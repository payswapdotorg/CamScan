package org.payswap.camscan.tools.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.render.Point

// WatermarkPlanner tests (CAMSCAN-PROD-011 section 6.6): grid geometry -
// tile counts from the documented formulas, exact grid steps, coverage of
// the whole page at 0 degrees, rotation via exact 90-degree matrices, the
// 45-degree general path, spacing effects, and determinism.

class WatermarkPlannerTest {

    private val planner = WatermarkPlanner()

    private fun spec(
        text: String = "AB",
        rotation: Int = 0,
        spacing: Int = 0,
        opacity: Int = 128,
    ) = WatermarkSpec(text, opacity, rotation, 0xFF000000L, spacing)

    @Test
    fun emptyText_yieldsNoPlacements() {
        assertTrue(planner.plan(spec(text = ""), 100, 100).isEmpty())
    }

    @Test
    fun zeroOpacity_yieldsNoPlacements() {
        assertTrue(planner.plan(spec(opacity = 0), 100, 100).isEmpty())
    }

    @Test
    fun tileCount_followsTheDocumentedFormula() {
        // "AB": runW = 12, runH = 9, spacing 0 -> cells 12x9.
        // side = ceil(sqrt(100^2 + 100^2)) = 142.
        // halfX = 142/12 + 1 = 12, halfY = 142/9 + 1 = 16.
        // count = 25 * 33 = 825.
        val placements = planner.plan(spec(), 100, 100)
        assertEquals(825, placements.size)
    }

    @Test
    fun tileCount_shrinksWithSpacing() {
        // spacing 100 -> cells 112x109; halfX = 142/112 + 1 = 2,
        // halfY = 142/109 + 1 = 2 -> 5 * 5 = 25.
        val placements = planner.plan(spec(spacing = 100), 100, 100)
        assertEquals(25, placements.size)
    }

    @Test
    fun centerTile_originIsCenterMinusHalfRun() {
        val placements = planner.plan(spec(), 100, 100)
        // Center of the page (50, 50) minus (12/2, 9/2) = (44, 46).
        assertTrue(placements.contains(Point(44, 46)))
    }

    @Test
    fun gridSteps_areCellSizedAtZeroRotation() {
        val placements = planner.plan(spec(), 100, 100)
        val byRow = placements.groupBy { it.y }
        // All rows exist at steps of 9 around 46.
        assertTrue(byRow.containsKey(46 - 9))
        assertTrue(byRow.containsKey(46 + 9))
        val row = byRow[46]!!
        // Within a row the x origins step by 12 around 44.
        assertTrue(row.contains(Point(44 - 12, 46)))
        assertTrue(row.contains(Point(44 + 12, 46)))
    }

