package org.payswap.camscan.tools.printops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — paper catalog + orientation + margins + units
// coverage: exact mm table, stable ids, the documented swap rule,
// margin validity, mm->points conversion and half-up rounding.

class PaperSizeTest {

    @Test
    fun catalogCarriesExactMillimetreDimensions() {
        assertEquals(PaperSize("paper-a3", 297.0, 420.0), PaperSizes.byId("paper-a3"))
        assertEquals(PaperSize("paper-a4", 210.0, 297.0), PaperSizes.byId("paper-a4"))
        assertEquals(PaperSize("paper-a5", 148.0, 210.0), PaperSizes.byId("paper-a5"))
        assertEquals(PaperSize("paper-letter", 215.9, 279.4), PaperSizes.byId("paper-letter"))
        assertEquals(PaperSize("paper-legal", 215.9, 355.6), PaperSizes.byId("paper-legal"))
        assertEquals(PaperSize("paper-ledger", 279.4, 431.8), PaperSizes.byId("paper-ledger"))
    }

    @Test
    fun catalogOrderIsStable() {
        assertEquals(
            listOf(
                PaperSizes.ID_A3,
                PaperSizes.ID_A4,
                PaperSizes.ID_A5,
                PaperSizes.ID_LETTER,
                PaperSizes.ID_LEGAL,
                PaperSizes.ID_LEDGER,
            ),
            PaperSizes.ALL.map { p -> p.paperId },
        )
    }

    @Test
    fun unknownIdReturnsNull() {
        assertNull(PaperSizes.byId("paper-a2"))
        assertNull(PaperSizes.byId("A4"))
        assertNull(PaperSizes.byId(""))
    }

    @Test
    fun allCatalogPapersArePortraitNative() {
        for (paper in PaperSizes.ALL) {
            assertTrue(paper.paperId + " must be portrait-native", paper.widthMm <= paper.heightMm)
        }
    }
}

class OrientationTest {

    @Test
    fun portraitKeepsPortraitNativePapers() {
        val oriented = Orientation.Portrait.apply(PaperSizes.byId("paper-a4")!!)
        assertEquals(210.0, oriented.widthMm, 0.0)
        assertEquals(297.0, oriented.heightMm, 0.0)
    }

    @Test
    fun portraitSwapsLandscapeNativeInputSoWidthLteHeight() {
        val landscapeNative = PaperSize("synthetic", 420.0, 297.0)
        val oriented = Orientation.Portrait.apply(landscapeNative)
        assertEquals(297.0, oriented.widthMm, 0.0)
        assertEquals(420.0, oriented.heightMm, 0.0)
    }

    @Test
    fun landscapeSwapsPortraitNativePapersSoWidthGteHeight() {
        val oriented = Orientation.Landscape.apply(PaperSizes.byId("paper-a4")!!)
        assertEquals(297.0, oriented.widthMm, 0.0)
        assertEquals(210.0, oriented.heightMm, 0.0)
    }

    @Test
    fun landscapeKeepsLandscapeNativeInput() {
        val landscapeNative = PaperSize("synthetic", 420.0, 297.0)
        val oriented = Orientation.Landscape.apply(landscapeNative)
        assertEquals(420.0, oriented.widthMm, 0.0)
        assertEquals(297.0, oriented.heightMm, 0.0)
    }

    @Test
    fun squarePaperNeverSwaps() {
        val square = PaperSize("synthetic", 100.0, 100.0)
        val portrait = Orientation.Portrait.apply(square)
        val landscape = Orientation.Landscape.apply(square)
        assertEquals(100.0, portrait.widthMm, 0.0)
        assertEquals(100.0, landscape.widthMm, 0.0)
    }

    @Test
    fun letterLandscapeUsesTheLongSideAsWidth() {
        val oriented = Orientation.Landscape.apply(PaperSizes.byId("paper-letter")!!)
        assertEquals(279.4, oriented.widthMm, 0.0)
        assertEquals(215.9, oriented.heightMm, 0.0)
    }
}

class PrintMarginsTest {

    @Test
    fun uniformSetsAllFourSides() {
        val margins = PrintMargins.uniform(10.0)
        assertEquals(10.0, margins.leftMm, 0.0)
        assertEquals(10.0, margins.topMm, 0.0)
        assertEquals(10.0, margins.rightMm, 0.0)
        assertEquals(10.0, margins.bottomMm, 0.0)
    }

    @Test
    fun zeroMarginsAreValid() {
        assertTrue(PrintMargins.uniform(0.0).isValid)
    }

    @Test
    fun negativeSideIsInvalid() {
        assertTrue(!PrintMargins(-1.0, 0.0, 0.0, 0.0).isValid)
        assertTrue(!PrintMargins(0.0, 0.0, 0.0, -0.5).isValid)
    }

    @Test
    fun nonFiniteSideIsInvalid() {
        assertTrue(!PrintMargins(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0).isValid)
        assertTrue(!PrintMargins(0.0, Double.NaN, 0.0, 0.0).isValid)
    }

    @Test
    fun perSideValuesAreIndependent() {
        val margins = PrintMargins(1.0, 2.0, 3.0, 4.0)
        assertTrue(margins.isValid)
        assertEquals(3.0, margins.rightMm, 0.0)
        assertEquals(4.0, margins.bottomMm, 0.0)
    }
}

class PrintUnitsTest {

    @Test
    fun oneInchConvertsToExactly72Points() {
        assertEquals(72.0, PrintUnits.mmToPoints(25.4), 1e-9)
    }

    @Test
    fun a4WidthConvertsToTheKnownPointValue() {
        // 210 mm = 595.275590... points (the standard A4 width in points).
        assertEquals(595.2755905511812, PrintUnits.mmToPoints(210.0), 1e-9)
    }

    @Test
    fun zeroAndNegativeMillimetresConvertLinearly() {
        assertEquals(0.0, PrintUnits.mmToPoints(0.0), 0.0)
        assertEquals(-72.0, PrintUnits.mmToPoints(-25.4), 1e-9)
    }

    @Test
    fun roundHalfUpRoundsTiesUpward() {
        assertEquals(1L, PrintUnits.roundHalfUp(0.5))
        assertEquals(2L, PrintUnits.roundHalfUp(1.5))
        assertEquals(-1L, PrintUnits.roundHalfUp(-0.5))
        assertEquals(-2L, PrintUnits.roundHalfUp(-1.5))
    }

    @Test
    fun roundHalfUpRoundsNonTiesToTheNearest() {
        assertEquals(0L, PrintUnits.roundHalfUp(0.49))
        assertEquals(1L, PrintUnits.roundHalfUp(0.51))
        assertEquals(7L, PrintUnits.roundHalfUp(6.5 + 0.0))
    }

    @Test
    fun roundHalfUpPassesIntegersThrough() {
        assertEquals(5L, PrintUnits.roundHalfUp(5.0))
        assertEquals(-5L, PrintUnits.roundHalfUp(-5.0))
    }
}
