package org.payswap.camscan.tools.modes

import kotlin.math.floor

// CAMSCAN-PROD-012 §6.3 — pure guidance-frame geometry. Given a mode's
// target aspect, the preview viewport and the current observed quad, the
// guidance frame is the largest centered rect with the mode aspect that
// fits the viewport shrunk by a documented margin fraction. Per-corner
// deltas are guidance corner minus observed quad corner (pure offsets).
// All arithmetic is Double; OUTPUT values are rounded half-up at exactly
// 0.5 px (ties toward positive infinity) through [roundHalfUp] — internal
// math stays unrounded. Deterministic; never throws (invalid inputs yield
// null).

/**
 * Rounds a pixel-scale Double half-up at 0.5 (ties toward positive
 * infinity: 0.5 -> 1.0, 2.5 -> 3.0, -0.5 -> 0.0, 1.4 -> 1.0). Documented
 * output rounding for guidance geometry; not a general-purpose rounding
 * primitive (Double addition edge cases beyond pixel scale are out of
 * scope).
 */
fun roundHalfUp(value: Double): Double = floor(value + 0.5)

/** The on-preview target rect the user should fill (exact Doubles). */
data class GuidanceFrame(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    // Output rounding (round-half-up at 0.5 px) — exact Doubles stay on
    // the unrounded fields.
    val roundedX: Double get() = roundHalfUp(x)
    val roundedY: Double get() = roundHalfUp(y)
    val roundedWidth: Double get() = roundHalfUp(width)
    val roundedHeight: Double get() = roundHalfUp(height)

    /** Corners in canonical order TL, TR, BR, BL (y-down). */
    val corners: List<ModePoint>
        get() = listOf(
            ModePoint(x, y),
            ModePoint(x + width, y),
            ModePoint(x + width, y + height),
            ModePoint(x, y + height),
        )
}

/**
 * One corner's guidance delta: guidance corner minus observed quad corner
 * (dx, dy). Sign convention (y-down): positive dx means the observed
 * corner lies LEFT of the guidance corner; positive dy means the observed
 * corner lies ABOVE the guidance corner. Moving the observed corner by
 * (dx, dy) places it exactly on the guidance corner.
 */
data class CornerDelta(val dx: Double, val dy: Double) {
    val roundedDx: Double get() = roundHalfUp(dx)
    val roundedDy: Double get() = roundHalfUp(dy)
}

/**
 * Full guidance computation result: the target frame plus per-corner
 * deltas (list order matches [ModeQuad.corners] / [GuidanceFrame.corners]:
 * TL, TR, BR, BL).
 */
data class GuidanceResult(
    val frame: GuidanceFrame,
    val cornerDeltas: List<CornerDelta>,
)

/** Pure guidance geometry; no UI coupling (wave-4 renders these numbers). */
object GuidanceGeometry {

    /**
     * Default viewport margin fraction: 5 percent per side (the guidance
     * frame never touches the viewport edge; usable area is the viewport
     * shrunk by 2 * margin in each dimension).
     */
    const val DEFAULT_MARGIN_FRACTION = 0.05

    /**
     * Computes the guidance frame and per-corner deltas. Returns null
     * (never throws) when: targetAspect or viewport dimensions are
     * non-finite or non-positive; marginFraction is outside [0.0, 0.5);
     * or the observed quad is degenerate (|area| below the quad epsilon).
     *
     * Largest centered rect: inside the usable viewport (viewport shrunk
     * by the margin on both sides) the rect with the mode aspect takes
     * the full usable height when the usable area is wider than the
     * aspect, else the full usable width; the rect is then centered on
     * the viewport.
     */
    fun computeGuidance(
        targetAspect: Double,
        viewportWidth: Double,
        viewportHeight: Double,
        observedQuad: ModeQuad,
        marginFraction: Double = DEFAULT_MARGIN_FRACTION,
    ): GuidanceResult? {
        if (!targetAspect.isFinite() || targetAspect <= 0.0) return null
        if (!viewportWidth.isFinite() || viewportWidth <= 0.0) return null
        if (!viewportHeight.isFinite() || viewportHeight <= 0.0) return null
        if (marginFraction < 0.0 || marginFraction >= 0.5) return null
        if (kotlin.math.abs(observedQuad.signedArea()) < ModeQuad.AREA_EPSILON) {
            return null
        }
        val usableWidth = viewportWidth * (1.0 - 2.0 * marginFraction)
        val usableHeight = viewportHeight * (1.0 - 2.0 * marginFraction)
        if (usableWidth <= 0.0 || usableHeight <= 0.0) return null
        val rectWidth: Double
        val rectHeight: Double
        if (usableWidth / usableHeight > targetAspect) {
            rectHeight = usableHeight
            rectWidth = usableHeight * targetAspect
        } else {
            rectWidth = usableWidth
            rectHeight = usableWidth / targetAspect
        }
        val x = (viewportWidth - rectWidth) / 2.0
        val y = (viewportHeight - rectHeight) / 2.0
        val frame = GuidanceFrame(x, y, rectWidth, rectHeight)
        val guidanceCorners = frame.corners
        val observedCorners = observedQuad.corners
        val deltas = mutableListOf<CornerDelta>()
        for (i in guidanceCorners.indices) {
            val g = guidanceCorners[i]
            val o = observedCorners[i]
            deltas.add(CornerDelta(g.x - o.x, g.y - o.y))
        }
        return GuidanceResult(frame, deltas)
    }
}
