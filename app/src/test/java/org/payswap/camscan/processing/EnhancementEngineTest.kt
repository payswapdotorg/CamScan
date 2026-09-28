package org.payswap.camscan.processing


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.PageEnhancementMode

/*
 * CAMSCAN-PROD-003 §6.7 — EnhancementEngine coverage: every mode on
 * small hand-computed fixtures, byte-stability, non-destructiveness,
 * alpha preservation, the correct -> enhance mode chain, and the
 * malformed-input flag discipline.
 */
class EnhancementEngineTest {

    private val engine: EnhancementEngine = DefaultEnhancementEngine()

    // ---------------------------------------------------------- helpers

    private fun gray(v: Int, alpha: Int = 255): Int =
        (alpha shl 24) or (v shl 16) or (v shl 8) or v

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun r(pixel: Int): Int = (pixel shr 16) and 0xFF
    private fun g(pixel: Int): Int = (pixel shr 8) and 0xFF
    private fun b(pixel: Int): Int = pixel and 0xFF
    private fun alpha(pixel: Int): Int = (pixel shr 24) and 0xFF

    private fun page(pixels: IntArray, width: Int, height: Int, id: String = "test-page"): ProcessedImage =
        ProcessedImage(
            ImageBuffer(width, height, pixels),
            ProcessedGeometry(QuadF.fullFrame(width, height), width, height, width.toDouble() / height),
            PageEnhancementMode.ORIGINAL,
            id,
        )

