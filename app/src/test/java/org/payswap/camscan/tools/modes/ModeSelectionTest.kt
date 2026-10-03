package org.payswap.camscan.tools.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-012 §7 — selection-machine coverage: the matching table
// (exact aspects, +/-11 percent, +/-13 percent, +/-12 percent boundary
// sides), the deterministic tie-break, ambiguity, degenerate quads, the
// direct-aspect path, explicit user choice and confidence spot values.
// Expected outcomes were pinned against IEEE-754 Double arithmetic (the
// same arithmetic Kotlin performs) before the tests were written.

class ModeSelectionTest {

    private val usCard = BusinessCardMode(BusinessCardVariant.US)
    private val euCard = BusinessCardMode(BusinessCardVariant.EU)

    private fun selectedMode(result: ModeSelectionResult): ScanMode? {
        return if (result is ModeSelectionResult.Selected) result.mode else null
    }

    private fun rejection(result: ModeSelectionResult): ModeRejectionReason? {
        return if (result is ModeSelectionResult.Rejected) result.reason else null
    }

    // ------------------------------------------------ exact aspects

    @Test
    fun exactIdCardAspectSelectsIdCardWithFullConfidence() {
        val r = ModeSelector.select(IdCardMode.targetAspect)
        assertEquals(IdCardMode, selectedMode(r))
        assertTrue((r as ModeSelectionResult.Selected).confidence == 1.0)
    }

    @Test
    fun exactUsBusinessCardAspectSelectsUsVariant() {
        val r = ModeSelector.select(usCard.targetAspect)
        assertEquals(usCard, selectedMode(r))
        assertTrue((r as ModeSelectionResult.Selected).confidence == 1.0)
    }

    @Test
    fun exactEuBusinessCardAspectSelectsEuVariant() {
        val r = ModeSelector.select(euCard.targetAspect)
        assertEquals(euCard, selectedMode(r))
        assertTrue((r as ModeSelectionResult.Selected).confidence == 1.0)
    }

    @Test
    fun exactBookSpreadAspectSelectsBookSpread() {
        val r = ModeSelector.select(BookSpreadMode.targetAspect)
        assertEquals(BookSpreadMode, selectedMode(r))
        assertTrue((r as ModeSelectionResult.Selected).confidence == 1.0)
    }

    // ------------------------------------------------ +/-11 percent

    @Test
    fun idCardPlusElevenPercentSelectsNearestModeUsCard() {
        val r = ModeSelector.select(IdCardMode.targetAspect * 1.11)
        assertEquals(usCard, selectedMode(r))
    }

    @Test
    fun idCardMinusElevenPercentSelectsNearestModeBookSpread() {
        val r = ModeSelector.select(IdCardMode.targetAspect * 0.89)
        assertEquals(BookSpreadMode, selectedMode(r))
    }

    @Test
    fun usCardPlusElevenPercentStillSelectsUsCard() {
        val r = ModeSelector.select(usCard.targetAspect * 1.11)
        assertEquals(usCard, selectedMode(r))
    }

    @Test
    fun usCardMinusElevenPercentSelectsEuCard() {
        val r = ModeSelector.select(usCard.targetAspect * 0.89)
        assertEquals(euCard, selectedMode(r))
    }

    @Test
    fun euCardPlusElevenPercentSelectsUsCard() {
        val r = ModeSelector.select(euCard.targetAspect * 1.11)
        assertEquals(usCard, selectedMode(r))
    }

    @Test
    fun euCardMinusElevenPercentSelectsBookSpread() {
        val r = ModeSelector.select(euCard.targetAspect * 0.89)
        assertEquals(BookSpreadMode, selectedMode(r))
    }

    @Test
    fun bookSpreadPlusElevenPercentSelectsIdCard() {
        val r = ModeSelector.select(BookSpreadMode.targetAspect * 1.11)
        assertEquals(IdCardMode, selectedMode(r))
    }

    @Test
    fun bookSpreadMinusElevenPercentStillSelectsBookSpread() {
        val r = ModeSelector.select(BookSpreadMode.targetAspect * 0.89)
        assertEquals(BookSpreadMode, selectedMode(r))
    }

    // ------------------------------------------------ +/-13 percent

    @Test
    fun idCardPlusThirteenPercentSelectsUsCard() {
        val r = ModeSelector.select(IdCardMode.targetAspect * 1.13)
        assertEquals(usCard, selectedMode(r))
    }

    @Test
    fun idCardMinusThirteenPercentSelectsBookSpread() {
        val r = ModeSelector.select(IdCardMode.targetAspect * 0.87)
        assertEquals(BookSpreadMode, selectedMode(r))
    }

    @Test
    fun usCardPlusThirteenPercentHasNoMatch() {
        val r = ModeSelector.select(usCard.targetAspect * 1.13)
        assertEquals(ModeRejectionReason.NO_MATCH, rejection(r))
    }

    @Test
    fun usCardMinusThirteenPercentSelectsEuCard() {
        val r = ModeSelector.select(usCard.targetAspect * 0.87)
        assertEquals(euCard, selectedMode(r))
    }

