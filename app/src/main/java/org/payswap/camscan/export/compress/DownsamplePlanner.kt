package org.payswap.camscan.export.compress

/**
 * Per-page downsample decision (CAMSCAN-PROD-008 §6.5): the source dims,
 * the target dims, and the skip rule's verdict. A page already within the
 * long-edge budget passes its bytes through VERBATIM — recorded honestly
 * with [skipDownsample] = true and identical source/target dims.
 */
data class PageDownsamplePlan(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val skipDownsample: Boolean,
)

/**
 * PURE downsample planner (CAMSCAN-PROD-008 §6.5) — integer arithmetic
 * only, deterministic, JVM-testable.
 *
 * Math (documented): let L = max(w, h). When L <= [CompressPdfOptions.maxLongEdgePx]
 * the page is already within budget → skip (bytes pass through). Otherwise
 * the target is the half-up rational scaling of each edge by
 * maxLongEdgePx / L — exact integer math, no floating point — clamped to at
 * least 1 px. The scale is strictly < 1 on the non-skip branch, so the
 * planner NEVER upscales.
 */
class DownsamplePlanner {

    fun plan(widthPx: Int, heightPx: Int, options: CompressPdfOptions): PageDownsamplePlan {
        val longEdge = maxOf(widthPx, heightPx)
        return if (longEdge <= options.maxLongEdgePx) {
            PageDownsamplePlan(
                sourceWidthPx = widthPx,
                sourceHeightPx = heightPx,
                targetWidthPx = widthPx,
                targetHeightPx = heightPx,
                skipDownsample = true,
            )
        } else {
            val budget = options.maxLongEdgePx
            val targetWidth = ((widthPx * budget) + longEdge / 2) / longEdge
            val targetHeight = ((heightPx * budget) + longEdge / 2) / longEdge
            PageDownsamplePlan(
                sourceWidthPx = widthPx,
                sourceHeightPx = heightPx,
                targetWidthPx = targetWidth.coerceAtLeast(1),
                targetHeightPx = targetHeight.coerceAtLeast(1),
                skipDownsample = false,
            )
        }
    }
}
