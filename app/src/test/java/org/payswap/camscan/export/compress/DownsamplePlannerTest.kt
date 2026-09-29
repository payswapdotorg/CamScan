package org.payswap.camscan.export.compress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// JVM tests for DownsamplePlanner + CompressPdfOptions (CAMSCAN-PROD-008
// §6.5/§6.7): the pure integer target-dim math (half-up rational scaling),
// the never-upscale law, the within-budget skip rule, and the option clamps.
class DownsamplePlannerTest {

    private val planner = DownsamplePlanner()
    private val defaults = CompressPdfOptions()

    @Test
    fun withinBudget_skipsWithDimsPassedThrough() {
        // Long edge EXACTLY the budget skips (already within target).
        val boundary = planner.plan(1600, 1200, defaults)
        assertTrue(boundary.skipDownsample)
        assertEquals(1600, boundary.targetWidthPx)
        assertEquals(1200, boundary.targetHeightPx)
        assertEquals(1600, boundary.sourceWidthPx)
        assertEquals(1200, boundary.sourceHeightPx)

        // Comfortably smaller also skips.
        assertTrue(planner.plan(800, 600, defaults).skipDownsample)
        // Portrait boundary too.
        assertTrue(planner.plan(1200, 1600, defaults).skipDownsample)
    }

    @Test
    fun targetMath_landscapeExactBudget() {
        // 3200x2400, budget 1600: (3200*1600 + 1600)/3200 = 1600,
        // (2400*1600 + 1600)/3200 = 1200.
        val plan = planner.plan(3200, 2400, defaults)
        assertFalse(plan.skipDownsample)
        assertEquals(1600, plan.targetWidthPx)
        assertEquals(1200, plan.targetHeightPx)
    }

    @Test
    fun targetMath_halfUpRoundingOnTheShortEdge() {
        // 3000x2000, budget 1600: long edge -> 1600; short edge
        // (2000*1600 + 1500)/3000 = 1067 (half-up, exact integer math).
        val plan = planner.plan(3000, 2000, defaults)
        assertEquals(1600, plan.targetWidthPx)
        assertEquals(1067, plan.targetHeightPx)
    }

    @Test
    fun targetMath_portraitOrientation() {
        val plan = planner.plan(2400, 3200, defaults)
        assertEquals(1200, plan.targetWidthPx)
        assertEquals(1600, plan.targetHeightPx)
    }

    @Test
    fun targetMath_customBudget() {
        // 4000x3000, budget 1000: (4000*1000 + 2000)/4000 = 1000,
        // (3000*1000 + 2000)/4000 = 750.
        val plan = planner.plan(4000, 3000, CompressPdfOptions(maxLongEdgePx = 1000))
        assertEquals(1000, plan.targetWidthPx)
        assertEquals(750, plan.targetHeightPx)
    }

    @Test
    fun neverUpscales_anyShape() {
        val shapes = listOf(
            3200 to 2400,
            2400 to 3200,
            2000 to 2000,
            1700 to 100,
            100 to 1700,
            1601 to 1600,
            9999 to 3,
        )
        for ((width, height) in shapes) {
            val plan = planner.plan(width, height, defaults)
            assertTrue("width never upscales at " + width + "x" + height, plan.targetWidthPx <= width)
            assertTrue("height never upscales at " + width + "x" + height, plan.targetHeightPx <= height)
            assertTrue(
                "long edge lands within the budget",
                maxOf(plan.targetWidthPx, plan.targetHeightPx) <= defaults.maxLongEdgePx,
            )
            assertEquals(width, plan.sourceWidthPx)
            assertEquals(height, plan.sourceHeightPx)
        }
    }

    @Test
    fun extremeAspect_clampsShortEdgeToAtLeastOnePixel() {
        // 100000x10, budget 1600: short edge (10*1600 + 50000)/100000 = 0,
        // clamped to 1 px — never a zero-dimension page.
        val plan = planner.plan(100000, 10, defaults)
        assertEquals(1600, plan.targetWidthPx)
        assertEquals(1, plan.targetHeightPx)
        assertFalse(plan.skipDownsample)
    }

    @Test
    fun options_qualityClampedInto0To100_defaultsAreTheDocumentedValues() {
        assertEquals(70, CompressPdfOptions.DEFAULT_QUALITY)
        assertEquals(1600, CompressPdfOptions.DEFAULT_MAX_LONG_EDGE_PX)
        assertEquals(70, defaults.quality)
        assertEquals(1600, defaults.maxLongEdgePx)
        // Clamp both sides of 0..100.
        assertEquals(0, CompressPdfOptions(quality = -5).quality)
        assertEquals(0, CompressPdfOptions(quality = 0).quality)
        assertEquals(100, CompressPdfOptions(quality = 100).quality)
        assertEquals(100, CompressPdfOptions(quality = 250).quality)
        // The long edge clamps to at least 1 px.
        assertEquals(1, CompressPdfOptions(maxLongEdgePx = 0).maxLongEdgePx)
        assertEquals(1, CompressPdfOptions(maxLongEdgePx = -3).maxLongEdgePx)
    }
}
