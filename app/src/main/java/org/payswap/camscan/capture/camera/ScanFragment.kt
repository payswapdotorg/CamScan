package org.payswap.camscan.capture.camera


import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.payswap.camscan.R
import org.payswap.camscan.core.time.TimeSource
import java.io.File


/**
 * CAMSCAN-PROD-001 §6.5 — the live scan surface.
 *
 * Contract-frozen semantic ids (fragment_scan.xml) — ADB parity tests depend
 * on them; do not rename:
 *  scan_camera_preview, scan_capture_button, scan_flash_toggle,
 *  scan_switch_camera, scan_permission_request_button,
 *  scan_permission_rationale, scan_unavailable_state, scan_done_button.
 *
 * Behavior:
 *  - hosts a [CameraController]; the [CameraStateMachine] is driven through
 *    the controller (its events originate from this surface's affordances);
 *  - permission overlay whenever the [CameraPermissionGate] is not GRANTED
 *    (rationale text + request button via RequestPermission);
 *  - capture button enabled only while the machine is READY; on success the
 *    shot is kept in memory as a [CapturedShot] and the surface re-arms;
 *    document persistence is NOT this work order's concern;
 *  - [onCaptureResult] is invoked with all shots when the user finishes via
 *    scan_done_button; [onScanAbandoned] fires when the surface is left any
 *    other way (back navigation);
 *  - graceful no-camera / permanent-error state (scan_unavailable_state).
 *
 * Known foundation limitation (deliberate, later work orders own the fix): the
 * in-memory shot list is lost on process death/config change; the
 * scan-session work order (PROD-004) introduces durable sessions.
 */
class ScanFragment : Fragment(R.layout.fragment_scan) {

    /**
     * Invoked with the in-memory shots when the user finishes the scan via
     * scan_done_button. Consumed by the shell/session flow in later work
     * orders; [CameraScanLauncher] wires the default completion path.
     */
    var onCaptureResult: ((List<CapturedShot>) -> Unit)? = null

    /**
     * Invoked when the scan surface is left without finishing (back
     * navigation). Wired by [CameraScanLauncher] to ScanHost.onScanFinished(null).
     */
    var onScanAbandoned: (() -> Unit)? = null

    private lateinit var permissionGate: CameraPermissionGate
    private var cameraController: CameraController? = null

    private var cameraPreview: PreviewView? = null
    private var captureButton: Button? = null
    private var flashButton: Button? = null
    private var switchButton: Button? = null
    private var doneButton: Button? = null
    private var permissionRationale: TextView? = null
    private var permissionRequestButton: Button? = null
    private var permissionOverlay: View? = null
    private var unavailableState: TextView? = null

    private val capturedShots = mutableListOf<CapturedShot>()

    /** True once the done path (or abandonment) has fired its callback. */
    private var finished = false

