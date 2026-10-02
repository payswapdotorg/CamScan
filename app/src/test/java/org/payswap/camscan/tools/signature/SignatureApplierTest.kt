package org.payswap.camscan.tools.signature

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect

// SignatureApplier tests (CAMSCAN-PROD-011 section 6.6): placement
// scale/anchor math on a 100x60 white page, non-mutation, clipping, and
// the degenerate-input rules.
//
// Derivation of the expected geometry (documented in the applier): a
// horizontal 2-point stroke (0,0)->(9,0) has bbox (0,0,10,1); with
// widthFraction 0.5 on a 100px page the target width is 50, scale 5.0,
// scaled points (0,0)->(45,0), scaled ink box (0,0,46,1), scaled stroke
// width 5 (brush rows y-2..y+2).

class SignatureApplierTest {

    private val PAGE_W = 100
    private val PAGE_H = 60
    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()

    private fun whitePage(): IntArray = IntArray(PAGE_W * PAGE_H) { WHITE }

    private fun horizontalSketch(): SignatureSketch =
        SignatureSketch(listOf(SignatureStroke(listOf(Point(0, 0), Point(9, 0)), 1, 0xFF000000L)))

    private fun at(page: IntArray, x: Int, y: Int): Int = page[y * PAGE_W + x]

