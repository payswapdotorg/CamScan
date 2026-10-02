package org.payswap.camscan.tools.watermark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// WatermarkApplier tests (CAMSCAN-PROD-011 section 6.6): glyph blits with
// integer alpha blend - opacity-to-alpha rule, spec-color RGB, clipping,
// non-mutation, determinism. The center tile of "A" on a 100x100 page
// sits at origin (47, 46): runW 6 -> 50 - 3, runH 9 -> 50 - 4.

class WatermarkApplierTest {

    private val W = 100
    private val H = 100
    private val WHITE = 0xFFFFFFFF.toInt()

    private fun whitePage(): IntArray = IntArray(W * H) { WHITE }

    private fun at(page: IntArray, x: Int, y: Int): Int = page[y * W + x]

    private fun rgb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun opaqueBlackWatermark_paintsGlyphPixels() {
        val spec = WatermarkSpec("A", 255, 0, 0xFF000000L, 0)
        val out = WatermarkApplier.apply(whitePage(), W, H, spec)
        // 'A' row 0 (0x04): column 2 -> (49, 46).
        assertEquals(0xFF000000.toInt(), at(out, 49, 46))
        // Row 4 (0x1F): all columns -> x 47..51 at y 50.
        assertEquals(0xFF000000.toInt(), at(out, 47, 50))
        assertEquals(0xFF000000.toInt(), at(out, 51, 50))
        // Column 0 of row 0 is off.
        assertEquals(WHITE, at(out, 47, 46))
    }

    @Test
    fun halfOpacityWatermark_blendsToDocumentedValue() {
        val spec = WatermarkSpec("A", 128, 0, 0xFF000000L, 0)
        val out = WatermarkApplier.apply(whitePage(), W, H, spec)
        // (0 * 128 + 255 * 127) / 255 = 127 exactly.
        assertEquals(rgb(255, 127, 127, 127), at(out, 49, 46))
        assertEquals(WHITE, at(out, 47, 46))
    }

    @Test
    fun specColor_rgbDrivesTheBlend() {
        val spec = WatermarkSpec("A", 128, 0, 0xFFFF0000L, 0)
        val out = WatermarkApplier.apply(whitePage(), W, H, spec)
        // Red at half alpha over white: (255, 127, 127).
        assertEquals(rgb(255, 255, 127, 127), at(out, 49, 46))
    }

    @Test
    fun specColorAlphaByte_isIgnored() {
        val translucent = WatermarkSpec("A", 255, 0, 0x01000000L, 0)
        val out = WatermarkApplier.apply(whitePage(), W, H, translucent)
        assertEquals(0xFF000000.toInt(), at(out, 49, 46))
    }

    @Test
    fun zeroOpacity_returnsPageUnchanged() {
        val spec = WatermarkSpec("A", 0, 0, 0xFF000000L, 0)
        val page = whitePage()
        assertArrayEquals(page, WatermarkApplier.apply(page, W, H, spec))
    }

    @Test
    fun emptyText_returnsPageUnchanged() {
        val spec = WatermarkSpec("", 255, 45, 0xFF000000L, 10)
        val page = whitePage()
        assertArrayEquals(page, WatermarkApplier.apply(page, W, H, spec))
    }

    @Test
    fun apply_neverMutatesInputPage() {
        val page = whitePage()
        val copy = page.copyOf()
        val spec = WatermarkSpec("DRAFT", 64, 45, 0xFF808080L, 8)
        WatermarkApplier.apply(page, W, H, spec)
        assertArrayEquals(copy, page)
    }

    @Test
    fun apply_isDeterministic_runTwice() {
        val spec = WatermarkSpec("DRAFT", 77, 30, 0xFF123456L, 5)
        val first = WatermarkApplier.apply(whitePage(), W, H, spec)
        val second = WatermarkApplier.apply(whitePage(), W, H, spec)
        assertArrayEquals(first, second)
    }

    @Test
    fun rotatedWatermark_stillInksTheCenterTile() {
        val spec = WatermarkSpec("A", 255, 45, 0xFF000000L, 0)
        val out = WatermarkApplier.apply(whitePage(), W, H, spec)
        // The center tile's origin is rotation-invariant: (47, 46).
        assertEquals(0xFF000000.toInt(), at(out, 49, 46))
    }

    @Test
    fun tilesClampInsideThePage() {
        val spec = WatermarkSpec("AB", 255, 0, 0xFF000000L, 0)
        val out = WatermarkApplier.apply(whitePage(), W, H, spec)
        // Corners stay white unless a glyph pixel lands there; simply
        // verify the page still contains painted pixels near the center
        // and that no exception occurs with heavily tiled output.
        var painted = 0
        for (y in 40 until 60) {
            for (x in 40 until 60) {
                if (at(out, x, y) != WHITE) painted++
            }
        }
        assertTrue(painted > 0)
    }

    @Test
    fun apply_rejectsMismatchedPageSize() {
        try {
            WatermarkApplier.apply(IntArray(5), W, H, WatermarkSpec("A", 255, 0, 0xFF000000L, 0))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size mismatch"))
        }
    }
}
