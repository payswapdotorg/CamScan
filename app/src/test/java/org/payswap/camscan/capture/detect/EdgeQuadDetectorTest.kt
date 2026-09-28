package org.payswap.camscan.capture.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAMSCAN-PROD-002 §6.7 — the detector suite (pure JUnit 4, no android.*
 * imports). Fixtures from [SyntheticFrames] — deterministic, no I/O, no
 * randomness.
 *
 * Calibrated tolerances (honest, not aspirational): corner accuracy for a
 * clean page is asserted within 2.5% of the frame width — "a few percent of
 * ground truth" per the work order — and the tilted/perspective page within
 * 5%, reflecting the coarser hull-vs-refinement geometry of a rotated edge.
 */
class EdgeQuadDetectorTest {

    private val detector = EdgeQuadDetector()

    private val frameWidth = 480
    private val frameHeight = 360

    /** A clean bright A4-ish page, centered, 60% x 60% of the frame. */
    private val cleanPageQuad = SyntheticFrames.quad(
        topLeft = 96 to 72,
        topRight = 384 to 72,
        bottomRight = 384 to 288,
        bottomLeft = 96 to 288,
    )

    // ------------------------------------------------------------------ clean page

    @Test
    fun `clean page is detected with corners within a few percent and sane confidence`() {
        val frame = SyntheticFrames.page(frameWidth, frameHeight, cleanPageQuad)
        val detection = detector.detect(frame, TIMESTAMP)

        assertNotNull("a clean bright page on a dark background must be detected", detection)
        detection!!
        assertEquals(4, detection.corners.size)

        val tolerancePx = 0.025 * frameWidth // 12 px — a few percent of truth
        for (i in 0 until 4) {
            val found = detection.corners[i]
            val truth = cleanPageQuad[i]
            val distance = QuadGeometry.distance(found, truth)
            assertTrue(
                "corner $i found at (${found.x},${found.y}) vs truth (${truth.x},${truth.y})" +
                    " — distance $distance exceeds $tolerancePx",
                distance <= tolerancePx,
            )
        }
        assertTrue(
            "confidence ${detection.confidence} must be in [0.5, 1.0]",
            detection.confidence >= 0.5f && detection.confidence <= 1.0f,
        )
        assertTrue(
            "a clean page must carry no quality flags (found ${detection.qualityFlags})",
            detection.qualityFlags.isEmpty(),
        )
        assertEquals(TIMESTAMP, detection.frameTimestampMs)
    }

    @Test
    fun `detector is deterministic — same frame and timestamp give equal detections`() {
        val frame = SyntheticFrames.page(frameWidth, frameHeight, cleanPageQuad)
        val first = detector.detect(frame, TIMESTAMP)
        val second = detector.detect(frame, TIMESTAMP)
        assertNotNull(first)
        assertEquals("data-class equality over identical inputs", first, second)
    }

    @Test
    fun `detector with noise is deterministic — same frame twice, equal detections`() {
        val frame = SyntheticFrames.page(
            frameWidth,
            frameHeight,
            cleanPageQuad,
            noiseAmplitude = 6,
        )
        assertEquals(detector.detect(frame, TIMESTAMP), detector.detect(frame, TIMESTAMP))
    }

    // ------------------------------------------------------------------ tilted / perspective page

    @Test
    fun `tilted perspective page is detected`() {
        val tilted = SyntheticFrames.quad(
            topLeft = 140 to 80,
            topRight = 360 to 60,
            bottomRight = 330 to 300,
            bottomLeft = 110 to 280,
        )
        val frame = SyntheticFrames.page(frameWidth, frameHeight, tilted)
        val detection = detector.detect(frame, TIMESTAMP)

        assertNotNull("a tilted page must be detected", detection)
        detection!!
        val tolerancePx = 0.05 * frameWidth // 24 px for the tilted fixture
        for (i in 0 until 4) {
            val found = detection.corners[i]
            val truth = tilted[i]
            assertTrue(
                "corner $i distance ${QuadGeometry.distance(found, truth)} exceeds $tolerancePx",
                QuadGeometry.distance(found, truth) <= tolerancePx,
            )
        }
    }

    // ------------------------------------------------------------------ receipt aspect ratio