    private fun grayPage(vararg rows: IntArray, id: String = "test-page"): ProcessedImage {
        val height = rows.size
        val width = rows[0].size
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = gray(rows[y][x])
            }
        }
        return page(pixels, width, height, id)
    }

    private fun allModes(): List<PageEnhancementMode> = PageEnhancementMode.entries

    // -------------------------------------------------------- ORIGINAL

    @Test
    fun originalReturnsIdentityCopyInANewBuffer() {
        val input = grayPage(intArrayOf(10, 20), intArrayOf(30, 40))
        val snapshot = input.buffer.argb.copyOf()
        val output = engine.process(input, PageEnhancementMode.ORIGINAL)
        assertTrue(output.buffer.argb.contentEquals(snapshot))
        assertTrue(output.buffer.argb !== input.buffer.argb) // fresh buffer
        assertEquals(PageEnhancementMode.ORIGINAL, output.enhancement)
        assertEquals(input.id, output.id)
        assertEquals(input.geometry, output.geometry)
    }

    // ------------------------------------------------------ GRAYSCALE

    @Test
    fun grayscaleLeavesGrayPixelsUnchanged() {
        // BT.601 of (v, v, v) is exactly v (integer round-half-up).
        val input = grayPage(intArrayOf(0, 64, 128), intArrayOf(200, 254, 255))
        val output = engine.process(input, PageEnhancementMode.GRAYSCALE)
        for (i in input.buffer.argb.indices) {
            assertEquals(input.buffer.argb[i], output.buffer.argb[i])
        }
    }

    @Test
    fun grayscaleComputesBt601LumaExactly() {
        val pixels = intArrayOf(
            argb(255, 255, 0, 0),   // luma 76
            argb(255, 0, 255, 0),   // luma 150
            argb(255, 0, 0, 255),   // luma 29
            argb(255, 10, 20, 30),  // luma 18
        )
        val input = page(pixels, 2, 2)
        val output = engine.process(input, PageEnhancementMode.GRAYSCALE)
        assertEquals(gray(76), output.buffer.argb[0])
        assertEquals(gray(150), output.buffer.argb[1])
        assertEquals(gray(29), output.buffer.argb[2])
        assertEquals(gray(18), output.buffer.argb[3])
    }

    @Test
    fun grayscalePreservesAlpha() {
        val pixels = intArrayOf(
            argb(128, 255, 0, 0),
            argb(200, 0, 255, 0),
        )
        val output = engine.process(page(pixels, 2, 1), PageEnhancementMode.GRAYSCALE)
        assertEquals(128, alpha(output.buffer.argb[0]))
        assertEquals(200, alpha(output.buffer.argb[1]))
    }

    // ------------------------------------------------- BLACK_AND_WHITE

    @Test
    fun blackAndWhiteSplitsTwoLumaClusters() {
        // 4 pixels at luma 60, 5 at luma 200: Otsu max between-class
        // variance is attained on t = 60..199; the smallest-maximizing-t
        // rule keeps 60 -> luma > 60 is paper (255).
        val input = grayPage(
            intArrayOf(60, 60, 60),
            intArrayOf(60, 200, 200),
            intArrayOf(200, 200, 200),
            id = "bw",
        )
        // Non-default alpha proves the mode preserves it.
        for (i in input.buffer.argb.indices) {
            input.buffer.argb[i] = (200 shl 24) or (input.buffer.argb[i] and 0x00FFFFFF)
        }
        val output = engine.process(input, PageEnhancementMode.BLACK_AND_WHITE)
        val expected = intArrayOf(0, 0, 0, 0, 255, 255, 255, 255, 255)
        for (i in expected.indices) {
            val pixel = output.buffer.argb[i]
            assertEquals("pixel $i", 200, alpha(pixel))
            assertEquals("pixel $i", expected[i], r(pixel))
            assertEquals(expected[i], g(pixel))
            assertEquals(expected[i], b(pixel))
        }
    }

    @Test
    fun blackAndWhiteFlatHistogramTieBreakIsPaperPositive() {
        val bright = grayPage(intArrayOf(200, 200), intArrayOf(200, 200))
        val brightOut = engine.process(bright, PageEnhancementMode.BLACK_AND_WHITE)
        for (i in brightOut.buffer.argb.indices) {
            assertEquals(255, r(brightOut.buffer.argb[i]))
        }

        val black = grayPage(intArrayOf(0, 0), intArrayOf(0, 0))
        val blackOut = engine.process(black, PageEnhancementMode.BLACK_AND_WHITE)
        for (i in blackOut.buffer.argb.indices) {
            assertEquals(0, r(blackOut.buffer.argb[i]))
        }
    }

    // -------------------------------------------------------- CONTRAST

    @Test
    fun contrastStretchesByLumaPercentiles() {
        // N = 9: 0.5th percentile = min (10), 99.5th = max (90); span 80.
        // (v - 10) * 255 / 80 with +40 rounding: 10->0, 30->64, 50->128,
        // 70->191, 90->255 (integer division).
        val input = grayPage(
            intArrayOf(10, 30, 50),
            intArrayOf(70, 90, 30),
            intArrayOf(50, 70, 90),
        )
        val output = engine.process(input, PageEnhancementMode.CONTRAST)
        val expected = intArrayOf(0, 64, 128, 191, 255, 64, 128, 191, 255)
        for (i in expected.indices) {
            assertEquals("pixel $i", gray(expected[i]), output.buffer.argb[i])
        }
    }

    @Test
    fun contrastFlatLumaIsIdentity() {
        val input = grayPage(intArrayOf(50, 50), intArrayOf(50, 50))
        val output = engine.process(input, PageEnhancementMode.CONTRAST)
        for (i in input.buffer.argb.indices) {
            assertEquals(input.buffer.argb[i], output.buffer.argb[i])
        }
    }

    @Test
    fun contrastClampsChannelsOutsideTheLumaRange() {
        // 8 grays at luma 60 + one (0, 153, 0) pixel at luma 90:
        // lo = 60, hi = 90, span 30, half 15. Gray 60 -> 0. The colored
        // pixel's R (0 < lo) clamps to 0; G (153 > hi) clamps to 255.
        val pixels = IntArray(9) { gray(60) }
        pixels[8] = argb(255, 0, 153, 0)
        val input = page(pixels, 3, 3)
        val output = engine.process(input, PageEnhancementMode.CONTRAST)
        for (i in 0 until 8) {
            assertEquals("pixel $i", gray(0), output.buffer.argb[i])
        }
        assertEquals(argb(255, 0, 255, 0), output.buffer.argb[8])
    }

    // -------------------------------------------------------- SHARPEN

    @Test
    fun sharpenAppliesCenter5CrossMinus1WithEdgeClamp() {
        val input = grayPage(
            intArrayOf(10, 20, 30),
            intArrayOf(40, 90, 60),
            intArrayOf(70, 80, 90),
        )
        val output = engine.process(input, PageEnhancementMode.SHARPEN)
        val out = output.buffer.argb
        // Center (1,1): 5*90 - (20 + 80 + 40 + 60) = 250.
        assertEquals(250, r(out[4]))
        // Corner (0,0): up/left clamp to self: 5*10 - (10 + 40 + 10 + 20) = -30 -> 0.
        assertEquals(0, r(out[0]))
        // (1,0): up clamps to self: 5*20 - (20 + 90 + 10 + 30) = -50 -> 0.
        assertEquals(0, r(out[1]))
        // (2,1): right clamps to self: 5*60 - (30 + 90 + 90 + 60) = 30.
        assertEquals(30, r(out[5]))
        // (2,2): down/right clamp to self: 5*90 - (60 + 90 + 80 + 90) = 130.
        assertEquals(130, r(out[8]))
    }

    @Test
    fun sharpenFlatImageIsIdentity() {
        // 5*c - 4*c = c for a uniform image.
        val input = grayPage(intArrayOf(128, 128), intArrayOf(128, 128))
        val output = engine.process(input, PageEnhancementMode.SHARPEN)
        for (i in input.buffer.argb.indices) {
            assertEquals(input.buffer.argb[i], output.buffer.argb[i])
        }
    }

    // ------------------------------------------------------ LOW_LIGHT

    @Test
    fun lowLightEndpointsAreExact() {
        // gamma(0) = 0, gamma(255) = 255; the 2-pixel stretch is the full
        // 0..255 range so both survive exactly.
        val input = grayPage(intArrayOf(0, 255))
        val output = engine.process(input, PageEnhancementMode.LOW_LIGHT)
        assertEquals(gray(0), output.buffer.argb[0])
        assertEquals(gray(255), output.buffer.argb[1])
    }

    @Test
    fun lowLightBrightensMidtonesAndPreservesHueProportion() {
        // gamma 1/1.6 < 1 brightens; luma 59 -> ~102. Proportional chroma
        // keeps the 2:1:0 channel ratio; the black channel stays black.
        val pixels = intArrayOf(argb(255, 100, 50, 0))
        val output = engine.process(page(pixels, 1, 1), PageEnhancementMode.LOW_LIGHT)
        val out = output.buffer.argb[0]
        assertEquals(0, b(out))                       // black channel exact
        assertTrue(r(out) in 170..176)                // ~173 (hand-checked)
        assertTrue(g(out) in 84..88)                  // ~86 (hand-checked)
        assertTrue(r(out) > g(out))                   // hue order preserved
        assertEquals(255, alpha(out))
    }

    // ---------------------------------- determinism / discipline / chain

    @Test
    fun everyModeIsByteStableAcrossRuns() {
        val input = grayPage(
            intArrayOf(10, 80, 160),
            intArrayOf(40, 120, 200),
            intArrayOf(0, 60, 255),
        )
        for (mode in allModes()) {
            val first = engine.process(input, mode)
            val second = engine.process(input, mode)
            assertTrue("mode $mode", first.buffer.argb.contentEquals(second.buffer.argb))
            assertEquals("mode $mode", first.id, second.id)
            assertEquals(first, second)
        }
    }

    @Test
    fun everyModeLeavesTheInputBufferUntouched() {
        val input = grayPage(
            intArrayOf(10, 80, 160),
            intArrayOf(40, 120, 200),
        )
        val snapshot = input.buffer.argb.copyOf()
        for (mode in allModes()) {
            engine.process(input, mode)
            assertTrue("mode $mode", input.buffer.argb.contentEquals(snapshot))
        }
    }

    @Test
    fun modeChainAfterCorrectionRetainsGeometryAndId() {
        // correct() -> enhance(): the contract's non-destructive chain.
        val gradient = ImageBuffer(4, 4, IntArray(16) { gray(10 + it * 10) })
        val corrected = BilinearPerspectiveCorrector()
            .correct(gradient, QuadF.fullFrame(4, 4))
        assertNotNull(corrected)
        val enhanced = engine.process(corrected!!, PageEnhancementMode.GRAYSCALE)
        assertEquals(corrected.geometry, enhanced.geometry)
        assertEquals(corrected.id, enhanced.id)
        assertEquals(PageEnhancementMode.GRAYSCALE, enhanced.enhancement)
        assertNotEquals(corrected, enhanced) // different pixels/mode
    }

    @Test
    fun malformedBufferNeverThrowsAndReportsDeclaredDims() {
        val malformed = ProcessedImage(
            ImageBuffer(2, 2, IntArray(3)), // mismatched storage
            ProcessedGeometry(QuadF.fullFrame(2, 2), 2, 2, 1.0),
            PageEnhancementMode.ORIGINAL,
            "malformed",
        )
        for (mode in allModes()) {
            val output = engine.process(malformed, mode)
            assertEquals(2, output.buffer.width)
            assertEquals(2, output.buffer.height)
            assertEquals(4, output.buffer.argb.size)
            assertEquals(0, output.buffer.argb[0])
            assertEquals("malformed", output.id)
            assertEquals(mode, output.enhancement)
        }
    }
}
