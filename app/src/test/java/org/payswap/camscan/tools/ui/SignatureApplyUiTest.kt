package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.signature.AnchorCorner
import org.payswap.camscan.tools.signature.SignatureApplier
import org.payswap.camscan.tools.signature.SignaturePlacement
import org.payswap.camscan.tools.signature.SignatureSketch
import org.payswap.camscan.tools.signature.SignatureStroke
import org.payswap.camscan.tools.render.Point

// SignatureApplyUiTest (CAMSCAN-VERIFY-001): drag resolution into the
// frozen SignatureApplier placement vocabulary, size ladder bounds, and the
// previewRect/applier destination mirror relation.

class SignatureApplyUiTest {

    @Test
    fun sizeLadder_stepsClampAtBothEnds() {
        val draft = SignatureApplyUi()
        assertEquals(SignatureApplyUi.SIZE_FRACTIONS[2], draft.fraction(), 0.0001f)
        // Two successful steps from index 2 to the top of the 5-step ladder.
        assertTrue(draft.stepSizeUp())
        assertTrue(draft.stepSizeUp())
        assertFalse(draft.stepSizeUp())
        assertEquals(SignatureApplyUi.SIZE_FRACTIONS[4], draft.fraction(), 0.0001f)
        while (draft.stepSizeDown()) {
            // walk to the bottom
        }
        assertFalse(draft.stepSizeDown())
        assertEquals(SignatureApplyUi.SIZE_FRACTIONS[0], draft.fraction(), 0.0001f)
        assertTrue(draft.fraction() > 0f && draft.fraction() <= 1f)
    }

    @Test
    fun resolvePlacement_bottomRightDrop_picksCornerAndNonNegativeMargin() {
        val placement = SignatureApplyUi.resolvePlacement(
            dropX = 90, dropY = 180, pageWidth = 100, pageHeight = 200,
            inkBoxWidth = 20, inkBoxHeight = 10,
        )
        assertEquals(AnchorCorner.BOTTOM_RIGHT, placement.anchor)
        assertTrue(placement.marginPx >= 0)
    }

    @Test
    fun resolvePlacement_topLeftDrop_picksTopLeft() {
        val placement = SignatureApplyUi.resolvePlacement(
            dropX = 8, dropY = 12, pageWidth = 100, pageHeight = 200,
            inkBoxWidth = 20, inkBoxHeight = 10,
        )
        assertEquals(AnchorCorner.TOP_LEFT, placement.anchor)
    }

    @Test
    fun resolvedPlacement_isTheLeastSquaresReachableBox() {
        // Reachable boxes for BOTTOM_RIGHT slide along the diagonal; the
        // optimal margin minimizes the distance to the drop-centered box.
        val placement = SignatureApplyUi.resolvePlacement(
            dropX = 50, dropY = 100, pageWidth = 100, pageHeight = 200,
            inkBoxWidth = 20, inkBoxHeight = 10,
        )
        assertEquals(AnchorCorner.BOTTOM_RIGHT, placement.anchor)
        // (100-20-40 + 200-10-95)/2 = 67.5 -> round half up = 68.
        assertEquals(68, placement.marginPx)
    }

    @Test
    fun previewRect_staysInsideThePage_forAllCorners() {
        val corners = listOf(
            AnchorCorner.TOP_LEFT, AnchorCorner.TOP_RIGHT,
            AnchorCorner.BOTTOM_LEFT, AnchorCorner.BOTTOM_RIGHT,
        )
        for (corner in corners) {
            for (margin in listOf(0, 7, 40, 500)) {
                val rect = SignatureApplyUi.previewRect(
                    SignaturePlacement(corner, 0.5f, margin), 100, 200, 20, 10,
                )
                assertTrue(rect.x >= 0)
                assertTrue(rect.y >= 0)
                assertTrue(rect.x + rect.width <= 100)
                assertTrue(rect.y + rect.height <= 200)
            }
        }
    }

    @Test
    fun previewRect_mirrorsApplierDestination() {
        // Engine agreement: the ink painted by SignatureApplier for a
        // placement lands inside previewRect inflated by the scaled brush.
        val pageW = 60
        val pageH = 60
        val page = IntArray(pageW * pageH) { 0xFFFFFFFF.toInt() }
        val sketch = SignatureSketch(
            listOf(SignatureStroke(listOf(Point(10, 10), Point(20, 20)), 1, 0xFF000000L)),
        )
        val inkBox = SignatureApplyUi.inkBoxSize(11, 11, pageW, 0.5f)!!
        val placement = SignaturePlacement(AnchorCorner.TOP_LEFT, 0.5f, 5)
        val rendered = SignatureApplier.apply(page, pageW, pageH, sketch, placement)
        val rect = SignatureApplyUi.previewRect(placement, pageW, pageH, inkBox.first, inkBox.second)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until pageH) {
            for (x in 0 until pageW) {
                if (rendered[y * pageW + x] != 0xFFFFFFFF.toInt()) {
                    minX = Math.min(minX, x)
                    minY = Math.min(minY, y)
                    maxX = Math.max(maxX, x)
                    maxY = Math.max(maxY, y)
                }
            }
        }
        assertNotNull(inkBox)
        val brush = 3
        assertTrue("ink minX=" + minX, minX >= rect.x - brush)
        assertTrue("ink minY=" + minY, minY >= rect.y - brush)
        assertTrue("ink maxX=" + maxX, maxX <= rect.x + rect.width + brush)
        assertTrue("ink maxY=" + maxY, maxY <= rect.y + rect.height + brush)
    }

    @Test
    fun inkBoxSize_scalesProportionally_andRejectsDegenerateBoxes() {
        val box = SignatureApplyUi.inkBoxSize(10, 20, 100, 0.5f)
        assertNotNull(box)
        assertEquals(50, box!!.first)
        assertEquals(100, box.second)
        assertNull(SignatureApplyUi.inkBoxSize(0, 20, 100, 0.5f))
        assertNull(SignatureApplyUi.inkBoxSize(10, 0, 100, 0.5f))
    }

    @Test
    fun placement_withoutDrag_usesDefaultAnchorAndCappedMargin() {
        val draft = SignatureApplyUi()
        val placement = draft.placement(100, 200, 20, 10)
        assertEquals(AnchorCorner.BOTTOM_RIGHT, placement.anchor)
        assertEquals(draft.fraction(), placement.widthFraction, 0.0001f)
        // 5% of 100 = 5, within the reachable range min(80, 190).
        assertEquals(5, placement.marginPx)
    }

    @Test
    fun placement_withDrag_resolvesFromTheRecordedDrop() {
        val draft = SignatureApplyUi()
        draft.dragTo(8, 12)
        val placement = draft.placement(100, 200, 20, 10)
        assertEquals(AnchorCorner.TOP_LEFT, placement.anchor)
        assertNotNull(draft.lastDrop())
        assertEquals(Point(8, 12), draft.lastDrop())
    }
}
