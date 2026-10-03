package org.payswap.camscan.tools.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-012 §7 — spread-split coverage: axis-aligned exact split,
// rotated split, the midpoint-line construction from both edge pairs,
// every degenerate rejection reason, and determinism.

class SpreadSplitPlannerTest {

    private fun quad(
        tl: Pair<Double, Double>,
        tr: Pair<Double, Double>,
        br: Pair<Double, Double>,
        bl: Pair<Double, Double>,
    ) = ModeQuad(
        ModePoint(tl.first, tl.second),
        ModePoint(tr.first, tr.second),
        ModePoint(br.first, br.second),
        ModePoint(bl.first, bl.second),
    )

    private fun squareSpread() = quad(
        0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0,
    )

    // ------------------------------------------------ axis-aligned splits

    @Test
    fun squareSpreadSplitsIntoExactHalves() {
        val r = SpreadSplitPlanner.planSplit(squareSpread())
        val split = r as SplitResult.Split
        assertEquals(
            ModeQuad(
                ModePoint(0.0, 0.0), ModePoint(5.0, 0.0),
                ModePoint(5.0, 10.0), ModePoint(0.0, 10.0),
            ),
            split.leftQuad,
        )
        assertEquals(
            ModeQuad(
                ModePoint(5.0, 0.0), ModePoint(10.0, 0.0),
                ModePoint(10.0, 10.0), ModePoint(5.0, 10.0),
            ),
            split.rightQuad,
        )
    }

