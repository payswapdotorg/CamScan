package org.payswap.camscan.tools.exportops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — LongImagePlanner coverage: strip math table
// (equal widths, differing widths, alignment offsets, seam heights,
// cumulative y-cursor, total-height rounding at exactly .5), validation
// rejections, determinism. Geometry only — raster composition is the
// lead-owned applier seam.

class LongImagePlannerTest {

    private fun ok(
        pages: List<LongImagePage>,
        alignment: HorizontalAlignment = HorizontalAlignment.Left,
        seamPolicy: SeamPolicy = SeamPolicy.None,
    ): LongImagePlan {
        val result = LongImagePlanner.plan(pages, alignment, seamPolicy)
        assertTrue("expected Ok, got " + result, result is LongImageResult.Ok)
        return (result as LongImageResult.Ok).plan
    }

    private fun err(
        pages: List<LongImagePage>,
        alignment: HorizontalAlignment = HorizontalAlignment.Left,
        seamPolicy: SeamPolicy = SeamPolicy.None,
    ): LongImageError {
        val result = LongImagePlanner.plan(pages, alignment, seamPolicy)
        assertTrue("expected Error, got " + result, result is LongImageResult.Error)
        return (result as LongImageResult.Error).error
    }

    // ------------------------------------------------ width normalization

    @Test
    fun equalWidthsKeepTheirWidthAsTheTarget() {
        val plan = ok(
            listOf(
                LongImagePage("a", 100, 50),
                LongImagePage("b", 100, 70),
            ),
        )
        assertEquals(100, plan.targetWidthPx)
        assertEquals(2, plan.placements.size)
        for (placement in plan.placements) {
            assertEquals(100, placement.scaledWidthPx)
        }
    }

    @Test
    fun differingWidthsNormalizeToTheMaximumWidth() {
        val plan = ok(
            listOf(
                LongImagePage("narrow", 50, 80),
                LongImagePage("wide", 200, 40),
            ),
        )
        assertEquals(200, plan.targetWidthPx)
        // Narrow page scales 4x: 50->200 wide, 80->320 tall.
        assertEquals(200, plan.placements[0].scaledWidthPx)
        assertEquals(320, plan.placements[0].scaledHeightPx)
        // Wide page scales 1x.
        assertEquals(200, plan.placements[1].scaledWidthPx)
        assertEquals(40, plan.placements[1].scaledHeightPx)
    }

    @Test
    fun scaledHeightRoundsHalfUpAtExactlyHalf() {
        // scale = 5/2 = 2.5 (binary-exact); height 3 -> 7.5 -> 8.
        val plan = ok(
            listOf(
                LongImagePage("small", 2, 3),
                LongImagePage("big", 5, 10),
            ),
        )
        assertEquals(8, plan.placements[0].scaledHeightPx)
        // scale = 6/4 = 1.5; height 1 -> 1.5 -> 2.
        val plan2 = ok(
            listOf(
                LongImagePage("small", 4, 1),
                LongImagePage("big", 6, 6),
            ),
        )
        assertEquals(2, plan2.placements[0].scaledHeightPx)
    }

    // ------------------------------------------------ alignment

    @Test
    fun alignmentOffsetsAreZeroUnderUniformNormalization() {
        // Documented: every page scales to exactly the target width, so
        // LEFT / CENTER / RIGHT all evaluate to offset 0.
        for (alignment in listOf(
            HorizontalAlignment.Left,
            HorizontalAlignment.Center,
            HorizontalAlignment.Right,
        )) {
            val plan = ok(
                listOf(LongImagePage("a", 50, 30), LongImagePage("b", 100, 60)),
                alignment = alignment,
            )
            assertEquals(0, plan.placements[0].xOffsetPx)
            assertEquals(0, plan.placements[1].xOffsetPx)
        }
    }

    @Test
    fun alignmentOffsetHelperComputesTheGenericRule() {
        // Direct helper truth table with synthetic widths.
        assertEquals(0, LongImagePlanner.alignmentOffset(HorizontalAlignment.Left, 10, 6))
        assertEquals(2, LongImagePlanner.alignmentOffset(HorizontalAlignment.Center, 10, 6))
        assertEquals(4, LongImagePlanner.alignmentOffset(HorizontalAlignment.Right, 10, 6))
        assertEquals(0, LongImagePlanner.alignmentOffset(HorizontalAlignment.Center, 10, 10))
    }

    // ------------------------------------------------ seams + vertical math

    @Test
    fun noSeamPolicyStacksPagesDirectly() {
        val plan = ok(
            listOf(LongImagePage("a", 100, 50), LongImagePage("b", 100, 70)),
            seamPolicy = SeamPolicy.None,
        )
        assertEquals(0, plan.placements[0].yOffsetPx)
        assertEquals(50, plan.placements[1].yOffsetPx)
        assertEquals(120, plan.totalHeightPx)
    }

