package org.payswap.camscan.tools.ui

import org.payswap.camscan.tools.watermark.WatermarkSpec

// WatermarkDraftUi (CAMSCAN-VERIFY-001): pure draft state for the watermark
// composer. UI fields (text, diagonal toggle, strength percent) map onto the
// frozen WatermarkSpec vocabulary: diagonal = 30 degree tile rotation, tile
// = 0 degrees, strength percent = source alpha 0..255. Android-free.

/** Mutable draft of one watermark. */
class WatermarkDraftUi(
    var text: String = "",
    var diagonal: Boolean = true,
    private var strengthPercentValue: Int = DEFAULT_STRENGTH_PERCENT,
) {

    /** Rotation for the current layout mode: 30 degrees diagonal, 0 tile. */
    fun rotationDegrees(): Int = if (diagonal) DIAGONAL_ROTATION_DEGREES else TILE_ROTATION_DEGREES

    /** Strength percent, clamped into 0..100. */
    fun strengthPercent(): Int =
        Math.max(0, Math.min(strengthPercentValue, MAX_STRENGTH_PERCENT))

    /** Sets the strength percent (out-of-range values are clamped). */
    fun setStrengthPercent(percent: Int) {
        strengthPercentValue = percent
    }

    /** Source alpha for the spec: round_half_up(percent * 255 / 100). */
    fun opacity(): Int = Math.round(strengthPercent() * 255 / 100.0).toInt()

    /** The engine spec for the current draft (text trimmed). */
    fun spec(): WatermarkSpec = WatermarkSpec(
        text = text.trim(),
        opacity = opacity(),
        rotationDegrees = rotationDegrees(),
        colorArgb = COLOR,
        tileSpacingPx = DEFAULT_TILE_SPACING_PX,
    )

    /** True when the draft can be applied: non-blank text and ink strength. */
    fun canApply(): Boolean = text.isNotBlank() && strengthPercent() > 0

    companion object {
        const val DIAGONAL_ROTATION_DEGREES: Int = 30
        const val TILE_ROTATION_DEGREES: Int = 0
        const val DEFAULT_STRENGTH_PERCENT: Int = 30
        const val DEFAULT_TILE_SPACING_PX: Int = 48
        const val MAX_STRENGTH_PERCENT: Int = 100

        /** Neutral gray watermark ink. */
        const val COLOR: Long = 0xFF808080L
    }
}
