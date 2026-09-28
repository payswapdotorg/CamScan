package org.payswap.camscan.capture.detect

import android.view.View
import androidx.fragment.app.testing.FragmentScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.payswap.camscan.R
import org.payswap.camscan.capture.camera.ScanFragment

/**
 * CAMSCAN-PROD-002 §6.8 — instrumentation skeleton for the detection surface.
 *
 * WHERE THIS RUNS: on the lead integration station's lab AVD, NOT in this
 * worker sandbox (no emulator here — the lead re-runs the full Gradle gate
 * including connected tests at integration time).
 *
 * WHAT IT PROVES: the scan surface still inflates (with the new detection
 * views) and the two new contract-frozen semantic ids resolve to real views
 * — the minimum an ADB parity test needs to drive the detection surface. It
 * needs no camera permission grant (the surface shows its permission
 * overlay) and no physical camera; live detection behavior itself is
 * verified by the JVM suites (detector/stabilizer/geometry) plus on-device
 * lead acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ScanFragmentOverlayTest {

    @Test
    fun scanSurfaceInflatesWithDetectionSemanticIds() {
        FragmentScenario.launchInContainer(
            ScanFragment::class.java,
            themeResId = R.style.Theme_CamScan,
        ).onFragment { fragment ->
            val root = fragment.requireView()

            // The two CAMSCAN-PROD-002 semantic ids, exact resource names.
            assertNotNull(
                "scan_framing_overlay must exist",
                root.findViewById<View>(R.id.scan_framing_overlay),
            )
            assertNotNull(
                "scan_detection_guidance must exist",
                root.findViewById<View>(R.id.scan_detection_guidance),
            )
        }
    }
}