    @Test
    fun separatorSeamsGoStrictlyBetweenPages() {
        val plan = ok(
            listOf(LongImagePage("a", 100, 50), LongImagePage("b", 100, 70), LongImagePage("c", 100, 10)),
            seamPolicy = SeamPolicy.Separator(3),
        )
        assertEquals(0, plan.placements[0].yOffsetPx)
        assertEquals(53, plan.placements[1].yOffsetPx)
        assertEquals(126, plan.placements[2].yOffsetPx)
        // 50 + 3 + 70 + 3 + 10 = 136.
        assertEquals(136, plan.totalHeightPx)
    }

    @Test
    fun singlePageCarriesNoSeam() {
        val plan = ok(
            listOf(LongImagePage("only", 100, 40)),
            seamPolicy = SeamPolicy.Separator(8),
        )
        assertEquals(0, plan.placements[0].yOffsetPx)
        assertEquals(40, plan.totalHeightPx)
    }

    @Test
    fun cumulativeYCursorUsesScaledHeights() {
        val plan = ok(
            listOf(
                LongImagePage("a", 50, 10),
                LongImagePage("b", 100, 10),
            ),
        )
        // a scales 2x -> height 20; b scales 1x -> height 10.
        assertEquals(0, plan.placements[0].yOffsetPx)
        assertEquals(20, plan.placements[1].yOffsetPx)
        assertEquals(30, plan.totalHeightPx)
    }

    @Test
    fun totalHeightSumsScaledHeightsPlusSeams() {
        val plan = ok(
            listOf(
                LongImagePage("a", 10, 7),
                LongImagePage("b", 20, 9),
                LongImagePage("c", 30, 11),
            ),
            seamPolicy = SeamPolicy.Separator(2),
        )
        // Scales: 3x, 1.5x, 1x -> heights 21, roundHalfUp(13.5)=14, 11.
        assertEquals(21, plan.placements[0].scaledHeightPx)
        assertEquals(14, plan.placements[1].scaledHeightPx)
        assertEquals(11, plan.placements[2].scaledHeightPx)
        // 21 + 2 + 14 + 2 + 11 = 50.
        assertEquals(50, plan.totalHeightPx)
    }

    // ------------------------------------------------ validation

    @Test
    fun emptyPageListIsNoPages() {
        assertTrue(err(emptyList()) is LongImageError.NoPages)
    }

    @Test
    fun zeroWidthPageIsBadPageSize() {
        assertTrue(
            err(listOf(LongImagePage("bad", 0, 10))) is LongImageError.BadPageSize,
        )
    }

    @Test
    fun negativeHeightPageIsBadPageSize() {
        assertTrue(
            err(listOf(LongImagePage("bad", 10, -1))) is LongImageError.BadPageSize,
        )
    }

    @Test
    fun badPageSizeCarriesTheOffendingPageId() {
        val error = err(
            listOf(LongImagePage("good", 10, 10), LongImagePage("bad", 0, 0)),
        ) as LongImageError.BadPageSize
        assertEquals("bad", error.pageId)
    }

    @Test
    fun seamHeightBelowOneIsRejected() {
        assertTrue(
            err(listOf(LongImagePage("a", 10, 10)), seamPolicy = SeamPolicy.Separator(0)) is
                LongImageError.BadSeamHeight,
        )
    }

    @Test
    fun seamHeightAboveEightIsRejected() {
        assertTrue(
            err(listOf(LongImagePage("a", 10, 10)), seamPolicy = SeamPolicy.Separator(9)) is
                LongImageError.BadSeamHeight,
        )
    }

    @Test
    fun seamHeightBoundsAreInclusive() {
        ok(listOf(LongImagePage("a", 10, 10)), seamPolicy = SeamPolicy.Separator(1))
        ok(listOf(LongImagePage("a", 10, 10)), seamPolicy = SeamPolicy.Separator(8))
    }

    // ------------------------------------------------ determinism + metadata

    @Test
    fun planningIsDeterministicForEqualInputs() {
        val pages = listOf(LongImagePage("a", 100, 50), LongImagePage("b", 40, 90))
        val first = LongImagePlanner.plan(pages, HorizontalAlignment.Center, SeamPolicy.Separator(2))
        val second = LongImagePlanner.plan(pages, HorizontalAlignment.Center, SeamPolicy.Separator(2))
        assertEquals((first as LongImageResult.Ok).plan, (second as LongImageResult.Ok).plan)
    }

    @Test
    fun planCarriesItsPageCountAndPolicies() {
        val plan = ok(
            listOf(LongImagePage("a", 100, 50), LongImagePage("b", 40, 90)),
            alignment = HorizontalAlignment.Right,
            seamPolicy = SeamPolicy.Separator(5),
        )
        assertEquals(2, plan.pageCount)
        assertTrue(plan.alignment is HorizontalAlignment.Right)
        assertTrue(plan.seamPolicy is SeamPolicy.Separator)
        assertEquals(5, (plan.seamPolicy as SeamPolicy.Separator).heightPx)
    }

    @Test
    fun placementsKeepTheirPageIdsInOrder() {
        val plan = ok(
            listOf(LongImagePage("first", 100, 50), LongImagePage("second", 100, 50)),
        )
        assertEquals(listOf("first", "second"), plan.placements.map { p -> p.pageId })
    }
}
