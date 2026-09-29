package org.payswap.camscan.export.compress

/**
 * Compression options (CAMSCAN-PROD-008 §6.5). Documented defaults:
 * [DEFAULT_QUALITY] = 70 (JPEG re-encode quality), [DEFAULT_MAX_LONG_EDGE_PX]
 * = 1600 (the long-edge pixel budget per page).
 *
 * The quality is CLAMPED into 0..100 at construction — the echoed
 * [quality] property always carries the clamped value; the long edge is
 * clamped to at least 1 px. Downsampling NEVER upscales (that rule lives in
 * [DownsamplePlanner]).
 */
class CompressPdfOptions(
    quality: Int = DEFAULT_QUALITY,
    maxLongEdgePx: Int = DEFAULT_MAX_LONG_EDGE_PX,
) {

    /** JPEG re-encode quality, clamped 0..100. */
    val quality: Int = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)

    /** Per-page long-edge pixel budget, clamped to at least 1. */
    val maxLongEdgePx: Int = maxLongEdgePx.coerceAtLeast(MIN_LONG_EDGE_PX)

    companion object {
        const val DEFAULT_QUALITY = 70
        const val DEFAULT_MAX_LONG_EDGE_PX = 1600
        const val MIN_QUALITY = 0
        const val MAX_QUALITY = 100
        const val MIN_LONG_EDGE_PX = 1
    }
}
