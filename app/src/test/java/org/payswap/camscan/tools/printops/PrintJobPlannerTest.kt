package org.payswap.camscan.tools.printops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — PrintJobPlanner coverage: scale-mode content
// rect spot values (fit letterbox / fill crop / actual overflow), copies
// multiplication, range selection, unit views, and every sealed
// degenerate error. Never throws.

class PrintJobPlannerTest {

    private fun page(widthMm: Double, heightMm: Double, id: String = "p1"): PrintPage =
        PrintPage(id, widthMm, heightMm)

    private fun request(
        paperId: String = PaperSizes.ID_A4,
        orientation: Orientation = Orientation.Portrait,
        margins: PrintMargins = PrintMargins.uniform(10.0),
        scaleMode: PrintScaleMode = PrintScaleMode.Fit,
        pageRange: String = "all",
        copies: Int = 1,
    ): PrintRequest = PrintRequest(paperId, orientation, margins, scaleMode, pageRange, copies)

    private fun okPlan(
        pages: List<PrintPage>,
        request: PrintRequest,
    ): PrintJobPlan {
        val result = PrintJobPlanner.plan(pages, request)
        assertTrue("expected Ok, got " + result, result is PrintResult.Ok)
        return (result as PrintResult.Ok).plan
    }

    private fun error(
        pages: List<PrintPage>,
        request: PrintRequest,
    ): PrintPlanError {
        val result = PrintJobPlanner.plan(pages, request)
        assertTrue("expected Error, got " + result, result is PrintResult.Error)
        return (result as PrintResult.Error).error
    }

    // ------------------------------------------------ paper + area basics

    @Test
    fun planCarriesOrientedPaperDimensionsAndContentArea() {
        // A4 portrait, uniform 10 mm margins: area 190 x 277 at (10, 10).
        val plan = okPlan(listOf(page(100.0, 100.0)), request())
        assertEquals(210.0, plan.paperWidthMm, 0.0)
        assertEquals(297.0, plan.paperHeightMm, 0.0)
        assertEquals(10.0, plan.contentAreaMm.x, 0.0)
        assertEquals(10.0, plan.contentAreaMm.y, 0.0)
        assertEquals(190.0, plan.contentAreaMm.width, 0.0)
        assertEquals(277.0, plan.contentAreaMm.height, 0.0)
    }

    @Test
    fun landscapeA4SwapsTheSheetDimensions() {
        val plan = okPlan(
            listOf(page(100.0, 100.0)),
            request(orientation = Orientation.Landscape),
        )
        assertEquals(297.0, plan.paperWidthMm, 0.0)
        assertEquals(210.0, plan.paperHeightMm, 0.0)
        // Area is now 277 x 190.
        assertEquals(277.0, plan.contentAreaMm.width, 0.0)
        assertEquals(190.0, plan.contentAreaMm.height, 0.0)
    }

    // ------------------------------------------------ scale-mode spot values

    @Test
    fun fitContainsTheContentAndLetterboxes() {
        // Content 100x100 into area 190x277: scale 1.9 -> 190x190 centered.
        val plan = okPlan(listOf(page(100.0, 100.0)), request(scaleMode = PrintScaleMode.Fit))
        val rect = plan.placements[0].rectMm
        assertEquals(190.0, rect.width, 1e-9)
        assertEquals(190.0, rect.height, 1e-9)
        assertEquals(10.0, rect.x, 1e-9)
        assertEquals(10.0 + (277.0 - 190.0) / 2.0, rect.y, 1e-9)
    }

    @Test
    fun fitOfTallContentLetterboxesHorizontally() {
        // Content 100x400 into area 190x277: scale = 277/400 = 0.6925.
        val plan = okPlan(listOf(page(100.0, 400.0)), request(scaleMode = PrintScaleMode.Fit))
        val rect = plan.placements[0].rectMm
        assertEquals(69.25, rect.width, 1e-9)
        assertEquals(277.0, rect.height, 1e-9)
        assertEquals(10.0, rect.y, 1e-9)
        assertEquals(10.0 + (190.0 - 69.25) / 2.0, rect.x, 1e-9)
    }

    @Test
    fun fillCoversTheAreaAndCropsTheOverflow() {
        // Content 100x400 into area 190x277: scale = 1.9 -> 190x760, cropped.
        val plan = okPlan(listOf(page(100.0, 400.0)), request(scaleMode = PrintScaleMode.Fill))
        val rect = plan.placements[0].rectMm
        assertEquals(190.0, rect.width, 1e-9)
        assertEquals(760.0, rect.height, 1e-9)
        assertEquals(10.0, rect.x, 1e-9)
        // Centered overflow: y extends past the area on both sides.
        assertEquals(10.0 + (277.0 - 760.0) / 2.0, rect.y, 1e-9)
        assertTrue(rect.y < 0.0)
    }

