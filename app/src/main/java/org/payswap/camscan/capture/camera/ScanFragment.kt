package org.payswap.camscan.capture.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.payswap.camscan.R
import org.payswap.camscan.capture.detect.DetectionAnalyzer
import org.payswap.camscan.capture.detect.DetectionQualityFlag
import org.payswap.camscan.capture.detect.DetectionStabilizer
import org.payswap.camscan.capture.detect.EdgeQuadDetector
import org.payswap.camscan.capture.detect.FramingOverlayView
import org.payswap.camscan.capture.detect.StableDetection
import org.payswap.camscan.capture.session.CaptureImageDecoder
import org.payswap.camscan.capture.session.ReviewFragment
import org.payswap.camscan.capture.session.ScanSessionController
import org.payswap.camscan.capture.session.ScanSessionRegistry
import org.payswap.camscan.capture.session.SessionPersistAdapter
import org.payswap.camscan.capture.session.SessionPersistence
import org.payswap.camscan.core.time.TimeSource
import java.io.File
import java.util.UUID

/**
 * CAMSCAN-PROD-001 §6.5 / CAMSCAN-PROD-004 §6.6 — the live scan surface and
 * the multi-page session loop.
 *
 * Contract-frozen semantic ids (fragment_scan.xml) — ADB parity tests depend
 * on them; do not rename:
 *  scan_camera_preview, scan_capture_button, scan_flash_toggle,
 *  scan_switch_camera, scan_permission_request_button,
 *  scan_permission_rationale, scan_unavailable_state, scan_done_button,
 *  scan_framing_overlay, scan_detection_guidance, plus the PROD-004
 *  session_page_count_badge.
 *
 * Behavior:
 *  - hosts a [CameraController]; the [CameraStateMachine] is driven through
 *    the controller (its events originate from this surface's affordances);
 *  - permission overlay whenever the [CameraPermissionGate] is not GRANTED;
 *  - CAMSCAN-PROD-002: live document detection feeds the framing overlay
 *    and the guidance line; advisory only — capture is never gated;
 *  - CAMSCAN-PROD-004: every successful capture is decoded upright
 *    (EXIF-aware), mapped onto the latest stable detection quad, and
 *    appended into (or retaken over) the in-memory [ScanSessionController];
 *    the user is routed into [ReviewFragment] in the SAME container (a
 *    back-stack entry) to inspect / crop / rotate / enhance / accept or
 *    retake the page;
 *  - the session tray badge (session_page_count_badge) shows "N pages";
 *  - scan_done_button finishes the SESSION: ScanSession.finish() ->
 *    [SessionPersistAdapter] when [sessionPersistence] is wired (document id
 *    flows to [onSessionFinished]) or a null id otherwise — the shell's
 *    placeholder behavior, honestly preserved;
 *  - leaving the surface any other way (back navigation below the scan
 *    surface) reports abandonment via [onScanAbandoned].
 *
 * Persistence wiring (integration-station note): [CameraScanLauncher] passes
 * its optional sessionPersistence here before the transaction commits. A
 * null persistence keeps today's behavior (ids from UUID, finish reports
 * null); the shell swaps in the real wiring with a one-line construction
 * change on ITS side.
 *
 * Known honest limitations: the in-memory session is lost on process death
 * (documented PROD-001 carry-over); backing out DURING the done-button
 * persistence cancels it and reports abandonment; the scan -> review ->
 * scan loop survives view destruction by design (the controller lives in
 * the fragment instance + [ScanSessionRegistry]).
 */
class ScanFragment : Fragment(R.layout.fragment_scan) {

    /**
     * Persistence wiring for the session — set by [CameraScanLauncher] (or
     * a host) before the fragment transaction commits. Null keeps the
     * placeholder finish behavior (onScanFinished(null)); the typed
     * null-object is [SessionPersistence.UNAVAILABLE].
     */
    var sessionPersistence: SessionPersistence? = null

    /**
     * Invoked with the durable document id when the user finishes the
     * session via scan_done_button (null when persistence is unwired/failed
     * or the session was empty). Wired by [CameraScanLauncher] to
     * ScanHost.onScanFinished.
     */
    var onSessionFinished: ((String?) -> Unit)? = null

    /**
     * Invoked when the scan surface is left without finishing (back
     * navigation below the scan surface). Wired by [CameraScanLauncher] to
     * ScanHost.onScanFinished(null).
     */
    var onScanAbandoned: (() -> Unit)? = null