    @Test
    fun `receipt aspect-ratio page is detected`() {
        // 130 x 310 — aspect ~2.4, the long-thin receipt family, fully inside
        // the frame (no border contact).
        val receipt = SyntheticFrames.quad(
            topLeft = 175 to 25,
            topRight = 305 to 25,
            bottomRight = 305 to 335,
            bottomLeft = 175 to 335,
        )
        val frame = SyntheticFrames.page(frameWidth, frameHeight, receipt)
        val detection = detector.detect(frame, TIMESTAMP)

        assertNotNull("a receipt-shaped page must be detected", detection)
        detection!!
        val detectedAspect = QuadGeometry.aspectRatio(detection.corners)
        val truthAspect = QuadGeometry.aspectRatio(receipt)
        assertTrue(
            "detected aspect $detectedAspect vs truth $truthAspect",
            kotlin.math.abs(detectedAspect - truthAspect) <= 0.35,
        )
        assertTrue(
            "receipt fully inside the frame must not flag PARTIAL_PAGE (found ${detection.qualityFlags})",
            DetectionQualityFlag.PARTIAL_PAGE !in detection.qualityFlags,
        )
    }

    // ------------------------------------------------------------------ blanks

    @Test
    fun `uniform blank frame yields null`() {
        val frame = SyntheticFrames.blank(frameWidth, frameHeight)
        assertNull("a uniform frame has nothing to detect", detector.detect(frame, TIMESTAMP))
    }

    @Test
    fun `malformed frame yields null not an exception`() {
        val broken = DetectorFrame(frameWidth, frameHeight, ByteArray(10))
        assertNull(detector.detect(broken, TIMESTAMP))
    }

    @Test
    fun `tiny frame yields null not an exception`() {
        val frame = DetectorFrame(16, 12, ByteArray(16 * 12))
        assertNull(detector.detect(frame, TIMESTAMP))
    }

    // ------------------------------------------------------------------ quality flags

    @Test
    fun `page touching the frame border flags PARTIAL_PAGE`() {
        // Flush to the left edge — the page continues outside the frame.
        val partial = SyntheticFrames.quad(
            topLeft = 0 to 72,
            topRight = 384 to 72,
            bottomRight = 384 to 288,
            bottomLeft = 0 to 288,
        )
        val frame = SyntheticFrames.page(frameWidth, frameHeight, partial)
        val detection = detector.detect(frame, TIMESTAMP)

        assertNotNull(detection)
        assertTrue(
            "a page flush to the border must flag PARTIAL_PAGE",
            DetectionQualityFlag.PARTIAL_PAGE in detection!!.qualityFlags,
        )
    }

    @Test
    fun `low-contrast page flags LOW_CONTRAST and is still returned`() {
        // Page 90 on background 60 — class separation 30, below the 40 floor.
        val frame = SyntheticFrames.page(
            frameWidth,
            frameHeight,
            cleanPageQuad,
            pageLuma = 90,
            backgroundLuma = 60,
        )
        val detection = detector.detect(frame, TIMESTAMP)

        assertNotNull("a weak-contrast page is degraded, not rejected", detection)
        assertTrue(
            "weak separation must flag LOW_CONTRAST",
            DetectionQualityFlag.LOW_CONTRAST in detection!!.qualityFlags,
        )
    }

    @Test
    fun `sub-minimum quad flags NO_PAGE`() {
        // A 20x20 bright speck — 0.23% of the frame, far below the 1% page
        // floor: a quad hypothesis that does not qualify as a page.
        val speck = SyntheticFrames.quad(
            topLeft = 230 to 170,
            topRight = 250 to 170,
            bottomRight = 250 to 190,
            bottomLeft = 230 to 190,
        )
        val frame = SyntheticFrames.page(frameWidth, frameHeight, speck)
        val detection = detector.detect(frame, TIMESTAMP)

        if (detection != null) {
            assertTrue(
                "a tiny quad is NO_PAGE, not a page (found ${detection.qualityFlags})",
                DetectionQualityFlag.NO_PAGE in detection.qualityFlags,
            )
        } else {
            // null is the other legal outcome for "nothing qualifies".
            assertNull(detection)
        }
    }

    // ------------------------------------------------------------------ constructor parameters

    @Test
    fun `constructor thresholds are honored — low contrast floor`() {
        // Separation 30, std ~14.4: below both default floors (40 / 20),
        // above configured 20 / 10.
        val frame = SyntheticFrames.page(
            frameWidth,
            frameHeight,
            cleanPageQuad,
            pageLuma = 90,
            backgroundLuma = 60,
        )
        val defaultDetector = EdgeQuadDetector()
        val relaxedDetector = EdgeQuadDetector(
            lowContrastSeparationThreshold = 20.0,
            lowContrastStdThreshold = 10.0,
        )

        assertTrue(
            DetectionQualityFlag.LOW_CONTRAST in
                (defaultDetector.detect(frame, TIMESTAMP)?.qualityFlags ?: emptySet()),
        )
        assertTrue(
            DetectionQualityFlag.LOW_CONTRAST !in
                (relaxedDetector.detect(frame, TIMESTAMP)?.qualityFlags ?: emptySet()),
        )
    }

    private companion object {
        private const val TIMESTAMP = 1_000L
    }
}