    @Test
    fun actualKeepsOneToOneScaleCenteredAndMayOverflow() {
        // Content 300x300 at 100% into area 190x277: centered overflow.
        val plan = okPlan(listOf(page(300.0, 300.0)), request(scaleMode = PrintScaleMode.Actual))
        val rect = plan.placements[0].rectMm
        assertEquals(300.0, rect.width, 0.0)
        assertEquals(300.0, rect.height, 0.0)
        assertEquals(10.0 + (190.0 - 300.0) / 2.0, rect.x, 0.0)
        assertEquals(10.0 + (277.0 - 300.0) / 2.0, rect.y, 0.0)
        assertTrue(rect.x < 0.0)
        assertTrue(rect.y < 0.0)
    }

    @Test
    fun aspectMatchingContentFitsExactlyUnderFitAndFill() {
        // Content 190x277 matches the area exactly: no letterbox, no crop.
        for (mode in listOf(PrintScaleMode.Fit, PrintScaleMode.Fill)) {
            val plan = okPlan(listOf(page(190.0, 277.0)), request(scaleMode = mode))
            val rect = plan.placements[0].rectMm
            assertEquals(190.0, rect.width, 1e-9)
            assertEquals(277.0, rect.height, 1e-9)
            assertEquals(10.0, rect.x, 1e-9)
            assertEquals(10.0, rect.y, 1e-9)
        }
    }

    // ------------------------------------------------ unit views

    @Test
    fun pointViewIsMillimetresTimes72Over254() {
        val plan = okPlan(listOf(page(100.0, 100.0)), request(scaleMode = PrintScaleMode.Fit))
        val points = plan.placements[0].rectPoints
        assertEquals(PrintUnits.mmToPoints(10.0), points.x, 0.0)
        assertEquals(PrintUnits.mmToPoints(190.0), points.width, 0.0)
    }

    @Test
    fun pxViewIsThePointValueRoundedHalfUp() {
        val plan = okPlan(listOf(page(100.0, 100.0)), request(scaleMode = PrintScaleMode.Fit))
        val px = plan.placements[0].rectPx
        assertEquals(PrintUnits.roundHalfUp(PrintUnits.mmToPoints(10.0)), px.x)
        assertEquals(PrintUnits.roundHalfUp(PrintUnits.mmToPoints(190.0)), px.width)
        // 10 mm = 28.3464... pt -> 28 px.
        assertEquals(28L, px.x)
        // 190 mm = 538.5826... pt -> 539 px.
        assertEquals(539L, px.width)
    }

    // ------------------------------------------------ ranges + copies

    @Test
    fun copiesMultiplyThePlacementsPerSelectedPage() {
        val pages = listOf(page(100.0, 100.0, "a"), page(100.0, 100.0, "b"))
        val plan = okPlan(pages, request(pageRange = "all", copies = 3))
        assertEquals(6, plan.placements.size)
        assertEquals(6, plan.totalSheetCount)
        // Order: page-major, copies 1..N within a page.
        assertEquals(1, plan.placements[0].pageNumber)
        assertEquals(1, plan.placements[0].copyIndex)
        assertEquals(1, plan.placements[2].pageNumber)
        assertEquals(3, plan.placements[2].copyIndex)
        assertEquals(2, plan.placements[3].pageNumber)
        assertEquals(1, plan.placements[3].copyIndex)
        assertEquals(2, plan.placements[5].pageNumber)
        assertEquals(3, plan.placements[5].copyIndex)
    }

    @Test
    fun rangeSelectionTargetsTheRequestedPages() {
        val pages = listOf(
            page(100.0, 100.0, "a"),
            page(100.0, 100.0, "b"),
            page(100.0, 100.0, "c"),
        )
        val plan = okPlan(pages, request(pageRange = "1,3"))
        assertEquals(2, plan.placements.size)
        assertEquals("a", plan.placements[0].pageId)
        assertEquals("c", plan.placements[1].pageId)
    }

    @Test
    fun singleCopyIsTheDefaultSheetCount() {
        val plan = okPlan(listOf(page(100.0, 100.0)), request())
        assertEquals(1, plan.totalSheetCount)
        assertEquals(1, plan.placements.size)
    }

    @Test
    fun perPagePlacementsUseEachPageOwnDimensions() {
        // Two differently sized pages, Actual mode: each keeps its size.
        val pages = listOf(page(50.0, 60.0, "small"), page(120.0, 40.0, "wide"))
        val plan = okPlan(pages, request(scaleMode = PrintScaleMode.Actual))
        assertEquals(50.0, plan.placements[0].rectMm.width, 0.0)
        assertEquals(60.0, plan.placements[0].rectMm.height, 0.0)
        assertEquals(120.0, plan.placements[1].rectMm.width, 0.0)
        assertEquals(40.0, plan.placements[1].rectMm.height, 0.0)
    }

