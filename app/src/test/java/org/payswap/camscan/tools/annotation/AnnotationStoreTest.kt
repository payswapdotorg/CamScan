package org.payswap.camscan.tools.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.render.DrawOp
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect
import org.payswap.camscan.tools.render.TextGlyphSource
import org.payswap.camscan.tools.signature.SignatureStroke

// AnnotationStore tests (CAMSCAN-PROD-011 section 6.6): total deterministic
// z ordering, per-document isolation, plan merging, and per-type op
// emission (Ink strokes, Highlight multiply, TextNote background geometry).

class AnnotationStoreTest {

    private val font = TextGlyphSource()

    private fun ink(id: String, vararg points: Point) =
        Annotation.Ink(listOf(SignatureStroke(points.toList(), 2, 0xFF000000L)), id, -1)

    private fun highlight(id: String) =
        Annotation.Highlight(Rect(0, 0, 4, 2), 0xFFFFFF00L, id, -1)

    private fun textNote(id: String, text: String) =
        Annotation.TextNote(text, Point(3, 4), 0xFF000000L, id, -1)

    @Test
    fun add_assignsDenseZIndicesStartingAtZero() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        store.add("doc", highlight("b"))
        store.add("doc", textNote("c", "Hi"))
        val z = store.annotationsFor("doc").map { it.zIndex }
        assertEquals(listOf(0, 1, 2), z)
    }

    @Test
    fun add_ignoresCallerSuppliedZIndex() {
        val store = AnnotationStore()
        val stored = store.add("doc", ink("a", Point(0, 0)))
        assertEquals(0, stored.zIndex)
    }

    @Test
    fun annotationsFor_returnsZOrder() {
        val store = AnnotationStore()
        store.add("doc", highlight("h"))
        store.add("doc", ink("i", Point(1, 1)))
        assertEquals(listOf("h", "i"), store.annotationsFor("doc").map { it.annotationId })
    }

    @Test
    fun documents_areIsolated() {
        val store = AnnotationStore()
        store.add("doc1", ink("a", Point(0, 0)))
        store.add("doc2", ink("b", Point(1, 1)))
        assertEquals(listOf("a"), store.annotationsFor("doc1").map { it.annotationId })
        assertEquals(listOf("b"), store.annotationsFor("doc2").map { it.annotationId })
        assertEquals(2, store.documentCount)
    }

    @Test
    fun remove_reindexesZTotally() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        store.add("doc", highlight("b"))
        store.add("doc", textNote("c", "x"))
        assertTrue(store.remove("doc", "b"))
        val remaining = store.annotationsFor("doc")
        assertEquals(listOf("a", "c"), remaining.map { it.annotationId })
        assertEquals(listOf(0, 1), remaining.map { it.zIndex })
    }

    @Test
    fun remove_unknownIdOrDocument_returnsFalse() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        assertFalse(store.remove("doc", "zz"))
        assertFalse(store.remove("other", "a"))
    }

    @Test
    fun remove_lastAnnotation_clearsDocument() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        store.remove("doc", "a")
        assertEquals(0, store.documentCount)
        assertTrue(store.annotationsFor("doc").isEmpty())
    }

    @Test
    fun reorder_movesToFrontAndReindexes() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        store.add("doc", highlight("b"))
        store.add("doc", textNote("c", "x"))
        val moved = store.reorder("doc", "c", 0)
        assertEquals("c", moved!!.annotationId)
        val order = store.annotationsFor("doc")
        assertEquals(listOf("c", "a", "b"), order.map { it.annotationId })
        assertEquals(listOf(0, 1, 2), order.map { it.zIndex })
    }

    @Test
    fun reorder_clampsOutOfRangeIndex() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        store.add("doc", highlight("b"))
        store.reorder("doc", "a", 99)
        assertEquals(listOf("b", "a"), store.annotationsFor("doc").map { it.annotationId })
        assertEquals(listOf(0, 1), store.annotationsFor("doc").map { it.zIndex })
    }

    @Test
    fun reorder_unknownId_returnsNull() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        assertNull(store.reorder("doc", "zz", 0))
        assertNull(store.reorder("other", "a", 0))
    }

    @Test
    fun plan_emptyDocument_isEmpty() {
        assertTrue(AnnotationStore().plan("doc").isEmpty)
    }

    @Test
    fun plan_mergesOpsInZOrder() {
        val store = AnnotationStore()
        store.add("doc", textNote("t", "A"))
        store.add("doc", highlight("h"))
        store.add("doc", ink("i", Point(0, 0), Point(3, 3)))
        val ops = store.plan("doc", font).ops
        // TextNote = FillRect + BlitGlyphs, Highlight = MultiplyRect,
        // Ink (one stroke) = one Stroke -> four ops in exact z order.
        assertEquals(4, ops.size)
        assertTrue(ops[0] is DrawOp.FillRect)
        assertTrue(ops[1] is DrawOp.BlitGlyphs)
        assertTrue(ops[2] is DrawOp.MultiplyRect)
        assertTrue(ops[3] is DrawOp.Stroke)
    }

    @Test
    fun inkOps_carryStrokeGeometry() {
        val store = AnnotationStore()
        store.add("doc", ink("i", Point(1, 2), Point(5, 6)))
        val ops = store.plan("doc", font).ops
        val stroke = ops[0] as DrawOp.Stroke
        assertEquals(listOf(Point(1, 2), Point(5, 6)), stroke.points)
        assertEquals(2, stroke.widthPx)
        assertEquals(0xFF000000L, stroke.colorArgb)
    }

    @Test
    fun highlightOps_useMultiplyRectWithSpecColor() {
        val store = AnnotationStore()
        store.add("doc", highlight("h"))
        val ops = store.plan("doc", font).ops
        val multiply = ops[0] as DrawOp.MultiplyRect
        assertEquals(Rect(0, 0, 4, 2), multiply.rect)
        assertEquals(0xFFFFFF00L, multiply.colorArgb)
    }

    @Test
    fun textNoteOps_backgroundIsMeasuredPlusOnePadding() {
        val store = AnnotationStore()
        store.add("doc", textNote("t", "AB"))
        val ops = store.plan("doc", font).ops
        val background = ops[0] as DrawOp.FillRect
        // measure("AB") = 12x9; background = anchor-1 padded by 1 on each side.
        assertEquals(Rect(2, 3, 14, 11), background.rect)
        assertEquals(Annotation.BACKGROUND_COLOR, background.colorArgb)
        val glyphs = ops[1] as DrawOp.BlitGlyphs
        assertEquals("AB", glyphs.text)
        assertEquals(Point(3, 4), glyphs.origin)
        assertEquals("builtin-5x7", glyphs.glyphSourceId)
        assertEquals(0xFF000000L, glyphs.colorArgb)
    }

    @Test
    fun textNoteOps_emptyTextHasMinimalBackground() {
        val store = AnnotationStore()
        store.add("doc", textNote("t", ""))
        val ops = store.plan("doc", font).ops
        val background = ops[0] as DrawOp.FillRect
        assertEquals(Rect(2, 3, 2, 2), background.rect)
    }

    @Test
    fun annotationsFor_returnsDefensiveCopy() {
        val store = AnnotationStore()
        store.add("doc", ink("a", Point(0, 0)))
        val snapshot = store.annotationsFor("doc")
        store.remove("doc", "a")
        assertEquals(1, snapshot.size)
        assertEquals(0, store.annotationsFor("doc").size)
    }

    @Test
    fun multiStrokeInk_emitsOneStrokeOpPerStroke() {
        val multi = Annotation.Ink(
            listOf(
                SignatureStroke(listOf(Point(0, 0)), 1, 0xFF000000L),
                SignatureStroke(listOf(Point(5, 5)), 2, 0xFF000000L),
            ),
            "m",
            -1,
        )
        val store = AnnotationStore()
        store.add("doc", multi)
        assertEquals(2, store.plan("doc", font).ops.size)
    }

    @Test
    fun annotationValueSemantics() {
        assertEquals(ink("a", Point(0, 0)), ink("a", Point(0, 0)))
        assertEquals(highlight("h"), highlight("h"))
        assertEquals(textNote("t", "x"), textNote("t", "x"))
        assertFalse(ink("a", Point(0, 0)) == highlight("a"))
    }
}
