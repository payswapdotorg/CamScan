package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.watermark.WatermarkSpec

// WatermarkDraftUiTest (CAMSCAN-VERIFY-001): UI-field to engine-spec
// mapping (diagonal/tile rotation, strength to opacity, trimmed text) and
// apply gating.

class WatermarkDraftUiTest {

    @Test
    fun diagonalVsTile_controlsTheTileRotation() {
        val draft = WatermarkDraftUi()
        assertTrue(draft.diagonal)
        assertEquals(30, draft.rotationDegrees())
        draft.diagonal = false
        assertEquals(0, draft.rotationDegrees())
    }

    @Test
    fun strengthPercent_mapsToOpacityWithRoundHalfUp() {
        val draft = WatermarkDraftUi()
        draft.setStrengthPercent(30)
        assertEquals(77, draft.opacity())
        draft.setStrengthPercent(100)
        assertEquals(255, draft.opacity())
        draft.setStrengthPercent(0)
        assertEquals(0, draft.opacity())
        draft.setStrengthPercent(50)
        assertEquals(128, draft.opacity())
    }

    @Test
    fun outOfRangeStrength_isClamped() {
        val draft = WatermarkDraftUi()
        draft.setStrengthPercent(150)
        assertEquals(100, draft.strengthPercent())
        draft.setStrengthPercent(-5)
        assertEquals(0, draft.strengthPercent())
    }

    @Test
    fun spec_carriesTheDraftFieldsWithTrimmedText() {
        val draft = WatermarkDraftUi()
        draft.text = "  DRAFT  "
        draft.setStrengthPercent(40)
        val spec: WatermarkSpec = draft.spec()
        assertEquals("DRAFT", spec.text)
        assertEquals(102, spec.opacity)
        assertEquals(30, spec.rotationDegrees)
        assertEquals(WatermarkDraftUi.COLOR, spec.colorArgb)
        assertEquals(WatermarkDraftUi.DEFAULT_TILE_SPACING_PX, spec.tileSpacingPx)
    }

    @Test
    fun canApply_requiresTextAndStrength() {
        val draft = WatermarkDraftUi()
        assertFalse(draft.canApply())
        draft.text = "DRAFT"
        assertTrue(draft.canApply())
        draft.setStrengthPercent(0)
        assertFalse(draft.canApply())
        draft.setStrengthPercent(30)
        draft.text = "   "
        assertFalse(draft.canApply())
    }
}