    @Test
    fun euCardPlusThirteenPercentSelectsUsCard() {
        val r = ModeSelector.select(euCard.targetAspect * 1.13)
        assertEquals(usCard, selectedMode(r))
    }

    @Test
    fun euCardMinusThirteenPercentSelectsBookSpread() {
        val r = ModeSelector.select(euCard.targetAspect * 0.87)
        assertEquals(BookSpreadMode, selectedMode(r))
    }

    @Test
    fun bookSpreadPlusThirteenPercentSelectsIdCard() {
        val r = ModeSelector.select(BookSpreadMode.targetAspect * 1.13)
        assertEquals(IdCardMode, selectedMode(r))
    }

    @Test
    fun bookSpreadMinusThirteenPercentHasNoMatch() {
        val r = ModeSelector.select(BookSpreadMode.targetAspect * 0.87)
        assertEquals(ModeRejectionReason.NO_MATCH, rejection(r))
    }

    // ------------------------------------------------ +/-12 percent boundary

    @Test
    fun usCardPlusTwelvePercentLandsJustOutsideTolerance() {
        // dev = 0.12000000000000009 in Double arithmetic: deterministic
        // right-side boundary behaviour (outside).
        val r = ModeSelector.select(usCard.targetAspect * 1.12)
        assertEquals(ModeRejectionReason.NO_MATCH, rejection(r))
    }

    @Test
    fun bookSpreadMinusTwelvePercentLandsJustInsideTolerance() {
        // dev = 0.11999999999999998: the inclusive boundary side, with
        // confidence effectively 0.0.
        val r = ModeSelector.select(BookSpreadMode.targetAspect * 0.88)
        assertEquals(BookSpreadMode, selectedMode(r))
        val conf = (r as ModeSelectionResult.Selected).confidence
        assertTrue("confidence near 0: " + conf, conf < 1e-9)
    }

    // ------------------------------------------------ ambiguity + tie-break

    @Test
    fun equidistantAspectBetweenIdCardAndEuCardIsAmbiguous() {
        val a = IdCardMode.targetAspect
        val b = euCard.targetAspect
        val tie = 2.0 * a * b / (a + b)
        val r = ModeSelector.select(tie)
        assertEquals(ModeRejectionReason.AMBIGUOUS, rejection(r))
    }

    @Test
    fun ambiguousResultCarriesTiedCandidatesInCatalogOrder() {
        val a = IdCardMode.targetAspect
        val b = euCard.targetAspect
        val tie = 2.0 * a * b / (a + b)
        val r = ModeSelector.select(tie)
        val rejected = r as ModeSelectionResult.Rejected
        assertEquals(listOf<ScanMode>(IdCardMode, euCard), rejected.candidates)
    }

    @Test
    fun explicitChoiceResolvesAmbiguityWithoutTieBreakInsideMachine() {
        val a = IdCardMode.targetAspect
        val b = euCard.targetAspect
        val tie = 2.0 * a * b / (a + b)
        val r = ModeSelector.select(tie, explicitChoice = IdCardMode)
        assertEquals(IdCardMode, selectedMode(r))
    }

    // ------------------------------------------------ confidence spot values

    @Test
    fun confidenceIsHalfAtSixPercentDeviation() {
        val r = ModeSelector.select(usCard.targetAspect * 1.06)
        val conf = (r as ModeSelectionResult.Selected).confidence
        assertEquals(0.5, conf, 1e-9)
    }

    @Test
    fun confidenceIsOneTwelfthAtElevenPercentDeviation() {
        val r = ModeSelector.select(usCard.targetAspect * 1.11)
        val conf = (r as ModeSelectionResult.Selected).confidence
        assertEquals(1.0 / 12.0, conf, 1e-9)
    }

    @Test
    fun confidenceClampedInsideUnitInterval() {
        for (mode in ScanModeCatalog.modes) {
            val r = ModeSelector.select(mode.targetAspect * 1.05)
            val conf = (r as ModeSelectionResult.Selected).confidence
            assertTrue("confidence >= 0: " + conf, conf >= 0.0)
            assertTrue("confidence <= 1: " + conf, conf <= 1.0)
        }
    }

    @Test
    fun relativeDeviationFormulaSpotValue() {
        val dev = ModeSelector.relativeDeviation(1.75, IdCardMode.targetAspect)
        assertEquals(kotlin.math.abs(1.75 - IdCardMode.targetAspect) / IdCardMode.targetAspect, dev, 0.0)
    }

    // ------------------------------------------------ degenerate quads

    @Test
    fun threePointsIsDegenerateQuad() {
        val r = ModeSelector.select(
            listOf(
                ModePoint(0.0, 0.0),
                ModePoint(10.0, 0.0),
                ModePoint(10.0, 10.0),
            )
        )
        assertEquals(ModeRejectionReason.DEGENERATE_QUAD, rejection(r))
    }

