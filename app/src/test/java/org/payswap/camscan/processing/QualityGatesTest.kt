package org.payswap.camscan.processing


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * CAMSCAN-PROD-003 §6.7 — QualityGates coverage: sharp/flat fixtures with
 * hand-computed normalized-Laplacian-RMS values, blown-highlight and
 * dark-fraction fixtures with exact fractions, ctor threshold overrides,
 * determinism, and the malformed-input worst-case discipline.
 */
class QualityGatesTest {

    private val gates = QualityGates()

    // ---------------------------------------------------------- helpers

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** 8x8 mid-tone checkerboard: (x + y) even -> 100, odd -> 160. */
    private fun checkerboard8x8(): ImageBuffer {
        val pixels = IntArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                pixels[y * 8 + x] = gray(if ((x + y) % 2 == 0) 100 else 160)
            }
        }
        return ImageBuffer(8, 8, pixels)
    }

    private fun flat8x8(value: Int): ImageBuffer =
        ImageBuffer(8, 8, IntArray(64) { gray(value) })

    /**
     * 8x8 mid-gray (128) with a patch of [count] pixels set to [value] in
     * the top-left corner block.
     */
    private fun patch8x8(value: Int, count: Int): ImageBuffer {
        val pixels = IntArray(64) { gray(128) }
        for (i in 0 until count) {
            pixels[i] = gray(value)
        }
        return ImageBuffer(8, 8, pixels)
    }

    // ---------------------------------------------------------- sharpness

    @Test
    fun highFrequencyMidToneImageIsSharpWithGoodContrast() {
        val quality = gates.evaluate(checkerboard8x8())
        assertEquals(setOf(QualityFlag.SHARP, QualityFlag.GOOD_CONTRAST), quality.flags)
        // Laplacian responses (edge-clamped borders): interior pixels (36)
        // respond +-240; non-corner edge pixels (24) have ONE clamped
        // (self-colored) neighbor -> +-180; corners (4) have TWO -> +-120.
        // RMS = sqrt((36*240^2 + 24*180^2 + 4*120^2)/64) = sqrt(45450),
        // normalized /255.
        assertEquals(0.83604, quality.sharpness, 1e-4)
        // Spread = (160 - 100) / 255.
        assertEquals(60.0 / 255.0, quality.contrastSpread, 1e-12)
        assertEquals(0.0, quality.blownFraction, 0.0)
        assertEquals(0.0, quality.darkFraction, 0.0)
    }

    @Test
    fun flatImageIsBlurredAndLowContrast() {
        val quality = gates.evaluate(flat8x8(128))
        assertEquals(setOf(QualityFlag.BLURRED, QualityFlag.LOW_CONTRAST), quality.flags)
        assertEquals(0.0, quality.sharpness, 0.0)
        assertEquals(0.0, quality.contrastSpread, 0.0)
    }

    // ------------------------------------------------- glare / darkness

    @Test
    fun blownHighlightsRaiseGlare() {
        // 8/64 = 12.5% at luma 255 (>= 250) > 10% default.
        val quality = gates.evaluate(patch8x8(value = 255, count = 8))
        assertTrue(quality.flags.contains(QualityFlag.GLARE))
        assertFalse(quality.flags.contains(QualityFlag.TOO_DARK))
        assertEquals(0.125, quality.blownFraction, 0.0)
    }

    @Test
    fun darkPatchRaisesTooDark() {
        // 20/64 = 31.25% at luma 0 (<= 5) > 25% default.
        val quality = gates.evaluate(patch8x8(value = 0, count = 20))
        assertTrue(quality.flags.contains(QualityFlag.TOO_DARK))
        assertFalse(quality.flags.contains(QualityFlag.GLARE))
        assertEquals(0.3125, quality.darkFraction, 0.0)
    }

    @Test
    fun mildDefectsStayBelowDefaultThresholds() {
        // 8 dark pixels = 12.5% <= 25% default: no TOO_DARK.
        assertFalse(gates.evaluate(patch8x8(value = 0, count = 8)).flags.contains(QualityFlag.TOO_DARK))
        // 6 blown pixels = 9.375% <= 10% default: no GLARE.
        assertFalse(gates.evaluate(patch8x8(value = 255, count = 6)).flags.contains(QualityFlag.GLARE))
    }

    // ------------------------------------------------------ ctor config

    @Test
    fun defaultThresholdsMatchTheDocumentedConstants() {
        assertEquals(QualityGates.DEFAULT_SHARPNESS_THRESHOLD, gates.sharpnessThreshold, 0.0)
        assertEquals(QualityGates.DEFAULT_CONTRAST_THRESHOLD, gates.contrastThreshold, 0.0)
        assertEquals(QualityGates.DEFAULT_GLARE_FRACTION, gates.glareFractionThreshold, 0.0)
        assertEquals(QualityGates.DEFAULT_DARK_FRACTION, gates.darkFractionThreshold, 0.0)
    }

    @Test
    fun thresholdOverridesFlipFlags() {
        val checker = checkerboard8x8()
        assertTrue(
            QualityGates(sharpnessThreshold = 10.0).evaluate(checker).flags
                .contains(QualityFlag.BLURRED),
        )
        assertTrue(
            QualityGates(contrastThreshold = 2.0).evaluate(checker).flags
                .contains(QualityFlag.LOW_CONTRAST),
        )
        val glareImage = patch8x8(value = 255, count = 8)
        assertFalse(
            QualityGates(glareFractionThreshold = 1.0).evaluate(glareImage).flags
                .contains(QualityFlag.GLARE),
        )
        val mildDark = patch8x8(value = 0, count = 8)
        assertTrue(
            QualityGates(darkFractionThreshold = 0.05).evaluate(mildDark).flags
                .contains(QualityFlag.TOO_DARK),
        )
    }

    // ------------------------------------------ determinism / malformed

    @Test
    fun evaluationIsDeterministic() {
        val image = checkerboard8x8()
        assertEquals(gates.evaluate(image), gates.evaluate(image))
    }

    @Test
    fun malformedImageYieldsWorstCaseWithoutThrowing() {
        val malformed = ImageBuffer(2, 2, IntArray(3))
        val quality = gates.evaluate(malformed)
        assertEquals(setOf(QualityFlag.BLURRED, QualityFlag.LOW_CONTRAST), quality.flags)
        assertEquals(0.0, quality.sharpness, 0.0)
        assertEquals(0.0, quality.contrastSpread, 0.0)
    }
}
