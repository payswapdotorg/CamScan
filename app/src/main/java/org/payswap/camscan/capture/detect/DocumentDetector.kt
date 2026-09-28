package org.payswap.camscan.capture.detect


/**
 * CAMSCAN-PROD-002 §6.2 — the detector seam
 * (docs/SCAN-ENGINE-CONTRACT.md: `DocumentDetector.detect(frame) ->
 * DocumentDetection?`; the seam name is contractual, implementations are
 * replaceable — pure interface, no android.* imports).
 *
 * Implementations MUST be deterministic: identical (frame, timestamp) inputs
 * produce an identical [DocumentDetection] — no randomness, no time-of-day,
 * no device state. Degraded conditions surface as quality flags, not
 * exceptions; `null` is reserved for "nothing qualifies at all".
 */
interface DocumentDetector {

    /**
     * Runs detection on one grayscale frame.
     *
     * @param frame the packed 8-bit luminance frame (the detector's only
     *   input; tests construct synthetic frames directly).
     * @param timestampMs epoch-millis timestamp for the result, sourced by the
     *   caller through [org.payswap.camscan.core.time.TimeSource].
     * @return the best quad hypothesis with confidence and evidence flags, or
     *   `null` when nothing qualifies (malformed or blank/uniform frames).
     */
    fun detect(frame: DetectorFrame, timestampMs: Long): DocumentDetection?
}