    private lateinit var permissionGate: CameraPermissionGate
    private var cameraController: CameraController? = null
    private var detectionAnalyzer: DetectionAnalyzer? = null

    private var cameraPreview: PreviewView? = null
    private var captureButton: Button? = null
    private var flashButton: Button? = null
    private var switchButton: Button? = null
    private var doneButton: Button? = null
    private var permissionRationale: TextView? = null
    private var permissionRequestButton: Button? = null
    private var permissionOverlay: View? = null
    private var unavailableState: TextView? = null
    private var framingOverlay: FramingOverlayView? = null
    private var guidanceText: TextView? = null
    private var pageBadge: TextView? = null

    /** The PROD-004 session state — created once per fragment instance. */
    private var sessionController: ScanSessionController? = null
    private var sessionToken: String? = null

    /** Latest detection result snapshot (the capture-time quad source). */
    private var lastDetection: DetectionAnalyzer.DetectionResult? = null

    /** True once the done path (or abandonment) has fired its callback. */
    private var finished = false

    /** True while a session finish is in flight (re-entry guard). */
    private var finishing = false

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
        framingOverlay = view.findViewById(R.id.scan_framing_overlay)
        guidanceText = view.findViewById(R.id.scan_detection_guidance)
        pageBadge = view.findViewById(R.id.session_page_count_badge)

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

        // The session (and its registry entry) survives the scan -> review ->
        // scan loop: the view is destroyed/recreated, the fragment instance
        // and the controller are not.
        if (sessionController == null) {
            val persistence = sessionPersistence
            val controller = ScanSessionController(
                idGenerator = if (persistence != null) {
                    persistence.idGenerator
                } else {
                    { UUID.randomUUID().toString() }
                },
                decodeCapture = CaptureImageDecoder::decode,
                persistResult = if (persistence != null) {
                    SessionPersistAdapter(persistence)::persist
                } else {
                    { null }
                },
            )
            val token = UUID.randomUUID().toString()
            ScanSessionRegistry.put(token, controller)
            sessionController = controller
            sessionToken = token
        }

        updatePageBadge()
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
        detectionAnalyzer?.shutdown()
        detectionAnalyzer = null
        cameraPreview = null
        captureButton = null
        flashButton = null
        switchButton = null
        doneButton = null
        permissionRationale = null
        permissionRequestButton = null
        permissionOverlay = null
        unavailableState = null
        framingOverlay = null
        guidanceText = null
        pageBadge = null
        lastDetection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Abandonment fires only when the fragment INSTANCE goes away without
        // a finish — replacing this surface with ReviewFragment merely
        // destroys the view (onDestroyView above), so the session loop does
        // not count as abandonment.
        if (!finished) {
            finished = true
            onScanAbandoned?.invoke()
        }
        ScanSessionRegistry.remove(sessionToken)
        sessionController = null
        sessionToken = null
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

        // CAMSCAN-PROD-002: live detection feeds the overlay + guidance line.
        // Bound into the controller's camera bind (analysis frames + the
        // stabilizer run on the analyzer's serial background executor;
        // results arrive on the main executor). Advisory only — capture is
        // never gated (see captureStill / updateCameraControls).
        val analyzer = DetectionAnalyzer(
            detector = EdgeQuadDetector(),
            stabilizer = DetectionStabilizer(),
            timeSource = timeSource,
            mainExecutor = ContextCompat.getMainExecutor(requireContext()),
        )
        analyzer.onDetectionResult = { result -> renderDetection(result) }
        detectionAnalyzer = analyzer
        controller.setAnalyzer(analyzer.analysisExecutor, analyzer)