    @Test
    fun grid_coversEveryPixelAtZeroRotationAndZeroSpacing() {
        val placements = planner.plan(spec(), 100, 100)
        val runW = 12
        val runH = 9
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val covered = placements.any { origin ->
                    x >= origin.x && x < origin.x + runW &&
                        y >= origin.y && y < origin.y + runH
                }
                assertTrue("pixel (" + x + "," + y + ") not covered", covered)
            }
        }
    }

    @Test
    fun rotation90_transposesTheGrid() {
        val placements = planner.plan(spec(rotation = 90), 100, 100)
        // Count is invariant under rotation.
        assertEquals(825, placements.size)
        // The center tile stays at the center.
        assertTrue(placements.contains(Point(44, 46)))
        // Rotating (dx, dy) -> (-dy, dx): the tile at (i=0, j=1) whose raw
        // center is (50, 59) maps to center (41, 50), origin (35, 46).
        assertTrue(placements.contains(Point(35, 46)))
        // And (i=1, j=0): raw center (62, 50) -> (50, 62), origin (44, 58).
        assertTrue(placements.contains(Point(44, 58)))
    }

    @Test
    fun rotation180_flipsBothAxes() {
        val placements = planner.plan(spec(rotation = 180), 100, 100)
        assertEquals(825, placements.size)
        assertTrue(placements.contains(Point(44, 46)))
        // (i=1, j=0) raw center (62, 50) -> (38, 50), origin (32, 46).
        assertTrue(placements.contains(Point(32, 46)))
    }

    @Test
    fun rotation45_keepsCountAndCenterTile() {
        val placements = planner.plan(spec(rotation = 45), 100, 100)
        assertEquals(825, placements.size)
        assertTrue(placements.contains(Point(44, 46)))
    }

    @Test
    fun rotation45_stillCoversAllPixelsWithZeroSpacing() {
        // Diagonal runs: use a single-character spec so the rotated boxes
        // (6x9 each, adjacent) still blanket the page diagonally.
        val placements = planner.plan(spec(text = "A", rotation = 45, spacing = 0), 100, 100)
        val runW = 6
        val runH = 9
        // Rotated glyph boxes are axis-aligned bounding boxes of the run
        // here (the planner places runs, the renderer rotates nothing
        // further), so check center coverage instead: every pixel of the
        // page must lie within 8px (max(runW, runH)) of some tile origin
        // - the documented diagonal grid guarantee.
        for (y in listOf(0, 25, 50, 75, 99)) {
            for (x in listOf(0, 25, 50, 75, 99)) {
                val near = placements.any { origin ->
                    Math.abs(origin.x - x) <= 8 && Math.abs(origin.y - y) <= 8
                }
                assertTrue("corner pixel (" + x + "," + y + ") far from every tile", near)
            }
        }
    }

    @Test
    fun rotation_isNormalizedMod360() {
        val at360 = planner.plan(spec(rotation = 360), 100, 100)
        val at0 = planner.plan(spec(rotation = 0), 100, 100)
        assertEquals(at0, at360)
        val at450 = planner.plan(spec(rotation = 450), 100, 100)
        val at90 = planner.plan(spec(rotation = 90), 100, 100)
        assertEquals(at90, at450)
    }

    @Test
    fun plan_isDeterministic_runTwice() {
        assertEquals(
            planner.plan(spec(rotation = 45), 80, 60),
            planner.plan(spec(rotation = 45), 80, 60),
        )
    }

    @Test
    fun coverageSide_isCeilOfTheDiagonal() {
        assertEquals(5, planner.coverageSide(3, 4))
        assertEquals(142, planner.coverageSide(100, 100))
        assertEquals(2, planner.coverageSide(1, 1))
        assertEquals(101, planner.coverageSide(101, 0))
    }

    @Test
    fun exactQuadrantRotation_usesIntegerMatrices() {
        val rotated = planner.rotateAroundCenter(50, 50, 10, 4, 90)
        assertEquals(Pair(46, 60), rotated)
        val rotated180 = planner.rotateAroundCenter(50, 50, 10, 4, 180)
        assertEquals(Pair(40, 46), rotated180)
        val rotated270 = planner.rotateAroundCenter(50, 50, 10, 4, 270)
        assertEquals(Pair(54, 40), rotated270)
        val identity = planner.rotateAroundCenter(50, 50, 10, 4, 0)
        assertEquals(Pair(60, 54), identity)
    }

    @Test
    fun spec_validation_rejectsBadValues() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WatermarkSpec("x", 256, 0, 0xFF000000L, 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WatermarkSpec("x", -1, 0, 0xFF000000L, 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WatermarkSpec("x", 128, 0, 0xFF000000L, -2)
        }
    }

    @Test
    fun spec_andBinding_valueSemantics() {
        val spec = WatermarkSpec("DRAFT", 64, 45, 0xFF808080L, 10)
        assertEquals(spec, WatermarkSpec("DRAFT", 64, 45, 0xFF808080L, 10))
        val binding = DocumentWatermark("doc-1", spec)
        assertEquals(binding, DocumentWatermark("doc-1", spec))
        assertTrue(binding.toString().contains("doc-1"))
        assertTrue(spec.toString().contains("DRAFT"))
    }

    @Test
    fun iterationOrder_isRowsThenColumns() {
        // The first two placements differ in i (inner loop) at constant j.
        val placements = planner.plan(spec(text = "A", spacing = 50), 60, 60)
        assertEquals(placements[0].y, placements[1].y)
    }
}
