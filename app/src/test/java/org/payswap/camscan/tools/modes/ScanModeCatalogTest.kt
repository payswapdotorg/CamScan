package org.payswap.camscan.tools.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-012 §7 — catalog coverage: four modes, exact millimetres
// and aspects, tolerance, deterministic iteration order, modeId lookup.

class ScanModeCatalogTest {

    private val usCard = BusinessCardMode(BusinessCardVariant.US)
    private val euCard = BusinessCardMode(BusinessCardVariant.EU)

    @Test
    fun catalogHasExactlyFourModes() {
        assertEquals(4, ScanModeCatalog.size)
        assertEquals(4, ScanModeCatalog.modes.size)
    }

    @Test
    fun iterationOrderIsDeclarationOrder() {
        val expected = listOf<ScanMode>(IdCardMode, usCard, euCard, BookSpreadMode)
        assertEquals(expected, ScanModeCatalog.modes)
    }

    @Test
    fun iterationOrderIsStableAcrossReads() {
        assertEquals(ScanModeCatalog.modes, ScanModeCatalog.modes)
        assertEquals(
            ScanModeCatalog.modes.map { it.modeId },
            ScanModeCatalog.modes.map { it.modeId },
        )
    }

    @Test
    fun idCardDimensionsAreId1Authority() {
        assertEquals(85.60, IdCardMode.targetWidthMm, 0.0)
        assertEquals(53.98, IdCardMode.targetHeightMm, 0.0)
    }

    @Test
    fun idCardAspectIsWidthOverHeight() {
        assertEquals(85.60 / 53.98, IdCardMode.targetAspect, 0.0)
    }

    @Test
    fun businessCardUsDimensionsAreUsAuthority() {
        assertEquals(88.9, usCard.targetWidthMm, 0.0)
        assertEquals(50.8, usCard.targetHeightMm, 0.0)
        assertEquals(88.9 / 50.8, usCard.targetAspect, 0.0)
    }

    @Test
    fun businessCardEuDimensionsAreEuAuthority() {
        assertEquals(85.0, euCard.targetWidthMm, 0.0)
        assertEquals(55.0, euCard.targetHeightMm, 0.0)
        assertEquals(85.0 / 55.0, euCard.targetAspect, 0.0)
    }

    @Test
    fun bookSpreadDimensionsArePerFaceA5Authority() {
        assertEquals(148.5, BookSpreadMode.targetWidthMm, 0.0)
        assertEquals(210.0, BookSpreadMode.targetHeightMm, 0.0)
    }

    @Test
    fun bookSpreadAspectIsFullSpreadAspect() {
        // Documented: the observed quad is the FULL spread (two A5 faces
        // side by side = 297 x 210 mm), so the matching aspect is
        // (2 * per-face width) / per-face height.
        assertEquals((2.0 * 148.5) / 210.0, BookSpreadMode.targetAspect, 0.0)
    }

    @Test
    fun everyModeCarriesTwelvePercentTolerance() {
        for (mode in ScanModeCatalog.modes) {
            assertEquals("tolerance of " + mode.modeId, 0.12, mode.tolerance, 0.0)
        }
    }

    @Test
    fun modeIdConstantsAreTheStableSemanticIds() {
        assertEquals("mode-id-card", ScanModeIds.ID_CARD)
        assertEquals("mode-business-card", ScanModeIds.BUSINESS_CARD)
        assertEquals("mode-book-spread", ScanModeIds.BOOK_SPREAD)
    }

    @Test
    fun everyModeCarriesItsStableModeId() {
        assertEquals("mode-id-card", IdCardMode.modeId)
        assertEquals("mode-business-card", usCard.modeId)
        assertEquals("mode-business-card", euCard.modeId)
        assertEquals("mode-book-spread", BookSpreadMode.modeId)
    }

    @Test
    fun displayLabelIdEqualsModeIdByConvention() {
        for (mode in ScanModeCatalog.modes) {
            assertEquals("displayLabelId of " + mode.modeId, mode.modeId, mode.displayLabelId)
        }
    }

    @Test
    fun splitPolicyNoneForSinglePageModes() {
        assertEquals(SplitPolicy.NONE, IdCardMode.splitPolicy)
        assertEquals(SplitPolicy.NONE, usCard.splitPolicy)
        assertEquals(SplitPolicy.NONE, euCard.splitPolicy)
    }

    @Test
    fun splitPolicyMidlineForBookSpread() {
        assertEquals(SplitPolicy.BOOK_SPREAD_MIDLINE, BookSpreadMode.splitPolicy)
    }

    @Test
    fun byModeIdResolvesIdCard() {
        assertEquals(IdCardMode, ScanModeCatalog.byModeId("mode-id-card"))
    }

    @Test
    fun byModeIdResolvesBusinessCardToUsDefault() {
        // Deterministic tie-break: catalog order — the US variant is first.
        assertEquals(usCard, ScanModeCatalog.byModeId("mode-business-card"))
    }

    @Test
    fun byModeIdResolvesBookSpread() {
        assertEquals(BookSpreadMode, ScanModeCatalog.byModeId("mode-book-spread"))
    }

    @Test
    fun byModeIdReturnsNullForUnknownId() {
        assertNull(ScanModeCatalog.byModeId("mode-passport"))
        assertNull(ScanModeCatalog.byModeId(""))
    }

    @Test
    fun businessCardVariantsListBothInCatalogOrder() {
        assertEquals(listOf(usCard, euCard), ScanModeCatalog.businessCardVariants())
    }

    @Test
    fun businessCardVariantsAreDistinctModes() {
        assertTrue(usCard != euCard)
        assertTrue(usCard.targetWidthMm != euCard.targetWidthMm)
        assertTrue(usCard.targetAspect != euCard.targetAspect)
    }

    @Test
    fun everyModeAspectIsFiniteAndPositive() {
        for (mode in ScanModeCatalog.modes) {
            assertTrue("aspect of " + mode.modeId, mode.targetAspect.isFinite())
            assertTrue("aspect of " + mode.modeId, mode.targetAspect > 0.0)
        }
    }
}
