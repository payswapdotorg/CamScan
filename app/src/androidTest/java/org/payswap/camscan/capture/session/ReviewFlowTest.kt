package org.payswap.camscan.capture.session

import androidx.fragment.app.testing.FragmentScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.payswap.camscan.R
import org.payswap.camscan.capture.camera.ScanFragment
import org.payswap.camscan.processing.ImageBuffer

/**
 * CAMSCAN-PROD-004 §6.8 — instrumentation skeleton for the review flow.
 *
 * WHERE THIS RUNS: on the lead integration station's lab AVD, NOT in this
 * worker sandbox (no emulator here — the sandbox gate compiles this suite
 * via :app:compileDebugAndroidTestKotlin only).
 *
 * WHAT IT PROVES: the simulated-capture leg of the P0 journey — a capture
 * folded into a session makes the review surface visible with EVERY
 * contract-frozen semantic id resolvable (what an ADB parity test needs to
 * drive accept / retake / crop / rotate / enhance), and the scan surface
 * exposes the session tray badge. No camera or permission grant is
 * required: the capture is simulated through the controller seam with a
 * synthetic page (the decode fake ignores the file argument).
 */
@RunWith(AndroidJUnit4::class)
class ReviewFlowTest {

    @Test
    fun reviewShowsContractFrozenIdsAfterSimulatedCapture() {
        val token = "review-flow-test-token"
        val controller = ScanSessionController(
            idGenerator = { "page-1" },
            decodeCapture = { syntheticPage() },
            persistResult = { null },
        )
        ScanSessionRegistry.put(token, controller)
        try {
            // The simulated capture: no camera involved.
            val index = checkNotNull(
                controller.submitCapture(File("/simulated/capture.jpg"), null, 0, 0),
            ) { "simulated capture failed" }
            assertEquals(0, index)

            FragmentScenario.launchInContainer(
                ReviewFragment::class.java,
                ReviewFragment.newInstance(token, index).arguments,
                R.style.Theme_CamScan,
            ).onFragment { fragment ->
                val root = fragment.requireView()

                // The contract-frozen review semantic ids.
                assertNotNull(root.findViewById<android.view.View>(R.id.review_page_image))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_accept_button))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_retake_button))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_crop_button))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_crop_sheet))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_rotate_button))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_enhancement_group))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_quality_banner))

                // The six enhancement mode ids.
                assertNotNull(root.findViewById<android.view.View>(R.id.review_enhancement_original))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_enhancement_grayscale))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_enhancement_black_and_white))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_enhancement_contrast))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_enhancement_sharpen))
                assertNotNull(root.findViewById<android.view.View>(R.id.review_enhancement_low_light))
            }
        } finally {
            ScanSessionRegistry.remove(token)
        }
    }

    @Test
    fun scanSurfaceExposesTheSessionBadge() {
        FragmentScenario.launchInContainer(
            ScanFragment::class.java,
            themeResId = R.style.Theme_CamScan,
        ).onFragment { fragment ->
            val root = fragment.requireView()
            assertNotNull(
                "session_page_count_badge must exist on the scan surface",
                root.findViewById<android.view.View>(R.id.session_page_count_badge),
            )
        }
    }

    /** Minimal inline fixture (the JVM suite's SyntheticPages lives in the
     * unit-test source set, invisible to androidTest). */
    private fun syntheticPage(): ImageBuffer {
        val width = 24
        val height = 24
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = (x * 10 + y * 3) % 256
                argb[y * width + x] = (0xFF shl 24) or (v shl 16) or ((v * 3) % 256 shl 8) or (255 - v)
            }
        }
        return ImageBuffer(width, height, argb)
    }
}