    @Test
    fun duplicatePointsIsDegenerateQuad() {
        val r = ModeSelector.select(
            listOf(
                ModePoint(0.0, 0.0),
                ModePoint(0.0, 0.0),
                ModePoint(10.0, 10.0),
                ModePoint(0.0, 10.0),
            )
        )
        assertEquals(ModeRejectionReason.DEGENERATE_QUAD, rejection(r))
    }

    @Test
    fun collinearPointsIsDegenerateQuad() {
        val r = ModeSelector.select(
            listOf(
                ModePoint(0.0, 0.0),
                ModePoint(2.0, 0.0),
                ModePoint(4.0, 0.0),
                ModePoint(6.0, 0.0),
            )
        )
        assertEquals(ModeRejectionReason.DEGENERATE_QUAD, rejection(r))
    }

    @Test
    fun fivePointsIsDegenerateQuad() {
        val r = ModeSelector.select(
            listOf(
                ModePoint(0.0, 0.0),
                ModePoint(10.0, 0.0),
                ModePoint(10.0, 10.0),
                ModePoint(0.0, 10.0),
                ModePoint(5.0, 5.0),
            )
        )
        assertEquals(ModeRejectionReason.DEGENERATE_QUAD, rejection(r))
    }

    // ------------------------------------------------ quad + direct-aspect paths

    @Test
    fun idCardSizedQuadSelectsIdCardFromBoundingBoxAspect() {
        val quad = ModeQuad(
            ModePoint(0.0, 0.0),
            ModePoint(85.6, 0.0),
            ModePoint(85.6, 53.98),
            ModePoint(0.0, 53.98),
        )
        val r = ModeSelector.select(quad)
        assertEquals(IdCardMode, selectedMode(r))
        assertTrue((r as ModeSelectionResult.Selected).confidence == 1.0)
    }

    @Test
    fun shuffledQuadOrderCanonicalizesToSameSelection() {
        val r = ModeSelector.select(
            listOf(
                ModePoint(85.6, 53.98),
                ModePoint(0.0, 53.98),
                ModePoint(0.0, 0.0),
                ModePoint(85.6, 0.0),
            )
        )
        assertEquals(IdCardMode, selectedMode(r))
    }

    @Test
    fun zeroAspectHasNoMatch() {
        assertEquals(ModeRejectionReason.NO_MATCH, rejection(ModeSelector.select(0.0)))
    }

    @Test
    fun negativeAspectHasNoMatch() {
        assertEquals(ModeRejectionReason.NO_MATCH, rejection(ModeSelector.select(-1.585)))
    }

    @Test
    fun nanAspectHasNoMatch() {
        assertEquals(ModeRejectionReason.NO_MATCH, rejection(ModeSelector.select(Double.NaN)))
    }

    @Test
    fun infiniteAspectHasNoMatch() {
        assertEquals(
            ModeRejectionReason.NO_MATCH,
            rejection(ModeSelector.select(Double.POSITIVE_INFINITY)),
        )
    }

    // ------------------------------------------------ explicit user choice

    @Test
    fun explicitChoiceWithinToleranceWinsOverCloserNeighbor() {
        // Auto matching would pick the US card (closer), but the user
        // pinned the ID-card mode and it is within ITS tolerance.
        val r = ModeSelector.select(
            IdCardMode.targetAspect * 1.11,
            explicitChoice = IdCardMode,
        )
        assertEquals(IdCardMode, selectedMode(r))
    }

    @Test
    fun explicitChoiceOutsideToleranceIsRejected() {
        val r = ModeSelector.select(
            IdCardMode.targetAspect * 1.13,
            explicitChoice = IdCardMode,
        )
        assertEquals(ModeRejectionReason.ASPECT_OUT_OF_TOLERANCE, rejection(r))
    }

    @Test
    fun explicitChoiceOutsideToleranceWhileAutoWouldMatch() {
        val r = ModeSelector.select(
            IdCardMode.targetAspect * 1.13,
            explicitChoice = BookSpreadMode,
        )
        // Far outside the spread's own tolerance even though an automatic
        // match exists (the US card).
        assertEquals(ModeRejectionReason.ASPECT_OUT_OF_TOLERANCE, rejection(r))
    }

    @Test
    fun degenerateQuadBeatsExplicitChoice() {
        val r = ModeSelector.select(
            listOf(
                ModePoint(0.0, 0.0),
                ModePoint(1.0, 0.0),
                ModePoint(2.0, 0.0),
                ModePoint(3.0, 0.0),
            ),
            explicitChoice = IdCardMode,
        )
        assertEquals(ModeRejectionReason.DEGENERATE_QUAD, rejection(r))
    }

    // ------------------------------------------------ determinism

    @Test
    fun selectionIsDeterministicAcrossCalls() {
        val aspect = 1.65
        assertEquals(ModeSelector.select(aspect), ModeSelector.select(aspect))
        val quad = listOf(
            ModePoint(2.0, 3.0),
            ModePoint(302.0, 5.0),
            ModePoint(298.0, 203.0),
            ModePoint(4.0, 200.0),
        )
        assertEquals(ModeSelector.select(quad), ModeSelector.select(quad))
    }
}
