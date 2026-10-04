package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.printops.Orientation
import org.payswap.camscan.tools.printops.PaperSizes
import org.payswap.camscan.tools.printops.PrintJobPlanner
import org.payswap.camscan.tools.printops.PrintScaleMode
import org.payswap.camscan.tools.printops.PrintPage

// CAMSCAN-VERIFY-002 — JVM tests of the print picker state + the
// pixel-to-millimetre bridge: paper/scale selection guards, the built
// request's documented defaults, and an integration pass through the
// delivered planner proving the built request plans real pages.

class PrintPickerUiTest {

    @Test
    fun defaults_areA4PortraitFitTenMillimetreMarginsAllPagesOneCopy() {
        val ui = PrintPickerUi()
        assertEquals(PaperSizes.ID_A4, ui.paperId())
        assertEquals(PrintScaleChoices.FIT, ui.scaleModeId())
        val request = ui.buildRequest()
        assertEquals(PaperSizes.ID_A4, request.paperId)
        assertTrue(request.orientation is Orientation.Portrait)
        assertEquals(10.0, request.margins.leftMm, 0.0)
        assertEquals(10.0, request.margins.topMm, 0.0)
        assertEquals(10.0, request.margins.rightMm, 0.0)
        assertEquals(10.0, request.margins.bottomMm, 0.0)
        assertTrue(request.scaleMode is PrintScaleMode.Fit)
        assertEquals("all", request.pageRange)
        assertEquals(1, request.copies)
    }

    @Test
    fun selectPaper_acceptsOnlyOfferedPapers() {
        val ui = PrintPickerUi()
        assertTrue(ui.selectPaper(PaperSizes.ID_LETTER))
        assertEquals(PaperSizes.ID_LETTER, ui.paperId())
        assertTrue(ui.selectPaper(PaperSizes.ID_A4))
        assertFalse("non-offered papers are rejected", ui.selectPaper(PaperSizes.ID_A3))
        assertEquals(PaperSizes.ID_A4, ui.paperId())
    }

    @Test
    fun selectScaleMode_acceptsOnlyOfferedModes() {
        val ui = PrintPickerUi()
        assertTrue(ui.selectScaleMode(PrintScaleChoices.FILL))
        assertEquals(PrintScaleChoices.FILL, ui.scaleModeId())
        assertTrue(ui.selectScaleMode(PrintScaleChoices.ACTUAL))
        assertFalse("unknown modes are rejected", ui.selectScaleMode("center"))
        assertEquals(PrintScaleChoices.ACTUAL, ui.scaleModeId())
    }

    @Test
    fun toScaleMode_mapsEveryOfferedId() {
        val ui = PrintPickerUi()
        assertTrue(ui.toScaleMode(PrintScaleChoices.FIT) is PrintScaleMode.Fit)
        assertTrue(ui.toScaleMode(PrintScaleChoices.FILL) is PrintScaleMode.Fill)
        assertTrue(ui.toScaleMode(PrintScaleChoices.ACTUAL) is PrintScaleMode.Actual)
    }

    @Test
    fun builtRequest_plansRealPagesThroughTheDeliveredPlanner() {
        val ui = PrintPickerUi()
        ui.selectPaper(PaperSizes.ID_LETTER)
        val pages = listOf(
            PrintPage("p1", 210.0, 297.0),
            PrintPage("p2", 148.0, 210.0),
        )
        val outcome = PrintJobPlanner.plan(pages, ui.buildRequest())
        assertTrue("the built request must plan cleanly", outcome is org.payswap.camscan.tools.printops.PrintResult.Ok)
        outcome as org.payswap.camscan.tools.printops.PrintResult.Ok
        assertEquals(2, outcome.plan.totalSheetCount)
        assertEquals(PaperSizes.ID_LETTER, outcome.plan.paperId)
    }

    @Test
    fun printGeometry_mmFromPx_usesTheDocumentedThreeHundredDpi() {
        // 300 px at 300 dpi = 1 inch = 25.4 mm exactly.
        assertEquals(25.4, org.payswap.camscan.export.print.PrintGeometry.mmFromPx(300), 1e-9)
        assertEquals(50.8, org.payswap.camscan.export.print.PrintGeometry.mmFromPx(600), 1e-9)
        assertEquals(0.0, org.payswap.camscan.export.print.PrintGeometry.mmFromPx(0), 1e-9)
    }

    @Test
    fun printGeometry_swapsSidesForQuarterTurnPages() {
        val pages = org.payswap.camscan.export.print.PrintGeometry.printPagesFrom(
            pages = listOf(Triple("p1", 2480, 3508)),
            rotationDegreesOf = { 90 },
        )
        assertEquals(3508.0 / 300.0 * 25.4, pages[0].widthMm, 1e-9)
        assertEquals(2480.0 / 300.0 * 25.4, pages[0].heightMm, 1e-9)
    }

    @Test
    fun printGeometry_keepsSidesForUnrotatedAndHalfTurnPages() {
        for (rotation in intArrayOf(0, 180, 360)) {
            val pages = org.payswap.camscan.export.print.PrintGeometry.printPagesFrom(
                pages = listOf(Triple("p1", 2480, 3508)),
                rotationDegreesOf = { rotation },
            )
            assertEquals(2480.0 / 300.0 * 25.4, pages[0].widthMm, 1e-9)
            assertEquals(3508.0 / 300.0 * 25.4, pages[0].heightMm, 1e-9)
        }
    }
}