    @Test
    fun topLeftAnchor_placesScaledStrokeAtMargin() {
        val placement = SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, 2)
        val out = SignatureApplier.apply(whitePage(), PAGE_W, PAGE_H, horizontalSketch(), placement)
        // Scaled stroke centers run x=2..47 at y=2 with brush width 5
        // (half 2): painted x 0..49, y 0..4.
        assertEquals(BLACK, at(out, 2, 2))
        assertEquals(BLACK, at(out, 47, 2))
        assertEquals(BLACK, at(out, 2, 0))
        assertEquals(BLACK, at(out, 47, 4))
        assertEquals(BLACK, at(out, 48, 2))
        assertEquals(BLACK, at(out, 49, 2))
        assertEquals(WHITE, at(out, 50, 2))
        assertEquals(WHITE, at(out, 25, 20))
    }

    @Test
    fun topRightAnchor_placesAtRightMargin() {
        val placement = SignaturePlacement(AnchorCorner.TOP_RIGHT, 0.5f, 2)
        val out = SignatureApplier.apply(whitePage(), PAGE_W, PAGE_H, horizontalSketch(), placement)
        // destX = 100 - 2 - 46 = 52; centers 52..97; brush x 50..99, y 0..4.
        assertEquals(BLACK, at(out, 52, 2))
        assertEquals(BLACK, at(out, 97, 2))
        assertEquals(BLACK, at(out, 98, 2))
        assertEquals(WHITE, at(out, 99, 5))
        assertEquals(WHITE, at(out, 49, 2))
        assertEquals(WHITE, at(out, 60, 10))
    }

    @Test
    fun bottomLeftAnchor_placesAtBottomMargin() {
        val placement = SignaturePlacement(AnchorCorner.BOTTOM_LEFT, 0.5f, 3)
        val out = SignatureApplier.apply(whitePage(), PAGE_W, PAGE_H, horizontalSketch(), placement)
        // destY = 60 - 3 - 1 = 56; brush rows 54..58.
        assertEquals(BLACK, at(out, 3, 56))
        assertEquals(BLACK, at(out, 3, 54))
        assertEquals(BLACK, at(out, 3, 58))
        assertEquals(WHITE, at(out, 3, 59))
    }

    @Test
    fun bottomRightAnchor_placesAtCornerMargin() {
        val placement = SignaturePlacement(AnchorCorner.BOTTOM_RIGHT, 0.5f, 3)
        val out = SignatureApplier.apply(whitePage(), PAGE_W, PAGE_H, horizontalSketch(), placement)
        // destX = 100 - 3 - 46 = 51, destY = 56; centers (51,56)..(96,56);
        // brush x 49..98, y 54..58.
        assertEquals(BLACK, at(out, 51, 56))
        assertEquals(BLACK, at(out, 96, 56))
        assertEquals(BLACK, at(out, 98, 56))
        assertEquals(WHITE, at(out, 99, 56))
        assertEquals(WHITE, at(out, 51, 59))
    }

    @Test
    fun widthFraction_scalesTheInkWidth() {
        val placement = SignaturePlacement(AnchorCorner.TOP_LEFT, 0.25f, 0)
        val out = SignatureApplier.apply(whitePage(), PAGE_W, PAGE_H, horizontalSketch(), placement)
        // Target width 25, scale 2.5: scaled points (0,0)->(Math.round(22.5)
        // = 23,0), stroke width max(1, round(2.5)) = 3 (half 1): painted
        // x 0..24 (clipped from -1), y 0..1.
        assertEquals(BLACK, at(out, 0, 0))
        assertEquals(BLACK, at(out, 23, 0))
        assertEquals(BLACK, at(out, 24, 0))
        assertEquals(WHITE, at(out, 25, 0))
        assertEquals(BLACK, at(out, 12, 1))
        assertEquals(WHITE, at(out, 12, 2))
    }

    @Test
    fun strokeWidth_scalesWithMinimumOne() {
        val placement = SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, 2)
        val out = SignatureApplier.apply(whitePage(), PAGE_W, PAGE_H, horizontalSketch(), placement)
        // Brush width 5: rows 0..4 around y=2 all painted along x=2..47.
        assertEquals(BLACK, at(out, 25, 0))
        assertEquals(BLACK, at(out, 25, 4))
        assertEquals(WHITE, at(out, 25, 5))
    }

    @Test
    fun apply_neverMutatesInputPage() {
        val page = whitePage()
        val copy = page.copyOf()
        SignatureApplier.apply(page, PAGE_W, PAGE_H, horizontalSketch(), SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, 2))
        assertArrayEquals(copy, page)
    }

    @Test
    fun emptySketch_returnsIdenticalCopy() {
        val page = whitePage()
        val out = SignatureApplier.apply(page, PAGE_W, PAGE_H, SignatureSketch(emptyList()), SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, 2))
        assertArrayEquals(page, out)
    }

    @Test
    fun degenerateWidthSketch_scalesFromOne() {
        // A single point sketch has bbox width 1: scale = target / max(1,1).
        val sketch = SignatureSketch(listOf(SignatureStroke(listOf(Point(3, 4)), 1, 0xFF000000L)))
        val placement = SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, 5)
        val out = SignatureApplier.apply(whitePage(), PAGE_W, PAGE_H, sketch, placement)
        // Scale 50: point (3,4) -> (150, 200) -> scaled box is that single
        // point (1x1) at (150,200); dest (5,5) clamped... box width 1 so
        // destX = 5, destY = 5; the dot lands at (5,5) with brush width 50
        // (half 25, span -25..24) -> clamped square x 0..29, y 0..29.
        assertEquals(BLACK, at(out, 5, 5))
        assertEquals(BLACK, at(out, 0, 0))
        assertEquals(WHITE, at(out, 30, 5))
        assertEquals(WHITE, at(out, 5, 30))
    }

    @Test
    fun apply_rejectsMismatchedPageSize() {
        try {
            SignatureApplier.apply(
                IntArray(10),
                PAGE_W,
                PAGE_H,
                horizontalSketch(),
                SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, 2),
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size mismatch"))
        }
    }

    @Test
    fun placement_rejectsInvalidFractions() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SignaturePlacement(AnchorCorner.TOP_LEFT, 0.0f, 2)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SignaturePlacement(AnchorCorner.TOP_LEFT, 1.5f, 2)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, -1)
        }
    }

    @Test
    fun placement_valueSemantics() {
        val a = SignaturePlacement(AnchorCorner.BOTTOM_RIGHT, 0.5f, 3)
        val b = SignaturePlacement(AnchorCorner.BOTTOM_RIGHT, 0.5f, 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("BOTTOM_RIGHT"))
    }

    @Test
    fun sketchBoundingBox_matchesInclusiveGeometry() {
        assertEquals(Rect(0, 0, 10, 1), horizontalSketch().boundingBox)
    }
}
