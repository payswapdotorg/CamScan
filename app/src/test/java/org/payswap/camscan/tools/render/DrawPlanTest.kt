package org.payswap.camscan.tools.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// DrawPlan tests (CAMSCAN-PROD-011 section 6.6): value semantics and the
// stable serialized form used for hash/equality checks.

class DrawPlanTest {

    private fun strokeOp(): DrawOp.Stroke =
        DrawOp.Stroke(listOf(Point(0, 0), Point(3, 4)), 2, 0xFF000000L)

    @Test
    fun emptyPlan_hasNoOps() {
        assertEquals(0, DrawPlan.EMPTY.ops.size)
        assertTrue(DrawPlan.EMPTY.isEmpty)
    }

    @Test
    fun equalPlans_areEqual() {
        val a = DrawPlan(listOf(strokeOp()))
        val b = DrawPlan(listOf(strokeOp()))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun differentOps_areNotEqual() {
        val a = DrawPlan(listOf(strokeOp()))
        val b = DrawPlan(listOf(DrawOp.Stroke(listOf(Point(1, 1)), 2, 0xFF000000L)))
        assertNotEquals(a, b)
    }

    @Test
    fun opOrder_mattersForEquality() {
        val fill = DrawOp.FillRect(Rect(0, 0, 2, 2), 0xFFFFFFFFL)
        val stroke = strokeOp()
        assertNotEquals(DrawPlan(listOf(fill, stroke)), DrawPlan(listOf(stroke, fill)))
    }

    @Test
    fun opsList_isDefensivelyCopied() {
        val mutable = ArrayList<DrawOp>()
        mutable.add(strokeOp())
        val plan = DrawPlan(mutable)
        mutable.clear()
        assertEquals(1, plan.ops.size)
    }

    @Test
    fun serializedForm_isStableAcrossEqualPlans() {
        val a = DrawPlan(listOf(strokeOp(), DrawOp.FillRect(Rect(1, 2, 3, 4), 0xFF00FF00L)))
        val b = DrawPlan(listOf(strokeOp(), DrawOp.FillRect(Rect(1, 2, 3, 4), 0xFF00FF00L)))
        assertEquals(a.serialized(), b.serialized())
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun serializedForm_joinsOpsWithNewlines() {
        val plan = DrawPlan(listOf(strokeOp(), DrawOp.FillRect(Rect(1, 2, 3, 4), 0xFF00FF00L)))
        val lines = plan.serialized().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("Stroke["))
        assertTrue(lines[1].startsWith("FillRect["))
    }

    @Test
    fun strokeToString_hasDocumentedFormat() {
        val text = strokeOp().toString()
        assertEquals("Stroke[points=(0,0) (3,4);width=2;color=ff000000]", text)
    }

    @Test
    fun fillRectToString_hasDocumentedFormat() {
        val text = DrawOp.FillRect(Rect(1, 2, 3, 4), 0xFF00FF00L).toString()
        assertEquals("FillRect[rect=[1,2 3x4];color=ff00ff00]", text)
    }

    @Test
    fun blitGlyphsToString_hasDocumentedFormat() {
        val text = DrawOp.BlitGlyphs("Hi", Point(2, 3), "builtin-5x7", 0xFF112233L).toString()
        assertEquals("BlitGlyphs[text=Hi;origin=(2,3);source=builtin-5x7;color=ff112233]", text)
    }

    @Test
    fun blendRectToString_hasDocumentedFormat() {
        val text = DrawOp.BlendRect(Rect(0, 0, 5, 5), 0x80FF0000L, 128).toString()
        assertEquals("BlendRect[rect=[0,0 5x5];color=80ff0000;alpha=128]", text)
    }

    @Test
    fun multiplyRectToString_hasDocumentedFormat() {
        val text = DrawOp.MultiplyRect(Rect(0, 0, 5, 5), 0xFFFFFF00L).toString()
        assertEquals("MultiplyRect[rect=[0,0 5x5];color=ffffff00]", text)
    }

    @Test
    fun plus_returnsNewPlanAndLeavesOriginal() {
        val original = DrawPlan(listOf(strokeOp()))
        val grown = original.plus(DrawOp.FillRect(Rect(0, 0, 1, 1), 0xFF000000L))
        assertEquals(1, original.ops.size)
        assertEquals(2, grown.ops.size)
    }

    @Test
    fun valueEquality_perOpType() {
        assertEquals(DrawOp.Stroke(listOf(Point(1, 2)), 1, 5L), DrawOp.Stroke(listOf(Point(1, 2)), 1, 5L))
        assertEquals(DrawOp.BlendRect(Rect(0, 0, 1, 1), 5L, 9), DrawOp.BlendRect(Rect(0, 0, 1, 1), 5L, 9))
        assertEquals(DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 5L), DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 5L))
        assertEquals(DrawOp.BlitGlyphs("a", Point(0, 0), "s", 1L), DrawOp.BlitGlyphs("a", Point(0, 0), "s", 1L))
        assertNotEquals(DrawOp.FillRect(Rect(0, 0, 1, 1), 5L), DrawOp.MultiplyRect(Rect(0, 0, 1, 1), 5L))
    }

    @Test
    fun argbHelpers_decodeChannels() {
        assertEquals(0x80, Argb.alpha(0x80112233L))
        assertEquals(0x11, Argb.red(0x80112233L))
        assertEquals(0x22, Argb.green(0x80112233L))
        assertEquals(0x33, Argb.blue(0x80112233L))
        assertEquals(0x80112233L, Argb.argb(0x80, 0x11, 0x22, 0x33))
    }

    @Test
    fun argbToHex_isLowercaseEightDigits() {
        assertEquals("ff000000", Argb.toHex(0xFF000000L))
        assertEquals("00ffffff", Argb.toHex(0x00FFFFFFL))
        assertEquals("00000000", Argb.toHex(0L))
    }
}