        cameraController = controller
        controller.start()
    }

    /**
     * Renders one stabilizer result onto the overlay + guidance line. Runs on
     * the main executor; view refs are null-safe because a final in-flight
     * callback may land after the view is torn down. The latest result is
     * also snapshotted as the capture-time quad source.
     */
    private fun renderDetection(result: DetectionAnalyzer.DetectionResult) {
        lastDetection = result
        framingOverlay?.show(result.detection, result.frameWidth, result.frameHeight)
        guidanceText?.setText(guidanceFor(result.detection))
    }

    /** Maps a stable detection's flags onto the scan_guidance_* copy. */
    private fun guidanceFor(detection: StableDetection?): Int = when {
        detection == null -> R.string.scan_guidance_searching
        DetectionQualityFlag.NO_PAGE in detection.qualityFlags -> R.string.scan_guidance_no_page
        DetectionQualityFlag.PARTIAL_PAGE in detection.qualityFlags -> R.string.scan_guidance_partial
        DetectionQualityFlag.BLUR in detection.qualityFlags -> R.string.scan_guidance_blur
        DetectionQualityFlag.GLARE in detection.qualityFlags -> R.string.scan_guidance_glare
        DetectionQualityFlag.LOW_CONTRAST in detection.qualityFlags -> R.string.scan_guidance_low_contrast
        DetectionQualityFlag.MOTION_UNSTABLE in detection.qualityFlags -> R.string.scan_guidance_unstable
        else -> R.string.scan_guidance_locked
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
        val target = File(captureDir, "shot-${timeSource.nowMillis()}-${UUID.randomUUID()}.jpg")
        controller.takeStill(target) { outcome ->
            when (outcome) {
                is CaptureOutcome.Saved -> handleCaptureSaved(outcome.file)

                is CaptureOutcome.Failed -> {
                    val appContext = context
                    if (appContext != null) {
                        Toast.makeText(appContext, R.string.scan_capture_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * CAMSCAN-PROD-004: one still landed — decode it upright (EXIF-aware,
     * off the main thread), fold it into the session (append, or the armed
     * retake's atomic replace), then route into review.
     */
    private fun handleCaptureSaved(file: File) {
        val controller = sessionController ?: return
        val detection = lastDetection
        viewLifecycleOwner.lifecycleScope.launch {
            val index = withContext(Dispatchers.Default) {
                controller.submitCapture(
                    file = file,
                    detectedCorners = detection?.detection?.corners,
                    frameWidth = detection?.frameWidth ?: 0,
                    frameHeight = detection?.frameHeight ?: 0,
                )
            }
            if (index == null) {
                val appContext = context
                if (appContext != null) {
                    Toast.makeText(appContext, R.string.scan_capture_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                updatePageBadge()
                openReview(index)
            }
        }
    }

    /** Routes into [ReviewFragment] in the SAME container, back-stack entry. */
    private fun openReview(pageIndex: Int) {
        val token = sessionToken ?: return
        val review = ReviewFragment.newInstance(token, pageIndex)
        parentFragmentManager.beginTransaction()
            .replace(containerViewId(), review, REVIEW_FRAGMENT_TAG)
            .addToBackStack(REVIEW_BACK_STACK_NAME)
            .commit()
    }

    /**
     * The container this surface lives in: derived from the view's parent
     * (the host's ScanHost.containerViewId without needing the host);
     * android.R.id.content is the honest fallback (e.g. FragmentScenario).
     */
    private fun containerViewId(): Int {
        val parent = view?.parent as? ViewGroup
        val id = parent?.id ?: View.NO_ID
        return if (id != View.NO_ID) id else android.R.id.content
    }

    /** The session tray: "N pages" once the session holds pages. */
    private fun updatePageBadge() {
        val badge = pageBadge
        if (badge == null) return
        val count = sessionController?.pageCount ?: 0
        if (count > 0) {
            badge.text = resources.getQuantityString(R.plurals.session_page_count, count, count)
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
        doneButton?.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    /**
     * scan_done_button: finishes the SESSION — freeze the pages, persist
     * through the adapter when wired (suspend; runs before the surface
     * pops so the callback is not racing the coroutine scope), then report
     * the document id (or null: empty session / unwired persistence).
     */
    private fun finishScan() {
        if (finished || finishing) {
            return
        }
        finishing = true
        doneButton?.isEnabled = false
        val controller = sessionController
        viewLifecycleOwner.lifecycleScope.launch {
            // Await persistence BEFORE popping: the scope dies with the view.
            // (Backing out mid-persist cancels it; onDestroy then reports
            // abandonment — the documented degradation.)
            val documentId = controller?.finish()
            finished = true
            onSessionFinished?.invoke(documentId)
            parentFragmentManager.popBackStack()
        }
    }

    private companion object {
        const val CAPTURE_DIR_NAME = "scan-captures"
        const val REVIEW_FRAGMENT_TAG = "review"
        const val REVIEW_BACK_STACK_NAME = "review"
    }
}
