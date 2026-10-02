package org.payswap.camscan.tools.annotation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect
import org.payswap.camscan.tools.signature.SignatureStroke

// AnnotationApplier tests (CAMSCAN-PROD-011 section 6.6): rendering a
// store plan onto page buffers - highlight multiply math on the page,
// text-note legibility background, z-order effects, non-mutation.

class AnnotationRenderTest {

    private val W = 40
    private val H = 30
    private val WHITE = 0xFFFFFFFF.toInt()

    private fun whitePage(): IntArray = IntArray(W * H) { WHITE }

    private fun at(page: IntArray, x: Int, y: Int): Int = page[y * W + x]

    private fun rgb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun highlight_overWhitePage_yieldsYellowPixels() {
        val store = AnnotationStore()
        store.add("doc", Annotation.Highlight(Rect(10, 10, 5, 5), 0xFFFFFF00L, "h", 0))
        val out = AnnotationApplier.apply(whitePage(), W, H, store, "doc")
        assertEquals(rgb(255, 255, 255, 0), at(out, 12, 12))
        assertEquals(WHITE, at(out, 9, 9))
        assertEquals(WHITE, at(out, 15, 15))
    }

    @Test
    fun highlight_overGrayPage_darkensPerChannel() {
        val gray = IntArray(W * H) { rgb(255, 128, 128, 128) }
        val store = AnnotationStore()
        store.add("doc", Annotation.Highlight(Rect(0, 0, W, H), 0xFFFFFF00L, "h", 0))
        val out = AnnotationApplier.apply(gray, W, H, store, "doc")
        assertEquals(rgb(255, 128, 128, 0), at(out, 20, 15))
    }

    @Test
    fun highlight_appliesOnlyInsideRect() {
        val store = AnnotationStore()
        store.add("doc", Annotation.Highlight(Rect(5, 5, 10, 10), 0xFFFFFF00L, "h", 0))
        val out = AnnotationApplier.apply(whitePage(), W, H, store, "doc")
        assertEquals(rgb(255, 255, 255, 0), at(out, 5, 5))
        assertEquals(rgb(255, 255, 255, 0), at(out, 14, 14))
        assertEquals(WHITE, at(out, 4, 5))
        assertEquals(WHITE, at(out, 15, 5))
        assertEquals(WHITE, at(out, 5, 4))
        assertEquals(WHITE, at(out, 5, 15))
    }

    @Test
    fun textNote_rendersWhiteBackgroundWithColoredGlyphs() {
        val store = AnnotationStore()
        store.add(
            "doc",
            Annotation.TextNote("I", Point(10, 10), 0xFF000000L, "t", 0),
        )
        // Paint the page dark so the background effect is visible.
        val dark = IntArray(W * H) { rgb(255, 10, 20, 30) }
        val out = AnnotationApplier.apply(dark, W, H, store, "doc")
        // Background rect = (9,9,8,11): all white.
        assertEquals(WHITE, at(out, 9, 9))
        assertEquals(WHITE, at(out, 16, 19))
        // Glyph 'I' row 0 covers columns 1..3 at x 11..13, row 10.
        assertEquals(0xFF000000.toInt(), at(out, 11, 10))
        assertEquals(0xFF000000.toInt(), at(out, 13, 10))
        assertEquals(WHITE, at(out, 10, 10))
        assertEquals(WHITE, at(out, 14, 10))
        // Outside the background the page stays dark.
        assertEquals(rgb(255, 10, 20, 30), at(out, 20, 20))
    }

    @Test
    fun zIndexOrder_laterAnnotationsDrawOnTop() {
        val store = AnnotationStore()
        store.add("doc", Annotation.Highlight(Rect(0, 0, W, H), 0xFFFFFF00L, "hl", 0))
        store.add(
            "doc",
            Annotation.TextNote("I", Point(0, 0), 0xFF000000L, "note", 1),
        )
        val out = AnnotationApplier.apply(whitePage(), W, H, store, "doc")
        // Under the text-note background the pixel is pure white again
        // (the opaque background covers the earlier yellow highlight);
        // outside it the highlight yellow remains.
        assertEquals(WHITE, at(out, 1, 1))
        assertEquals(rgb(255, 255, 255, 0), at(out, 30, 20))
    }

    @Test
    fun ink_rendersStrokeOnPage() {
        val store = AnnotationStore()
        store.add(
            "doc",
            Annotation.Ink(
                listOf(SignatureStroke(listOf(Point(0, 0), Point(10, 0)), 1, 0xFF000000L)),
                "ink",
                0,
            ),
        )
        val out = AnnotationApplier.apply(whitePage(), W, H, store, "doc")
        assertEquals(0xFF000000.toInt(), at(out, 5, 0))
        assertEquals(WHITE, at(out, 5, 1))
    }

    @Test
    fun apply_neverMutatesInputPage() {
        val page = whitePage()
        val copy = page.copyOf()
        val store = AnnotationStore()
        store.add("doc", Annotation.Highlight(Rect(0, 0, W, H), 0xFFFFFF00L, "h", 0))
        AnnotationApplier.apply(page, W, H, store, "doc")
        assertArrayEquals(copy, page)
    }

    @Test
    fun applyPlan_isPure() {
        val page = whitePage()
        val copy = page.copyOf()
        val plan = AnnotationApplier.apply(page, W, H, AnnotationStore(), "doc")
        assertArrayEquals(copy, page)
        assertArrayEquals(page, plan)
    }

    @Test
    fun apply_isDeterministic() {
        val store = AnnotationStore()
        store.add("doc", Annotation.Highlight(Rect(2, 2, 8, 8), 0xFFFFFF00L, "h", 0))
        store.add("doc", Annotation.TextNote("Az", Point(4, 4), 0xFF000000L, "t", 1))
        val first = AnnotationApplier.apply(whitePage(), W, H, store, "doc")
        val second = AnnotationApplier.apply(whitePage(), W, H, store, "doc")
        assertArrayEquals(first, second)
    }

    @Test
    fun apply_rejectsMismatchedPageSize() {
        try {
            AnnotationApplier.apply(IntArray(3), W, H, AnnotationStore(), "doc")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size mismatch"))
        }
    }
}