    @Test
    fun rectangleSpreadSplitsAlongTheVerticalMidline() {
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 12.0 to 0.0, 12.0 to 8.0, 0.0 to 8.0)
        )
        val split = r as SplitResult.Split
        assertEquals(
            ModeQuad(
                ModePoint(0.0, 0.0), ModePoint(6.0, 0.0),
                ModePoint(6.0, 8.0), ModePoint(0.0, 8.0),
            ),
            split.leftQuad,
        )
        assertEquals(
            ModeQuad(
                ModePoint(6.0, 0.0), ModePoint(12.0, 0.0),
                ModePoint(12.0, 8.0), ModePoint(6.0, 8.0),
            ),
            split.rightQuad,
        )
    }

    @Test
    fun a5SpreadSplitsIntoTwoPerFaceQuads() {
        // Full spread 297 x 210 mm at 1 px per mm: per-face 148.5 wide.
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 297.0 to 0.0, 297.0 to 210.0, 0.0 to 210.0)
        )
        val split = r as SplitResult.Split
        assertEquals(148.5, split.leftQuad.topRight.x, 0.0)
        assertEquals(148.5, split.leftQuad.bottomRight.x, 0.0)
        assertEquals(148.5, split.rightQuad.topLeft.x, 0.0)
        assertEquals(148.5, split.rightQuad.bottomLeft.x, 0.0)
        assertEquals(210.0, split.leftQuad.bottomRight.y, 0.0)
        assertEquals(0.0, split.leftQuad.topLeft.y, 0.0)
    }

    @Test
    fun translatedSpreadSplitsAroundItsOwnMidline() {
        val r = SpreadSplitPlanner.planSplit(
            quad(100.0 to 50.0, 300.0 to 50.0, 300.0 to 250.0, 100.0 to 250.0)
        )
        val split = r as SplitResult.Split
        assertEquals(200.0, split.leftQuad.topRight.x, 0.0)
        assertEquals(200.0, split.rightQuad.topLeft.x, 0.0)
        assertEquals(50.0, split.leftQuad.topLeft.y, 0.0)
    }

    // ------------------------------------------------ rotated split

    @Test
    fun rotatedSquareSpreadSplitsThroughTheRotatedMidline() {
        // Square rotated: (0,0), (8,2), (10,10), (2,8). Midline from the
        // top-edge midpoint (4,1) to the bottom-edge midpoint (6,9).
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 8.0 to 2.0, 10.0 to 10.0, 2.0 to 8.0)
        )
        val split = r as SplitResult.Split
        assertEquals(
            ModeQuad(
                ModePoint(0.0, 0.0), ModePoint(4.0, 1.0),
                ModePoint(6.0, 9.0), ModePoint(2.0, 8.0),
            ),
            split.leftQuad,
        )
        assertEquals(
            ModeQuad(
                ModePoint(4.0, 1.0), ModePoint(8.0, 2.0),
                ModePoint(10.0, 10.0), ModePoint(6.0, 9.0),
            ),
            split.rightQuad,
        )
    }

    @Test
    fun slightlyKeystonedSpreadStillSplitsCleanly() {
        // Top edge slightly wider than the bottom: the midline endpoints
        // are the two edge midpoints, so the split follows the keystoning.
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 12.0 to 0.0, 11.0 to 8.0, 1.0 to 8.0)
        )
        val split = r as SplitResult.Split
        assertEquals(6.0, split.leftQuad.topRight.x, 0.0)
        assertEquals(6.0, split.rightQuad.topLeft.x, 0.0)
        assertEquals(6.0, split.leftQuad.bottomRight.x, 0.0)
        assertEquals(6.0, split.rightQuad.bottomLeft.x, 0.0)
    }

    // ------------------------------------------------ midpoint-line construction

    @Test
    fun splitCrossingsAreExactlyTheTopAndBottomEdgeMidpoints() {
        val spread = quad(0.0 to 0.0, 12.0 to 0.0, 11.0 to 8.0, 1.0 to 8.0)
        val split = SpreadSplitPlanner.planSplit(spread) as SplitResult.Split
        val topMid = spread.topLeft.midpoint(spread.topRight)
        val bottomMid = spread.bottomLeft.midpoint(spread.bottomRight)
        assertEquals(topMid, split.leftQuad.topRight)
        assertEquals(bottomMid, split.leftQuad.bottomRight)
        assertEquals(topMid, split.rightQuad.topLeft)
        assertEquals(bottomMid, split.rightQuad.bottomLeft)
    }

    @Test
    fun orderRotated180DegreesProducesTheSameTwoPages() {
        // The same square given as [BR, BL, TL, TR]: the midpoint line is
        // the same segment (endpoints swapped), so LEFT/RIGHT swap as
        // point sets but cover the same two pages.
        val original = SpreadSplitPlanner.planSplit(squareSpread())
                as SplitResult.Split
        val rotated = SpreadSplitPlanner.planSplit(
            quad(10.0 to 10.0, 0.0 to 10.0, 0.0 to 0.0, 10.0 to 0.0)
        ) as SplitResult.Split
        assertEquals(
            original.rightQuad.corners.toSet(),
            rotated.leftQuad.corners.toSet(),
        )
        assertEquals(
            original.leftQuad.corners.toSet(),
            rotated.rightQuad.corners.toSet(),
        )
    }

    @Test
    fun orderRotated90DegreesUsesTheOtherEdgePairByConstruction() {
        // Given as [TR, BR, BL, TL] the (TL,TR)/(BL,BR) slots hold the
        // right/left edges, so the construction (purely order-driven,
        // documented) connects THOSE midpoints: a horizontal split.
        val rotated = SpreadSplitPlanner.planSplit(
            quad(10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0, 0.0 to 0.0)
        ) as SplitResult.Split
        assertEquals(
            ModeQuad(
                ModePoint(10.0, 0.0), ModePoint(10.0, 5.0),
                ModePoint(0.0, 5.0), ModePoint(0.0, 0.0),
            ),
            rotated.leftQuad,
        )
        assertEquals(
            ModeQuad(
                ModePoint(10.0, 5.0), ModePoint(10.0, 10.0),
                ModePoint(0.0, 10.0), ModePoint(0.0, 5.0),
            ),
            rotated.rightQuad,
        )
    }

    // ------------------------------------------------ degenerate rejections

    @Test
    fun collinearPointsAreRejectedAsCollinear() {
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 2.0 to 0.0, 4.0 to 0.0, 6.0 to 0.0)
        )
        assertEquals(
            SplitRejectionReason.COLLINEAR_POINTS,
            (r as SplitResult.Degenerate).reason,
        )
    }

    @Test
    fun duplicatePointsAreRejectedAsCollinear() {
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 0.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)
        )
        assertEquals(
            SplitRejectionReason.COLLINEAR_POINTS,
            (r as SplitResult.Degenerate).reason,
        )
    }

    @Test
    fun zeroAreaQuadIsRejectedAsCollinear() {
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 0.0 to 8.0, 10.0 to 0.0, 10.0 to 8.0)
        )
        assertEquals(
            SplitRejectionReason.COLLINEAR_POINTS,
            (r as SplitResult.Degenerate).reason,
        )
    }

    @Test
    fun midlineCollinearWithAnEdgeIsRejectedAsParallelEdges() {
        // Top edge from (0,0) to (10,0); the bottom-edge midpoint lands at
        // (0,0) so the midpoint line runs ALONG the top edge.
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 10.0 to 0.0, 2.0 to -4.0, -2.0 to 4.0)
        )
        assertEquals(
            SplitRejectionReason.PARALLEL_EDGES,
            (r as SplitResult.Degenerate).reason,
        )
    }

    @Test
    fun selfIntersectingOrderIsRejectedAsIntersectionOffQuad() {
        // Bow-tie ordering (nonzero area): the midline picks up crossings
        // on the side edges — not exactly the two spine-crossed edges.
        val r = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 0.0 to 8.0, 10.0 to 0.0, 10.0 to 10.0)
        )
        assertEquals(
            SplitRejectionReason.INTERSECTION_OFF_QUAD,
            (r as SplitResult.Degenerate).reason,
        )
    }

    @Test
    fun plannerNeverThrowsOnExtremeCoordinates() {
        // Huge coordinates: still a deterministic result, no exception.
        val r = SpreadSplitPlanner.planSplit(
            quad(
                -1.0e9 to -1.0e9, 1.0e9 to -1.0e9,
                1.0e9 to 1.0e9, -1.0e9 to 1.0e9,
            )
        )
        val split = r as SplitResult.Split
        assertEquals(0.0, split.leftQuad.topRight.x, 0.0)
    }

    // ------------------------------------------------ output invariants

    @Test
    fun splitPageQuadsAreInCanonicalCornerOrder() {
        val split = SpreadSplitPlanner.planSplit(
            quad(0.0 to 0.0, 12.0 to 0.0, 12.0 to 8.0, 0.0 to 8.0)
        ) as SplitResult.Split
        // TL above TR, TR right of BL etc.: check the top-left corner is
        // the min-x min-y corner of the left page.
        val left = split.leftQuad.boundingBox()
        assertEquals(split.leftQuad.topLeft, ModePoint(left.minX, left.minY))
        val right = split.rightQuad.boundingBox()
        assertEquals(split.rightQuad.topLeft, ModePoint(right.minX, right.minY))
    }

    @Test
    fun leftAndRightPagesPartitionTheSpreadArea() {
        val split = SpreadSplitPlanner.planSplit(squareSpread())
                as SplitResult.Split
        val total = squareSpread().signedArea()
        val sum = split.leftQuad.signedArea() + split.rightQuad.signedArea()
        assertEquals(total, sum, 1e-9)
    }

    @Test
    fun splitPlanningIsDeterministic() {
        val spread = quad(1.0 to 2.0, 13.0 to 1.5, 12.5 to 9.0, 0.5 to 8.5)
        assertEquals(
            SpreadSplitPlanner.planSplit(spread),
            SpreadSplitPlanner.planSplit(spread),
        )
    }

    @Test
    fun splitPageAreasAreEqualForSymmetricSpreads() {
        val split = SpreadSplitPlanner.planSplit(squareSpread())
                as SplitResult.Split
        assertEquals(
            split.leftQuad.signedArea(),
            split.rightQuad.signedArea(),
            1e-12,
        )
        assertTrue(split.leftQuad.isConvex())
        assertTrue(split.rightQuad.isConvex())
    }
}
