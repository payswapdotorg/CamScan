package org.payswap.camscan.tools.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// DrawSurface tests (CAMSCAN-PROD-011 section 6.6): Bresenham
// pixel-exactness on small buffers, square-brush geometry, the exact
// integer source-over rounding table, multiply blending, glyph blitting,
// clipping, and byte determinism.

class DrawSurfaceTest {

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()

    private fun surface(w: Int, h: Int): DrawSurface = DrawSurface(w, h)

    private fun whiteBuffer(w: Int, h: Int): IntArray = IntArray(w * h) { WHITE }

    private fun rgb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun at(buffer: IntArray, w: Int, x: Int, y: Int): Int = buffer[y * w + x]

    private fun stroke(vararg points: Point): DrawOp.Stroke =
        DrawOp.Stroke(points.toList(), 1, 0xFF000000L)

    private fun pixelSet(buffer: IntArray, w: Int, color: Int = BLACK): Set<Long> {
        val set = HashSet<Long>()
        for (y in 0 until buffer.size / w) {
            for (x in 0 until w) {
                if (buffer[y * w + x] == color) set.add(y.toLong() * 100000L + x)
            }
        }
        return set
    }

    private fun xy(x: Int, y: Int): Long = y.toLong() * 100000L + x

    // ------------------------------------------------------------------
    // Bresenham pixel exactness (width 1, square brush = single pixel)
    // ------------------------------------------------------------------

    @Test
    fun bresenham_horizontalLine_isExact() {
        val out = surface(5, 3).render(DrawPlan(listOf(stroke(Point(0, 0), Point(3, 0)))))
        assertEquals(setOf(xy(0, 0), xy(1, 0), xy(2, 0), xy(3, 0)), pixelSet(out, 5))
    }

    @Test
    fun bresenham_verticalLine_isExact() {
        val out = surface(5, 3).render(DrawPlan(listOf(stroke(Point(1, 0), Point(1, 2)))))
        assertEquals(setOf(xy(1, 0), xy(1, 1), xy(1, 2)), pixelSet(out, 5))
    }

    @Test
    fun bresenham_perfectDiagonal_isExact() {
        val out = surface(4, 4).render(DrawPlan(listOf(stroke(Point(0, 0), Point(3, 3)))))
        assertEquals(setOf(xy(0, 0), xy(1, 1), xy(2, 2), xy(3, 3)), pixelSet(out, 4))
    }

    @Test
    fun bresenham_shallowLine_isExact() {
        val out = surface(5, 3).render(DrawPlan(listOf(stroke(Point(0, 0), Point(3, 1)))))
        assertEquals(setOf(xy(0, 0), xy(1, 0), xy(2, 1), xy(3, 1)), pixelSet(out, 5))
    }

    @Test
    fun bresenham_steepLine_isExact() {
        val out = surface(5, 4).render(DrawPlan(listOf(stroke(Point(0, 0), Point(1, 3)))))
        assertEquals(setOf(xy(0, 0), xy(0, 1), xy(1, 2), xy(1, 3)), pixelSet(out, 5))
    }

    @Test
    fun bresenham_reversedLine_coversSamePixels() {
        val forward = surface(5, 4).render(DrawPlan(listOf(stroke(Point(0, 0), Point(1, 3)))))
        val backward = surface(5, 4).render(DrawPlan(listOf(stroke(Point(1, 3), Point(0, 0)))))
        assertEquals(pixelSet(forward, 5), pixelSet(backward, 5))
    }

    @Test
    fun multiSegmentStroke_connectsAllPoints() {
        val out = surface(9, 3).render(
            DrawPlan(listOf(stroke(Point(0, 0), Point(4, 2), Point(8, 0)))),
        )
        // Segment 1 (0,0)->(4,2): (0,0),(1,0),(2,1),(3,1),(4,2).
        // Segment 2 (4,2)->(8,0): (4,2),(5,2),(6,1),(7,1),(8,0).
        assertEquals(
            setOf(
                xy(0, 0), xy(1, 0), xy(2, 1), xy(3, 1), xy(4, 2),
                xy(5, 2), xy(6, 1), xy(7, 1), xy(8, 0),
            ),
            pixelSet(out, 9),
        )
    }

    @Test
    fun singlePointStroke_plotsExactlyOnePixel() {
        val out = surface(5, 5).render(DrawPlan(listOf(stroke(Point(2, 3)))))
        assertEquals(setOf(xy(2, 3)), pixelSet(out, 5))
    }