    /** Determinism seam: product time is read through TimeSource, never System directly. */
    private val timeSource: TimeSource = TimeSource.SYSTEM

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGate.onPermissionResult(granted)
            onPermissionStateChanged()
        }

    private val controllerListener = object : CameraControllerListener {
        override fun onCameraStateChanged(state: CameraState) {
            updateCameraControls()
        }

        override fun onSettingsChanged(settings: StableCameraState) {
            updateSettingsControls(settings)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraPreview = view.findViewById(R.id.scan_camera_preview)
        captureButton = view.findViewById(R.id.scan_capture_button)
        flashButton = view.findViewById(R.id.scan_flash_toggle)
        switchButton = view.findViewById(R.id.scan_switch_camera)
        doneButton = view.findViewById(R.id.scan_done_button)
        permissionRationale = view.findViewById(R.id.scan_permission_rationale)
        permissionRequestButton = view.findViewById(R.id.scan_permission_request_button)
        permissionOverlay = view.findViewById(R.id.scan_permission_overlay)
        unavailableState = view.findViewById(R.id.scan_unavailable_state)

        permissionGate = CameraPermissionGate(
            RationaleChecker { shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) },
        )
        // Seed the gate from the current OS state: a grant from a previous
        // session (or a still-active one-time grant) starts the surface in
        // GRANTED without an in-session request.
        permissionGate.onPermissionResult(isOsPermissionGranted())

        permissionRequestButton?.setOnClickListener {
            // While a system dialog is in flight, extra clicks are ignored.
            if (permissionGate.state == CameraPermissionState.REQUESTED) {
                return@setOnClickListener
            }
            // Always attempt the launch: on a permanent denial the OS answers
            // instantly with denied (no dialog) and the gate + UI re-classify
            // honestly. requestStarted() itself may be rejected (diagnostic).
            permissionGate.requestStarted()
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        captureButton?.setOnClickListener { captureStill() }
        flashButton?.setOnClickListener { cameraController?.toggleFlash() }
        switchButton?.setOnClickListener { cameraController?.switchLens() }
        doneButton?.setOnClickListener { finishScan() }

        onPermissionStateChanged()
    }

    override fun onResume() {
        super.onResume()
        // OS state may have changed while paused (revocation or one-time-grant
        // expiry). Re-seed the gate when it disagrees with the OS.
        if (this::permissionGate.isInitialized) {
            val granted = isOsPermissionGranted()
            if (granted != (permissionGate.state == CameraPermissionState.GRANTED)) {
                permissionGate.onPermissionResult(granted)
                onPermissionStateChanged()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraController?.stop()
        cameraController = null
        if (!finished) {
            finished = true
            onScanAbandoned?.invoke()
        }
        cameraPreview = null
        captureButton = null
        flashButton = null
        switchButton = null
        doneButton = null
        permissionRationale = null
        permissionRequestButton = null
        permissionOverlay = null
        unavailableState = null
    }

    private fun isOsPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun onPermissionStateChanged() {
        if (permissionGate.state == CameraPermissionState.GRANTED) {
            permissionOverlay?.visibility = View.GONE
            startCamera()
        } else {
            showPermissionOverlay()
        }
    }

    private fun showPermissionOverlay() {
        permissionOverlay?.visibility = View.VISIBLE
        permissionRationale?.setText(
            when (permissionGate.state) {
                CameraPermissionState.NOT_REQUESTED,
                CameraPermissionState.REQUESTED,
                -> R.string.scan_permission_needed

                CameraPermissionState.DENIED_SOFT -> R.string.scan_permission_denied_soft
                CameraPermissionState.DENIED_PERMANENT -> R.string.scan_permission_denied_permanent
                CameraPermissionState.GRANTED -> R.string.scan_permission_needed
            },
        )
        updateCameraControls()
    }

    private fun startCamera() {
        if (cameraController != null) {
            return
        }
        val preview = cameraPreview ?: return
        val controller = CameraController(
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            previewView = preview,
            permissionGate = permissionGate,
        )
        controller.listener = controllerListener
        cameraController = controller
        controller.start()
    }

    private fun updateCameraControls() {
        val controller = cameraController
        val cameraState = controller?.cameraState ?: CameraState.IDLE
        val cameraSettings = controller?.cameraSettings
        captureButton?.isEnabled =
            permissionGate.state == CameraPermissionState.GRANTED && cameraState == CameraState.READY
        val unavailable = cameraSettings?.available == false || cameraState == CameraState.ERROR
        unavailableState?.visibility = if (unavailable) View.VISIBLE else View.GONE
    }

    private fun updateSettingsControls(cameraSettings: StableCameraState) {
        val flashLabel = when (cameraSettings.flash) {
            FlashMode.AUTO -> R.string.scan_flash_auto
            FlashMode.ON -> R.string.scan_flash_on
            FlashMode.OFF -> R.string.scan_flash_off
        }
        flashButton?.let { button ->
            button.setText(flashLabel)
            button.contentDescription = getString(flashLabel)
        }
        switchButton?.isEnabled = cameraSettings.available
        updateCameraControls()
    }

    private fun captureStill() {
        val controller = cameraController ?: return
        val captureDir = File(requireContext().cacheDir, CAPTURE_DIR_NAME).apply { mkdirs() }
        val target = File(captureDir, "shot-${capturedShots.size + 1}-${timeSource.nowMillis()}.jpg")
        controller.takeStill(target) { outcome ->
            when (outcome) {
                is CaptureOutcome.Saved -> {
                    capturedShots.add(
                        CapturedShot(
                            file = outcome.file,
                            capturedAtMillis = timeSource.nowMillis(),
                            lensFacing = controller.cameraSettings.lensFacing,
                            flash = controller.cameraSettings.flash,
                        ),
                    )
                    doneButton?.visibility = View.VISIBLE
                }

                is CaptureOutcome.Failed -> {
                    val appContext = context
                    if (appContext != null) {
                        Toast.makeText(appContext, R.string.scan_capture_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun finishScan() {
        if (finished) {
            return
        }
        finished = true
        val shots = capturedShots.toList()
        if (shots.isNotEmpty()) {
            onCaptureResult?.invoke(shots)
        }
        parentFragmentManager.popBackStack()
    }

    private companion object {
        const val CAPTURE_DIR_NAME = "scan-captures"
    }
}