    // ------------------------------------------------ sealed degenerate errors

    @Test
    fun emptyPageListIsNoPages() {
        val err = error(emptyList(), request())
        assertTrue(err is PrintPlanError.NoPages)
    }

    @Test
    fun nonPositivePageDimensionIsBadPageSize() {
        assertTrue(error(listOf(page(0.0, 100.0)), request()) is PrintPlanError.BadPageSize)
        assertTrue(error(listOf(page(100.0, -1.0)), request()) is PrintPlanError.BadPageSize)
    }

    @Test
    fun nonFinitePageDimensionIsBadPageSize() {
        val bad = PrintPage("p", Double.NaN, 100.0)
        assertTrue(error(listOf(bad), request()) is PrintPlanError.BadPageSize)
    }

    @Test
    fun unknownPaperIsUnknownPaper() {
        val err = error(listOf(page(100.0, 100.0)), request(paperId = "paper-a2"))
        assertTrue(err is PrintPlanError.UnknownPaper)
        assertEquals("paper-a2", (err as PrintPlanError.UnknownPaper).paperId)
    }

    @Test
    fun negativeMarginsAreBadMargins() {
        val err = error(
            listOf(page(100.0, 100.0)),
            request(margins = PrintMargins(-1.0, 0.0, 0.0, 0.0)),
        )
        assertTrue(err is PrintPlanError.BadMargins)
    }

    @Test
    fun nonFiniteMarginsAreBadMargins() {
        val err = error(
            listOf(page(100.0, 100.0)),
            request(margins = PrintMargins(0.0, Double.POSITIVE_INFINITY, 0.0, 0.0)),
        )
        assertTrue(err is PrintPlanError.BadMargins)
    }

    @Test
    fun zeroCopiesIsBadCopies() {
        val err = error(listOf(page(100.0, 100.0)), request(copies = 0))
        assertTrue(err is PrintPlanError.BadCopies)
        assertEquals(0, (err as PrintPlanError.BadCopies).copies)
    }

    @Test
    fun invalidRangeExpressionIsBadPageRange() {
        val err = error(listOf(page(100.0, 100.0)), request(pageRange = "banana"))
        assertTrue(err is PrintPlanError.BadPageRange)
        assertEquals("banana", (err as PrintPlanError.BadPageRange).expression)
    }

    @Test
    fun outOfRangeSelectionIsBadPageRange() {
        assertTrue(
            error(listOf(page(100.0, 100.0)), request(pageRange = "2")) is PrintPlanError.BadPageRange,
        )
    }

    @Test
    fun marginsConsumingTheSheetAreEmptyContentArea() {
        // 105 + 106 > 210 width -> no printable area left.
        val err = error(
            listOf(page(100.0, 100.0)),
            request(margins = PrintMargins(105.0, 10.0, 106.0, 10.0)),
        )
        assertTrue(err is PrintPlanError.EmptyContentArea)
    }

    @Test
    fun validationOrderChecksPagesBeforePaper() {
        // Both wrong: NoPages wins over UnknownPaper.
        val err = error(emptyList(), request(paperId = "nope"))
        assertTrue(err is PrintPlanError.NoPages)
    }

    @Test
    fun validationOrderChecksPageSizeBeforeUnknownPaper() {
        val err = error(listOf(page(0.0, 0.0)), request(paperId = "nope"))
        assertTrue(err is PrintPlanError.BadPageSize)
    }

    @Test
    fun validationOrderChecksPaperBeforeMargins() {
        val err = error(
            listOf(page(100.0, 100.0)),
            request(paperId = "nope", margins = PrintMargins(-1.0, -1.0, -1.0, -1.0)),
        )
        assertTrue(err is PrintPlanError.UnknownPaper)
    }

    @Test
    fun plannerNeverThrowsOnExtremeInput() {
        // Non-throwing smoke: every combination below yields a sealed result.
        val results = listOf(
            PrintJobPlanner.plan(emptyList(), request(copies = -5)),
            PrintJobPlanner.plan(
                listOf(PrintPage("x", 1e308, 1e308)),
                request(scaleMode = PrintScaleMode.Fill, copies = 2),
            ),
            PrintJobPlanner.plan(
                listOf(page(1.0, 1.0)),
                request(pageRange = "1-1", copies = 99),
            ),
        )
        for (result in results) {
            assertTrue(result is PrintResult.Ok || result is PrintResult.Error)
        }
    }
}