    @Test
    fun emptyPointList_rendersNothing() {
        val out = surface(5, 5).render(DrawPlan(listOf(DrawOp.Stroke(emptyList(), 3, 0xFF000000L))))
        assertEquals(0, pixelSet(out, 5).size)
    }

    // ------------------------------------------------------------------
    // Square brush geometry (documented rule)
    // ------------------------------------------------------------------

    @Test
    fun brushWidth1_isSinglePixel() {
        val out = surface(5, 5).render(DrawPlan(listOf(DrawOp.Stroke(listOf(Point(2, 2)), 1, 0xFF000000L))))
        assertEquals(setOf(xy(2, 2)), pixelSet(out, 5))
    }

    @Test
    fun brushWidth3_isSymmetricThreeByThree() {
        val out = surface(7, 7).render(DrawPlan(listOf(DrawOp.Stroke(listOf(Point(3, 3)), 3, 0xFF000000L))))
        val expected = HashSet<Long>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                expected.add(xy(3 + dx, 3 + dy))
            }
        }
        assertEquals(expected, pixelSet(out, 7))
    }

    @Test
    fun brushWidth2_usesDocumentedAsymmetricSpan() {
        val out = surface(7, 7).render(DrawPlan(listOf(DrawOp.Stroke(listOf(Point(3, 3)), 2, 0xFF000000L))))
        assertEquals(
            setOf(xy(2, 2), xy(2, 3), xy(3, 2), xy(3, 3)),
            pixelSet(out, 7),
        )
    }

    @Test
    fun strokeClips_atSurfaceBounds() {
        val out = surface(5, 3).render(DrawPlan(listOf(stroke(Point(-2, 0), Point(2, 0)))))
        assertEquals(setOf(xy(0, 0), xy(1, 0), xy(2, 0)), pixelSet(out, 5))
    }

    // ------------------------------------------------------------------
    // FillRect
    // ------------------------------------------------------------------

    @Test
    fun fillRect_fillsExactRegion() {
        val out = surface(5, 5).render(DrawPlan(listOf(DrawOp.FillRect(Rect(1, 1, 2, 2), 0xFF000000L))))
        assertEquals(setOf(xy(1, 1), xy(2, 1), xy(1, 2), xy(2, 2)), pixelSet(out, 5))
    }

    @Test
    fun fillRect_clipsToSurface() {
        val out = surface(5, 3).render(DrawPlan(listOf(DrawOp.FillRect(Rect(2, 1, 4, 4), 0xFF000000L))))
        assertEquals(setOf(xy(2, 1), xy(3, 1), xy(4, 1), xy(2, 2), xy(3, 2), xy(4, 2)), pixelSet(out, 5))
    }

    // ------------------------------------------------------------------
    // Alpha blending rounding table (exact integer source-over)
    // ------------------------------------------------------------------

    @Test
    fun blend_halfAlphaRed_overWhite_isDocumentedValue() {
        val base = whiteBuffer(3, 1)
        val out = surface(3, 1).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 3, 1), 0xFFFF0000L, 128))))
        assertEquals(rgb(255, 255, 127, 127), at(out, 3, 0, 0))
        assertEquals(rgb(255, 255, 127, 127), at(out, 3, 2, 0))
    }

    @Test
    fun blend_halfAlphaRed_overBlack_isDocumentedValue() {
        val base = IntArray(3) { BLACK }
        val out = surface(3, 1).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 3, 1), 0xFFFF0000L, 128))))
        assertEquals(rgb(255, 128, 0, 0), at(out, 3, 0, 0))
    }

    @Test
    fun blend_roundsHalfUp_aboveTheTie() {
        // (1*128 + 255*127)/255 = 127.502... -> 128 (round-half-up).
        val base = whiteBuffer(1, 1)
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 1, 1), 0xFF010101L, 128))))
        assertEquals(rgb(255, 128, 128, 128), at(out, 1, 0, 0))
    }

    @Test
    fun blend_alphaOne_overWhite_darkensByOne() {
        val base = whiteBuffer(1, 1)
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 1, 1), 0xFF000000L, 1))))
        assertEquals(rgb(255, 254, 254, 254), at(out, 1, 0, 0))
    }

    @Test
    fun blend_overTransparent_keepsSourceRgbAndAlpha() {
        val out = surface(1, 1).render(DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 1, 1), 0xFFFF0000L, 100))))
        assertEquals(rgb(100, 255, 0, 0), at(out, 1, 0, 0))
    }

    @Test
    fun blend_alphaZero_isNoOp() {
        val base = whiteBuffer(2, 1)
        val out = surface(2, 1).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 2, 1), 0xFF000000L, 0))))
        assertArrayEquals(base, out)
    }

    @Test
    fun blend_alpha255_replaces() {
        val base = whiteBuffer(2, 1)
        val out = surface(2, 1).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 2, 1), 0xFFFF0000L, 255))))
        assertEquals(rgb(255, 255, 0, 0), at(out, 2, 0, 0))
    }

    @Test
    fun blendRect_ignoresColorAlphaByte() {
        val base = whiteBuffer(1, 1)
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(0, 0, 1, 1), 0x01FF0000L, 255))))
        assertEquals(rgb(255, 255, 0, 0), at(out, 1, 0, 0))
    }

    @Test
    fun fillRect_withColorAlpha_blendsSourceOver() {
        val base = whiteBuffer(1, 1)
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.FillRect(Rect(0, 0, 1, 1), 0x80000000L))))
        assertEquals(rgb(255, 127, 127, 127), at(out, 1, 0, 0))
    }

    @Test
    fun stroke_withColorAlpha_blendsSourceOver() {
        val base = whiteBuffer(3, 3)
        val plan = DrawPlan(listOf(DrawOp.Stroke(listOf(Point(1, 1)), 1, 0x80000000L)))
        val out = surface(3, 3).render(base, plan)
        assertEquals(rgb(255, 127, 127, 127), at(out, 3, 1, 1))
    }

    @Test
    fun blendRect_clipsToSurface() {
        val base = whiteBuffer(3, 3)
        val out = surface(3, 3).render(base, DrawPlan(listOf(DrawOp.BlendRect(Rect(1, 1, 5, 5), 0xFF000000L, 128))))
        assertEquals(rgb(255, 127, 127, 127), at(out, 3, 2, 2))
        assertEquals(WHITE, at(out, 3, 0, 0))
        assertEquals(WHITE, at(out, 3, 2, 0))
    }

    // ------------------------------------------------------------------
    // Multiply blending (Highlight semantics)
    // ------------------------------------------------------------------

    @Test
    fun multiply_yellow_overWhite_isYellow() {
        val base = whiteBuffer(1, 1)
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 0xFFFFFF00L))))
        assertEquals(rgb(255, 255, 255, 0), at(out, 1, 0, 0))
    }

    @Test
    fun multiply_yellow_overBlack_isBlack() {
        val base = IntArray(1) { BLACK }
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 0xFFFFFF00L))))
        assertEquals(BLACK, at(out, 1, 0, 0))
    }

    @Test
    fun multiply_yellow_overGray_isDarkYellow() {
        val base = IntArray(1) { rgb(255, 128, 128, 128) }
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 0xFFFFFF00L))))
        assertEquals(rgb(255, 128, 128, 0), at(out, 1, 0, 0))
    }

    @Test
    fun multiply_isPerChannel() {
        val base = IntArray(1) { rgb(255, 200, 100, 50) }
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 0xFFFFFF00L))))
        assertEquals(rgb(255, 200, 100, 0), at(out, 1, 0, 0))
    }

    @Test
    fun multiply_rounding_isHalfUp() {
        // 200 * 130 / 255 = 101.96 -> 102.
        val base = IntArray(1) { rgb(255, 200, 200, 200) }
        val out = surface(1, 1).render(base, DrawPlan(listOf(DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 0xFF828282L))))
        assertEquals(rgb(255, 102, 102, 102), at(out, 1, 0, 0))
    }

    // ------------------------------------------------------------------
    // Glyph blitting
    // ------------------------------------------------------------------

    @Test
    fun blitGlyphs_rendersLetterI_exactly() {
        val out = surface(12, 9).render(DrawPlan(listOf(DrawOp.BlitGlyphs("I", Point(0, 0), "builtin-5x7", 0xFF000000L))))
        // Row 0 of 'I' is 0x0E: columns 1..3; rows 1..5 are 0x04: column 2;
        // row 6 is 0x0E again.
        assertTrue(at(out, 12, 1, 0) == BLACK)
        assertTrue(at(out, 12, 2, 0) == BLACK)
        assertTrue(at(out, 12, 3, 0) == BLACK)
        assertFalse(at(out, 12, 0, 0) == BLACK)
        assertFalse(at(out, 12, 4, 0) == BLACK)
        assertTrue(at(out, 12, 2, 3) == BLACK)
        assertFalse(at(out, 12, 1, 3) == BLACK)
        assertTrue(at(out, 12, 1, 6) == BLACK)
        assertTrue(at(out, 12, 3, 6) == BLACK)
        assertFalse(at(out, 12, 0, 6) == BLACK)
    }

    @Test
    fun blitGlyphs_advancesSixPxPerChar() {
        val out = surface(13, 7).render(DrawPlan(listOf(DrawOp.BlitGlyphs("II", Point(0, 0), "builtin-5x7", 0xFF000000L))))
        // Second 'I' occupies columns 6..10; its row-0 bar covers columns 7..9.
        assertTrue(at(out, 13, 7, 0) == BLACK)
        assertTrue(at(out, 13, 9, 0) == BLACK)
        assertFalse(at(out, 13, 6, 0) == BLACK)
        assertFalse(at(out, 13, 10, 0) == BLACK)
        assertFalse(at(out, 13, 11, 0) == BLACK)
    }

    @Test
    fun blitGlyphs_honorsOpColor() {
        val out = surface(6, 7).render(DrawPlan(listOf(DrawOp.BlitGlyphs("I", Point(0, 0), "builtin-5x7", 0xFF336699L))))
        assertEquals(rgb(255, 0x33, 0x66, 0x99), at(out, 6, 2, 3))
    }

    @Test
    fun blitGlyphs_unknownGlyphSource_failsFast() {
        try {
            surface(6, 7).render(DrawPlan(listOf(DrawOp.BlitGlyphs("I", Point(0, 0), "other-font", 0xFF000000L))))
            fail("expected IllegalArgumentException for glyph source mismatch")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("glyph source mismatch"))
        }
    }

    @Test
    fun blitGlyphs_spaceRendersNothing_butAdvances() {
        val out = surface(20, 9).render(DrawPlan(listOf(DrawOp.BlitGlyphs("A B", Point(0, 0), "builtin-5x7", 0xFF000000L))))
        // Space renders nothing but still advances 6px: 'B' starts at x=12
        // and its row-0 bar (0x1E) covers x=12..15.
        assertTrue(at(out, 20, 2, 0) == BLACK)
        assertTrue(at(out, 20, 12, 0) == BLACK)
        assertTrue(at(out, 20, 15, 0) == BLACK)
        assertFalse(at(out, 20, 11, 0) == BLACK)
        assertFalse(at(out, 20, 16, 0) == BLACK)
    }

    @Test
    fun blitGlyphs_uncoveredCharacters_advanceNothing() {
        val out = surface(20, 9).render(DrawPlan(listOf(DrawOp.BlitGlyphs("A" + 1.toChar() + "B", Point(0, 0), "builtin-5x7", 0xFF000000L))))
        // A control char (code 1) is uncovered: no pixels, no advance, so
        // 'B' starts immediately at x=6 (its row-0 bar covers x=6..9).
        assertTrue(at(out, 20, 2, 0) == BLACK)
        assertTrue(at(out, 20, 6, 0) == BLACK)
        assertTrue(at(out, 20, 9, 0) == BLACK)
        assertFalse(at(out, 20, 5, 0) == BLACK)
        assertFalse(at(out, 20, 10, 0) == BLACK)
    }

    // ------------------------------------------------------------------
    // Buffer discipline and determinism
    // ------------------------------------------------------------------

    @Test
    fun render_ontoBase_neverMutatesInput() {
        val base = whiteBuffer(4, 4)
        val copy = base.copyOf()
        surface(4, 4).render(base, DrawPlan(listOf(DrawOp.FillRect(Rect(0, 0, 4, 4), 0xFF000000L))))
        assertArrayEquals(copy, base)
    }

    @Test
    fun render_emptyPlan_onFreshBuffer_isAllTransparent() {
        val out = surface(3, 2).render(DrawPlan.EMPTY)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun render_isDeterministic_runTwice() {
        val plan = DrawPlan(
            listOf(
                DrawOp.Stroke(listOf(Point(0, 0), Point(5, 7)), 3, 0xFF123456L),
                DrawOp.FillRect(Rect(1, 1, 4, 4), 0xFF654321L),
                DrawOp.BlendRect(Rect(0, 0, 8, 8), 0xFF102030L, 77),
                DrawOp.MultiplyRect(Rect(2, 2, 5, 5), 0xFF808080L),
                DrawOp.BlitGlyphs("Az09", Point(0, 0), "builtin-5x7", 0xFF000000L),
            ),
        )
        val base = IntArray(10 * 10) { if (it % 3 == 0) WHITE else rgb(255, 100, 150, 200) }
        val first = surface(10, 10).render(base, plan)
        val second = surface(10, 10).render(base, plan)
        assertArrayEquals(first, second)
    }

    @Test
    fun render_rejectsMismatchedBaseSize() {
        try {
            surface(3, 3).render(IntArray(4), DrawPlan.EMPTY)
            fail("expected IllegalArgumentException for buffer size mismatch")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size mismatch"))
        }
    }
}
