package org.payswap.camscan.capture.detect


/*
 * CAMSCAN-PROD-002 §6.1 — the detection domain types (pure Kotlin, no
 * android.* imports so §6.7 JVM tests compile and run anywhere).
 *
 *  - Corner — a corner in frame (pixel) coordinates;
 *  - DetectionQualityFlag — the contract's degrade/reject vocabulary;
 *  - DocumentDetection — one detector result for one frame;
 *  - StableDetection — the temporally-agreed quad the UI may trust.
 */

/**
 * Ordered corner in frame (pixel) coordinates.
 *
 * Deliberately frame-space Int (not the normalized Float
 * `core.model.Corner` used by the durable Document model): detection works in
 * analysis-frame pixels; converting to normalized source-image coordinates is
 * the perspective/geometry work order's concern (PROD-003), which will map
 * detection quads onto captures at capture resolution.
 */
data class Corner(val x: Int, val y: Int)


/**
 * The scan-engine contract's degrade/reject vocabulary
 * (docs/SCAN-ENGINE-CONTRACT.md, Detection section).
 *
 * Semantics in this implementation:
 *  - [NO_PAGE] — a quad hypothesis exists but does not qualify as a page
 *    (far below the minimum page area). A fully blank/uniform frame with no
 *    structure at all yields `null` from the detector instead.
 *  - [PARTIAL_PAGE] — the quad touches the frame border; the page continues
 *    outside the visible frame.
 *  - [BLUR] — the page edges lack the sharpness of an in-focus document.
 *  - [GLARE] — a significant saturated region washes out part of the frame.
 *  - [LOW_CONTRAST] — the frame's page/background contrast is too weak.
 *  - [MOTION_UNSTABLE] — never set by the detector; only [DetectionStabilizer]
 *    surfaces it on a stable quad whose supporting detections exceeded the
 *    corner tolerance more than once in the stable window.
 */
enum class DetectionQualityFlag { NO_PAGE, PARTIAL_PAGE, BLUR, GLARE, LOW_CONTRAST, MOTION_UNSTABLE }


/**
 * One [DocumentDetector] result for one analyzed frame.
 *
 * @param corners four ordered corners TL, TR, BR, BL in frame coordinates
 *   (canonical order via [QuadGeometry.orderCorners]; index-aligned pairwise
 *   comparison is legal — the stabilizer relies on it).
 * @param confidence detection confidence in [0, 1] (edge support + quad
 *   regularity + page-area sanity; see EdgeQuadDetector).
 * @param qualityFlags evidence-based degrade/reject flags; a flagged detection
 *   is still returned when a quad exists — `null` is reserved for
 *   "nothing qualifies at all".
 * @param frameTimestampMs analysis time in epoch millis, always sourced
 *   through [org.payswap.camscan.core.time.TimeSource], never System directly.
 */
data class DocumentDetection(
    val corners: List<Corner>,
    val confidence: Float,
    val qualityFlags: Set<DetectionQualityFlag>,
    val frameTimestampMs: Long,
)


/**
 * The temporally-agreed quad the UI may trust — emitted by
 * [DetectionStabilizer] only after the required run of consecutive agreeing
 * detections, held (hysteresis) across short disagreement gaps so the overlay
 * does not flicker.
 *
 * @param corners four ordered corners TL, TR, BR, BL in frame coordinates.
 * @param confidence confidence of the latest supporting detection.
 * @param stableForFrames how many consecutive agreeing detections support
 *   this quad (frozen at the value reached when a holdout began).
 * @param qualityFlags the supporting detection's flags, plus
 *   [DetectionQualityFlag.MOTION_UNSTABLE] when corner-tolerance breaches
 *   accumulated in the current stable window.
 */
data class StableDetection(
    val corners: List<Corner>,
    val confidence: Float,
    val stableForFrames: Int,
    val qualityFlags: Set<DetectionQualityFlag>,
)
