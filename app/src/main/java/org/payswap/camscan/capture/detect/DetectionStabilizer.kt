package org.payswap.camscan.capture.detect


/**
 * CAMSCAN-PROD-002 §6.3 — temporal stabilization of detections (docs/
 * SCAN-ENGINE-CONTRACT.md: `DetectionStabilizer.update(detection) ->
 * StableDetection?`). Pure Kotlin, no android.* imports.
 *
 * Agreement model: a run of consecutive detections whose quads agree —
 * per-corner distance <= [cornerTolerancePx] AND pairwise IoU >=
 * [iouThreshold] — becomes stable after [requiredAgreementFrames] members.
 * A quad that passes IoU but exceeds the corner tolerance is a *breach*
 * (jitter evidence), not an agreement.
 *
 * Hysteresis on loss: when a stable quad stops being supported (missed or
 * disagreeing frames), the last stable quad is held for [holdoutFrames]
 * updates before `null` is emitted — the UI must not flicker. During the
 * holdout, a detection agreeing with the held quad resumes stability
 * immediately.
 *
 * MOTION_UNSTABLE: surfaced (never a reject) on stable emissions once more
 * than one corner-tolerance breach accumulated in the current stable window
 * (the window spans holdout/resume cycles attached to the same stable quad).
 *
 * Discipline follows PROD-001's CameraStateMachine: never throws, every
 * transition is legal or ignored-with-diagnostic, constructor parameters
 * outside legal ranges are clamped with a diagnostic, and the whole machine
 * is deterministic — the same update sequence produces the same emissions.
 *
 * A malformed detection (not exactly 4 corners) is ignored as a miss, with a
 * diagnostic — a bad input degrades to "no evidence", never to a crash.
 */
