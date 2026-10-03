package org.payswap.camscan.tools.modes

import kotlin.math.abs

// CAMSCAN-PROD-012 §6.2 — the deterministic mode-selection machine. Pure,
// side-effect free, never throws: malformed input yields Rejected results.
//
// Matching semantics (documented, frozen):
//   observed aspect  : quad width/height from the axis-aligned bounding
//                      box AFTER canonical edge ordering, or supplied
//                      directly through the aspect overload.
//   candidate        : |observed - target| / target <= tolerance (0.12).
//   confidence       : 1 - |observed - target| / (target * tolerance),
//                      clamped to [0, 1] — 1.0 at an exact aspect match,
//                      0.0 at the tolerance boundary.
//   selection        : among candidates the minimum relative deviation
//                      wins. An exact tie (best deviations within
//                      TIE_EPSILON of each other) is AMBIGUOUS and carries
//                      the tied candidates in catalog order — the
//                      deterministic tie-break consumers apply when
//                      resolving (first candidate in catalog order);
//                      the machine itself never silently resolves a tie.
//   explicit choice  : when the user pinned a mode, only that mode is
//                      validated; outside its tolerance the result is
//                      ASPECT_OUT_OF_TOLERANCE (not NO_MATCH).

/** Why a selection was rejected. */
enum class ModeRejectionReason {
    /** Explicit user choice, observed aspect outside the chosen mode's tolerance. */
    ASPECT_OUT_OF_TOLERANCE,

    /** Quad unusable: wrong point count, duplicate points, or collinear. */
    DEGENERATE_QUAD,

    /** Automatic matching: no catalog mode within tolerance. */
    NO_MATCH,

    /** Two or more modes tied within tolerance; candidates carried. */
    AMBIGUOUS,
}

/** Result of a mode selection: exactly one of Selected or Rejected. */
sealed class ModeSelectionResult {

    /** The chosen [mode] with matching confidence in [0.0, 1.0]. */
    data class Selected(val mode: ScanMode, val confidence: Double) :
        ModeSelectionResult()

    /**
     * Rejection with a [reason]. [candidates] is non-empty only for
     * [ModeRejectionReason.AMBIGUOUS], where it carries the tied modes in
     * catalog order (the documented deterministic tie-break order).
     */
    data class Rejected(
        val reason: ModeRejectionReason,
        val candidates: List<ScanMode> = emptyList(),
    ) : ModeSelectionResult()
}

/** Pure, deterministic selection machine over [ScanModeCatalog]. */
object ModeSelector {

    /**
     * Relative-deviation window inside which two best deviations count as
     * an exact tie (Double arithmetic noise is ~1e-16 relative; 1e-9 is
     * safely above noise and far below any meaningful aspect difference).
     */
    const val TIE_EPSILON = 1e-9

    /**
     * Selection from a directly observed aspect ratio (width / height).
     * Non-finite or non-positive aspects match nothing (NO_MATCH);
     * never throws.
     */
    fun select(
        observedAspect: Double,
        explicitChoice: ScanMode? = null,
    ): ModeSelectionResult {
        if (!observedAspect.isFinite() || observedAspect <= 0.0) {
            return ModeSelectionResult.Rejected(ModeRejectionReason.NO_MATCH)
        }
        return match(observedAspect, explicitChoice)
    }

    /**
     * Selection from a CANONICAL quad ([ModeQuad], TL/TR/BR/BL): the
     * observed aspect is the bounding-box width/height after edge
     * ordering.
     */
    fun select(
        quad: ModeQuad,
        explicitChoice: ScanMode? = null,
    ): ModeSelectionResult {
        if (abs(quad.signedArea()) < ModeQuad.AREA_EPSILON) {
            return ModeSelectionResult.Rejected(
                ModeRejectionReason.DEGENERATE_QUAD,
            )
        }
        return select(quad.boundingBoxAspect(), explicitChoice)
    }

    /**
     * Selection from four observed points in ANY order: canonicalized
     * first (centroid-angle edge ordering); wrong point count, duplicate
     * points or a collinear set yield DEGENERATE_QUAD — never throws.
     */
    fun select(
        points: List<ModePoint>,
        explicitChoice: ScanMode? = null,
    ): ModeSelectionResult {
        val quad = ModeQuad.fromPoints(points)
        if (quad == null) {
            return ModeSelectionResult.Rejected(
                ModeRejectionReason.DEGENERATE_QUAD,
            )
        }
        return select(quad, explicitChoice)
    }

    /**
     * Relative deviation of an observed aspect from a target aspect:
     * |observed - target| / target. Public and pure so callers and tests
     * share the exact same arithmetic.
     */
    fun relativeDeviation(observedAspect: Double, targetAspect: Double): Double =
        abs(observedAspect - targetAspect) / targetAspect

    private fun match(
        aspect: Double,
        explicitChoice: ScanMode?,
    ): ModeSelectionResult {
        if (explicitChoice != null) {
            val dev = relativeDeviation(aspect, explicitChoice.targetAspect)
            if (dev <= explicitChoice.tolerance) {
                return selected(explicitChoice, dev)
            }
            return ModeSelectionResult.Rejected(
                ModeRejectionReason.ASPECT_OUT_OF_TOLERANCE,
            )
        }
        val candidates: MutableList<Pair<ScanMode, Double>> = mutableListOf()
        for (mode in ScanModeCatalog.modes) {
            val dev = relativeDeviation(aspect, mode.targetAspect)
            if (dev <= mode.tolerance) {
                candidates.add(mode to dev)
            }
        }
        if (candidates.isEmpty()) {
            return ModeSelectionResult.Rejected(ModeRejectionReason.NO_MATCH)
        }
        var bestDev = candidates[0].second
        for (pair in candidates) {
            if (pair.second < bestDev) bestDev = pair.second
        }
        val tied: MutableList<ScanMode> = mutableListOf()
        for (pair in candidates) {
            if (pair.second <= bestDev + TIE_EPSILON) tied.add(pair.first)
        }
        if (tied.size == 1) {
            return selected(tied[0], bestDev)
        }
        return ModeSelectionResult.Rejected(
            ModeRejectionReason.AMBIGUOUS,
            tied,
        )
    }

    private fun selected(mode: ScanMode, deviation: Double): ModeSelectionResult {
        val confidence = (1.0 - deviation / mode.tolerance).coerceIn(0.0, 1.0)
        return ModeSelectionResult.Selected(mode, confidence)
    }
}
