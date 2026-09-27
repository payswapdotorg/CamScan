package org.payswap.camscan.capture.camera


import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File


/**
 * CAMSCAN-PROD-001 §6.4 — the CameraX glue.
 *
 * Thin by design: every decision is delegated to the pure components of
 * §6.1–§6.3 —
 *  - [CameraPermissionGate] gates binding and still capture;
 *  - [CameraStateMachine] owns the capture lifecycle;
 *  - [StableCameraState] / [StableCameraReducer] own settings + availability.
 *
 * Responsibilities:
 *  - binds Preview + ImageCapture to a [LifecycleOwner] through
 *    [ProcessCameraProvider.getInstance] using the ListenableFuture listener
 *    pattern (the future is only read inside its own listener, where it has
 *    already completed — there are no blocking waits);
 *  - rebinds on lens switch and on recoverable errors (bounded to
 *    [MAX_REBIND_ATTEMPTS] consecutive attempts, then a permanent ERROR);
 *  - maps the flash policy onto ImageCapture flash modes;
 *  - runs [takeStill] with the §6.4 failure taxonomy.
 *
 * Constraints honored: no Activity/Fragment imports beyond
 * LifecycleOwner/Context; main-thread confined (CameraX callbacks and our
 * executors all run on the main executor).
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val permissionGate: CameraPermissionGate,
) {
    /** UI observer for camera state and settings changes. */
    var listener: CameraControllerListener? = null

    private val stateMachine = CameraStateMachine()
    private var settings = StableCameraState()

    /** Current camera-settings snapshot. */
    val cameraSettings: StableCameraState
        get() = settings

    /** Current capture-lifecycle state snapshot. */
    val cameraState: CameraState
        get() = stateMachine.state

    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

    /** Monotonic bind generation: stale async bind listeners become no-ops. */
    private var bindGeneration = 0

    /** Consecutive recoverable-error rebind attempts since the last READY. */
    private var rebindAttempts = 0

    init {
        // Close automatically when the owning lifecycle dies, even if the host
        // forgets stop(). Idempotent with an explicit stop().
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                stop()
            }
        })
    }

    /**
     * Starts the camera. Returns false (with a diagnostic log, never a throw)
     * when the permission gate is not GRANTED or the machine is already
     * CLOSED.
     */
    fun start(): Boolean {
        if (permissionGate.state != CameraPermissionState.GRANTED) {
            logRejection("start() without GRANTED permission (state=${permissionGate.state})")
            return false
        }
        if (!stateMachine.bindStarted()) {
            logRejection("start() rejected in state ${stateMachine.state}")
            return false
        }
        notifyStateChanged()
        bindCamera()
        return true
    }

    /** Unbinds everything and closes the machine. Idempotent. */
    fun stop() {
        if (stateMachine.state != CameraState.CLOSED) {
            stateMachine.close()
            notifyStateChanged()
        }
        bindGeneration++ // invalidates any in-flight bind listeners
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        preview = null
    }

    /** Cycles the flash policy and applies it to the bound ImageCapture. */
    fun toggleFlash() {
        settings = StableCameraReducer.reduce(settings, CameraSettingEvent.ToggleFlash)
        imageCapture?.flashMode = flashModeOf(settings.flash)
        listener?.onSettingsChanged(settings)
    }

    /**
     * Flips BACK ⇄ FRONT and rebinds. Ignored (diagnostic log) while a capture
     * is in flight, while no usable camera is available, or in terminal
     * machine states — the pure reducer owns the availability rejection.
     */
    fun switchLens() {
        val machineState = stateMachine.state
        if (machineState != CameraState.READY && machineState != CameraState.OPENING) {
            logRejection("switchLens() ignored in state $machineState")
            return
        }
        val next = StableCameraReducer.reduce(settings, CameraSettingEvent.SwitchLens)
        if (next == settings) {
            logRejection("switchLens() rejected: no usable camera to switch to")
            return
        }
        settings = next
        listener?.onSettingsChanged(settings)
        if (machineState == CameraState.READY) {
            stateMachine.bindStarted() // READY → OPENING for the rebind
            notifyStateChanged()
        }
        bindCamera()
    }

    /**
     * Captures a still image into [target]. The [callback] receives
     * [CaptureOutcome.Saved] or [CaptureOutcome.Failed] exactly once, on the
     * main thread. [CaptureOutcome.Failed] classifies the failure with the
     * §6.4 reason taxonomy.
     */
    fun takeStill(target: File, callback: (CaptureOutcome) -> Unit) {
        if (permissionGate.state != CameraPermissionState.GRANTED) {
            callback(
                CaptureOutcome.Failed(
                    CaptureOutcome.Reason.NO_PERMISSION,
                    "camera permission state is ${permissionGate.state}",
                ),
            )
            return
        }
        val capture = imageCapture
        if (stateMachine.state != CameraState.READY) {
            callback(
                CaptureOutcome.Failed(
                    CaptureOutcome.Reason.NOT_READY,
                    "camera state is ${stateMachine.state}",
                ),
            )
            return
        }
        if (capture == null) {
            callback(
                CaptureOutcome.Failed(
                    CaptureOutcome.Reason.CAMERA_UNAVAILABLE,
                    "no bound ImageCapture",
                ),
            )
            return
        }
        if (!stateMachine.captureStarted()) {
            callback(
                CaptureOutcome.Failed(
                    CaptureOutcome.Reason.NOT_READY,
                    "capture start rejected in state ${stateMachine.state}",
                ),
            )
            return
        }
        notifyStateChanged()
        try {
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(target).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        stateMachine.captureComplete()
                        notifyStateChanged()
                        callback(CaptureOutcome.Saved(target))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        handleCaptureError(exception, callback)
                    }
                },
            )
        } catch (t: Throwable) {
            // Defensive: takePicture should not throw from READY, but a thrown
            // glue error must still surface as a classified failure.
            stateMachine.captureFailed()
            notifyStateChanged()
            callback(CaptureOutcome.Failed(CaptureOutcome.Reason.CAPTURE_FAILED, t.message))
        }
    }

    private fun handleCaptureError(exception: ImageCaptureException, callback: (CaptureOutcome) -> Unit) {
        val reason = when (exception.imageCaptureError) {
            ImageCapture.ERROR_FILE_IO -> CaptureOutcome.Reason.IO

            ImageCapture.ERROR_INVALID_CAMERA,
            ImageCapture.ERROR_CAMERA_CLOSED,
            -> CaptureOutcome.Reason.CAMERA_UNAVAILABLE

            // ERROR_CAPTURE_FAILED, ERROR_UNKNOWN and any other code (including
            // deprecated resolution-failure codes) are capture-level failures.
            else -> CaptureOutcome.Reason.CAPTURE_FAILED
        }
        // The failed capture re-arms the machine for the next shot. If the
        // machine already left CAPTURING (camera error raced the capture), the
        // no-op rejection is recorded as a diagnostic and never throws.
        stateMachine.captureFailed()
        notifyStateChanged()
        if (reason == CaptureOutcome.Reason.CAMERA_UNAVAILABLE) {
            onRecoverableCameraError("capture failed with a camera-level error: ${exception.message}")
        }
        callback(CaptureOutcome.Failed(reason, exception.message))
    }

    private fun onRecoverableCameraError(detail: String) {
        rebindAttempts++
        if (rebindAttempts > MAX_REBIND_ATTEMPTS) {
            Log.w(TAG, "exhausted $MAX_REBIND_ATTEMPTS rebind attempts ($detail); declaring permanent error")
            if (stateMachine.cameraError(recoverable = false)) {
                notifyStateChanged()
            }
            return
        }
        Log.w(TAG, "recoverable camera error; rebind attempt $rebindAttempts/$MAX_REBIND_ATTEMPTS ($detail)")
        if (stateMachine.cameraError(recoverable = true)) {
            notifyStateChanged()
        }
        if (stateMachine.state == CameraState.OPENING) {
            bindCamera()
        }
    }

    private fun bindCamera() {
        if (stateMachine.state == CameraState.CLOSED) {
            return
        }
        val generation = ++bindGeneration
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                // Stale generation or a closed controller: this bind is void.
                if (generation != bindGeneration || stateMachine.state == CameraState.CLOSED) {
                    return@addListener
                }
                val provider = try {
                    // Read inside the listener only: the future has completed here.
                    providerFuture.get()
                } catch (t: Throwable) {
                    onProviderFailure(generation, t)
                    return@addListener
                }
                cameraProvider = provider

                var selector = selectorFor(settings.lensFacing)
                if (!hasCameraOrFalse(provider, selector)) {
                    // The selected lens is missing on this device; try the other
                    // one before declaring the camera unavailable (back-less or
                    // front-less devices).
                    val fallbackLens = flipLens(settings.lensFacing)
                    if (hasCameraOrFalse(provider, selectorFor(fallbackLens))) {
                        settings = StableCameraReducer.reduce(settings, CameraSettingEvent.SwitchLens)
                        listener?.onSettingsChanged(settings)
                        selector = selectorFor(settings.lensFacing)
                    } else {
                        declareCameraUnavailable("no usable camera for either lens")
                        return@addListener
                    }
                }

                try {
                    val newCapture = ImageCapture.Builder()
                        .setFlashMode(flashModeOf(settings.flash))
                        .build()
                    val newPreview = Preview.Builder().build().also { p ->
                        p.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, selector, newPreview, newCapture)
                    imageCapture = newCapture
                    preview = newPreview
                    rebindAttempts = 0
                    if (stateMachine.cameraReady()) {
                        notifyStateChanged()
                    }
                } catch (t: Throwable) {
                    onProviderFailure(generation, t)
                }
            },
            mainExecutor,
        )
    }

    private fun onProviderFailure(generation: Int, error: Throwable) {
        if (generation != bindGeneration) {
            return
        }
        Log.w(TAG, "camera bind failed", error)
        onRecoverableCameraError("bind failed: ${error.message}")
    }

    private fun declareCameraUnavailable(detail: String) {
        Log.w(TAG, "camera unavailable: $detail")
        settings = StableCameraReducer.reduce(settings, CameraSettingEvent.SetAvailable(false))
        listener?.onSettingsChanged(settings)
        if (stateMachine.cameraError(recoverable = false)) {
            notifyStateChanged()
        }
    }

    private fun hasCameraOrFalse(provider: ProcessCameraProvider, selector: CameraSelector): Boolean = try {
        provider.hasCamera(selector)
    } catch (t: Throwable) {
        false
    }

    private fun selectorFor(lens: LensFacing): CameraSelector = when (lens) {
        LensFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        LensFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    private fun flipLens(lens: LensFacing): LensFacing = when (lens) {
        LensFacing.BACK -> LensFacing.FRONT
        LensFacing.FRONT -> LensFacing.BACK
    }

    private fun flashModeOf(flash: FlashMode): Int = when (flash) {
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    }

    private fun notifyStateChanged() {
        listener?.onCameraStateChanged(stateMachine.state)
    }

    private fun logRejection(message: String) {
        Log.w(TAG, message)
    }

    companion object {
        private const val TAG = "CameraController"

        /** Consecutive rebind attempts allowed before declaring a permanent error. */
        private const val MAX_REBIND_ATTEMPTS = 3
    }
}


/** Observer surface the scan UI implements to mirror camera state. */
interface CameraControllerListener {
    /** A [CameraStateMachine] transition happened. */
    fun onCameraStateChanged(state: CameraState)

    /** Camera settings or availability changed. */
    fun onSettingsChanged(settings: StableCameraState)
}


/**
 * Result of [CameraController.takeStill] — CAMSCAN-PROD-001 §6.4.
 * The callback fires exactly once, on the main thread.
 */
sealed class CaptureOutcome {

    /** The still was written to [file]. */
    data class Saved(val file: File) : CaptureOutcome()

    /** The still failed; [reason] classifies the failure per the §6.4 taxonomy. */
    data class Failed(
        val reason: Reason,
        val detail: String? = null,
    ) : CaptureOutcome()

    /** §6.4 failure taxonomy. */
    enum class Reason { IO, CAPTURE_FAILED, CAMERA_UNAVAILABLE, NOT_READY, NO_PERMISSION }
}