class DetectionStabilizer(
    requiredAgreementFrames: Int = DEFAULT_REQUIRED_AGREEMENT_FRAMES,
    cornerTolerancePx: Double = DEFAULT_CORNER_TOLERANCE_PX,
    iouThreshold: Double = DEFAULT_IOU_THRESHOLD,
    holdoutFrames: Int = DEFAULT_HOLDOUT_FRAMES,
) {

    private enum class Phase { SEARCHING, STABLE, HOLDOUT }

    private enum class Agreement { AGREE, CORNER_BREACH, DISAGREE }

    /** Clamped/validated constructor parameters (see class KDoc). */
    private val required: Int
    private val cornerTolerance: Double

    // Named `iouGate` (not `iouThreshold`) — the constructor parameter keeps
    // the contract name for call sites; a property with the identical name
    // would shadow-collide inside the init block.
    private val iouGate: Double
    private val holdout: Int

    private val diagnosticsInternal = mutableListOf<String>()

    /** Chronological record of ignored/interesting transitions; diagnostics only. */
    val diagnostics: List<String>
        get() = diagnosticsInternal.toList()

    private var phase = Phase.SEARCHING

    /** The most recent detection — the agreement reference while a run is open. */
    private var previous: DocumentDetection? = null

    private var runLength = 0

    private var lastStable: StableDetection? = null

    /** Run length frozen when the current holdout began (resume continues from it). */
    private var holdoutRunLength = 0

    private var holdoutRemaining = 0

    /** Corner-tolerance breaches in the current stable window (reset when stability is re-earned from SEARCHING). */
    private var toleranceBreaches = 0

    init {
        var clamped = false
        val requiredClamped = requiredAgreementFrames.coerceAtLeast(1)
        if (requiredClamped != requiredAgreementFrames) clamped = true
        val cornerClamped = when {
            cornerTolerancePx.isNaN() || cornerTolerancePx <= 0.0 -> DEFAULT_CORNER_TOLERANCE_PX
            else -> cornerTolerancePx
        }
        if (cornerClamped != cornerTolerancePx) clamped = true
        val iouClamped = when {
            iouThreshold.isNaN() -> DEFAULT_IOU_THRESHOLD
            else -> iouThreshold.coerceIn(0.01, 1.0)
        }
        if (iouClamped != iouThreshold) clamped = true
        val holdoutClamped = holdoutFrames.coerceAtLeast(0)
        if (holdoutClamped != holdoutFrames) clamped = true
        required = requiredClamped
        cornerTolerance = cornerClamped
        iouGate = iouClamped
        holdout = holdoutClamped
        if (clamped) {
            diagnosticsInternal.add(
                "DetectionStabilizer: constructor parameters clamped to legal ranges " +
                    "(required=$required, cornerTolerance=$cornerTolerance, iou=$iouThreshold, holdout=$holdout)",
            )
        }
    }

    /**
     * Feeds one detector result in and returns the stable quad the UI may
     * trust, the held (hysteresis) quad, or `null` after the holdout expired.
     */
    fun update(detection: DocumentDetection?): StableDetection? {
        val candidate = if (detection != null && detection.corners.size != CORNER_COUNT) {
            diagnosticsInternal.add(
                "DetectionStabilizer: malformed detection (corners=${detection.corners.size}) ignored as a miss",
            )
            null
        } else {
            detection
        }
        return when (phase) {
            Phase.SEARCHING -> updateSearching(candidate)
            Phase.STABLE -> updateStable(candidate)
            Phase.HOLDOUT -> updateHoldout(candidate)
        }
    }

    // ------------------------------------------------------------------ phases

    private fun updateSearching(detection: DocumentDetection?): StableDetection? {
        if (detection == null) {
            previous = null
            runLength = 0
            return null
        }
        val anchor = previous
        if (anchor == null) {
            previous = detection
            runLength = 1
            if (runLength >= required) {
                return promoteToStable(detection)
            }
            return null
        }
        return when (agreement(detection.corners, anchor.corners)) {
            Agreement.AGREE -> {
                previous = detection
                runLength += 1
                if (runLength >= required) {
                    promoteToStable(detection)
                } else {
                    null
                }
            }

            else -> {
                // Disagreement (or corner breach) resets the run; the new
                // detection seeds the next run.
                diagnosticsInternal.add("DetectionStabilizer: agreement run reset at frame ${detection.frameTimestampMs}")
                previous = detection
                runLength = 1
                if (runLength >= required) {
                    promoteToStable(detection)
                } else {
                    null
                }
            }
        }
    }

    private fun updateStable(detection: DocumentDetection?): StableDetection? {
        if (detection == null) {
            enterHoldout("missed frame")
            return holdoutStep()
        }
        val reference = previous
        if (reference == null) {
            // Defensive: STABLE always has a previous detection; if state was
            // corrupted, re-earn stability instead of crashing.
            diagnosticsInternal.add("DetectionStabilizer: stable phase without reference; re-searching")
            phase = Phase.SEARCHING
            return updateSearching(detection)
        }
        return when (agreement(detection.corners, reference.corners)) {
            Agreement.AGREE -> {
                previous = detection
                runLength += 1
                lastStable = stableOf(detection, runLength)
                lastStable
            }

            else -> {
                if (agreement(detection.corners, reference.corners) == Agreement.CORNER_BREACH) {
                    toleranceBreaches += 1
                }
                enterHoldout("disagreeing frame at ${detection.frameTimestampMs}")
                holdoutStep()
            }
        }
    }

    private fun updateHoldout(detection: DocumentDetection?): StableDetection? {
        val heldQuad = lastStable?.corners
        if (detection != null && heldQuad != null) {
            when (agreement(detection.corners, heldQuad)) {
                Agreement.AGREE -> {
                    // Strong evidence the page is still there: resume stability.
                    phase = Phase.STABLE
                    runLength = holdoutRunLength + 1
                    previous = detection
                    lastStable = stableOf(detection, runLength)
                    diagnosticsInternal.add(
                        "DetectionStabilizer: resumed after holdout (run=$runLength)",
                    )
                    return lastStable
                }

                Agreement.CORNER_BREACH -> toleranceBreaches += 1

                Agreement.DISAGREE -> Unit
            }
        }
        return holdoutStep()
    }

    // ------------------------------------------------------------------ helpers

    private fun holdoutStep(): StableDetection? {
        if (holdoutRemaining > 0) {
            holdoutRemaining -= 1
            return held()
        }
        // Holdout expired: stable detection lost.
        phase = Phase.SEARCHING
        lastStable = null
        toleranceBreaches = 0
        runLength = if (previous != null) 1 else 0
        diagnosticsInternal.add("DetectionStabilizer: holdout expired; stable detection lost")
        return null
    }

    private fun enterHoldout(reason: String) {
        phase = Phase.HOLDOUT
        holdoutRemaining = holdout
        holdoutRunLength = runLength
        diagnosticsInternal.add("DetectionStabilizer: holdout entered ($reason)")
    }

    private fun promoteToStable(detection: DocumentDetection): StableDetection {
        phase = Phase.STABLE
        toleranceBreaches = 0
        runLength = maxOf(runLength, 1)
        val stable = stableOf(detection, runLength)
        lastStable = stable
        diagnosticsInternal.add("DetectionStabilizer: stable after $runLength agreeing frames")
        return stable
    }

    /** The held emission during holdout: last stable quad with frozen run length. */
    private fun held(): StableDetection {
        val base = requireNotNull(lastStable) { "holdout requires a last stable detection" }
        val flags = base.qualityFlags.toMutableSet()
        if (toleranceBreaches > MOTION_BREACH_LIMIT) {
            flags.add(DetectionQualityFlag.MOTION_UNSTABLE)
        }
        return base.copy(qualityFlags = flags)
    }

    private fun stableOf(detection: DocumentDetection, frames: Int): StableDetection {
        val flags = detection.qualityFlags.toMutableSet()
        if (toleranceBreaches > MOTION_BREACH_LIMIT) {
            flags.add(DetectionQualityFlag.MOTION_UNSTABLE)
        }
        return StableDetection(
            corners = detection.corners,
            confidence = detection.confidence,
            stableForFrames = frames,
            qualityFlags = flags,
        )
    }

    private fun agreement(quad: List<Corner>, reference: List<Corner>): Agreement {
        val iou = QuadGeometry.iou(quad, reference)
        val cornersWithinTolerance = QuadGeometry.cornerDistances(quad, reference)
            .all { it <= cornerTolerance }
        return when {
            cornersWithinTolerance && iou >= iouGate -> Agreement.AGREE
            !cornersWithinTolerance && iou >= iouGate -> Agreement.CORNER_BREACH
            else -> Agreement.DISAGREE
        }
    }

    private companion object {
        private const val CORNER_COUNT = 4

        /** "more than once in the window" — the contract's MOTION_UNSTABLE wording. */
        private const val MOTION_BREACH_LIMIT = 1

        private const val DEFAULT_REQUIRED_AGREEMENT_FRAMES = 3
        private const val DEFAULT_CORNER_TOLERANCE_PX = 24.0
        private const val DEFAULT_IOU_THRESHOLD = 0.85
        private const val DEFAULT_HOLDOUT_FRAMES = 5
    }
}
