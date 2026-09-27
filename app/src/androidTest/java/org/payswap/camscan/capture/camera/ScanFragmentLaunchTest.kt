package org.payswap.camscan.capture.camera


import android.view.View
import androidx.fragment.app.testing.FragmentScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.payswap.camscan.R


/**
 * CAMSCAN-PROD-001 §6.8 — instrumentation skeleton for the scan surface.
 *
 * WHERE THIS RUNS: on the lead integration station's lab AVD, NOT in the
 * CAMSCAN-PROD-001 worker sandbox (that sandbox has no Android SDK, Gradle
 * cache or emulator — the lead re-runs the full Gradle gate including
 * connected tests at integration time).
 *
 * WHAT IT PROVES: the scan surface inflates and every contract-frozen
 * semantic id resolves to a real view — the minimum an ADB parity test needs
 * to drive the surface. It needs no camera permission grant (the surface
 * simply shows its permission overlay) and no physical camera.
 */
@RunWith(AndroidJUnit4::class)
class ScanFragmentLaunchTest {

    @Test
    fun scanSurfaceLaunchesWithContractFrozenSemanticIds() {
        FragmentScenario.launchInContainer(
            ScanFragment::class.java,
            themeResId = R.style.Theme_CamScan,
        ).onFragment { fragment ->
            val root = fragment.requireView()

            // §6.8 minimum: the preview and the capture button.
            assertNotNull("scan_camera_preview must exist", root.findViewById<View>(R.id.scan_camera_preview))
            assertNotNull("scan_capture_button must exist", root.findViewById<View>(R.id.scan_capture_button))

            // The remaining contract-frozen semantic ids.
            assertNotNull("scan_flash_toggle must exist", root.findViewById<View>(R.id.scan_flash_toggle))
            assertNotNull("scan_switch_camera must exist", root.findViewById<View>(R.id.scan_switch_camera))
            assertNotNull(
                "scan_permission_request_button must exist",
                root.findViewById<View>(R.id.scan_permission_request_button),
            )
            assertNotNull(
                "scan_permission_rationale must exist",
                root.findViewById<View>(R.id.scan_permission_rationale),
            )
            assertNotNull(
                "scan_unavailable_state must exist",
                root.findViewById<View>(R.id.scan_unavailable_state),
            )
            assertNotNull("scan_done_button must exist", root.findViewById<View>(R.id.scan_done_button))
        }
    }
}
