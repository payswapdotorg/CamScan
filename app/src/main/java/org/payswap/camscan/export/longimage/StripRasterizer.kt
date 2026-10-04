package org.payswap.camscan.export.longimage

import org.payswap.camscan.tools.exportops.LongImagePlan

// CAMSCAN-VERIFY-002 — the raster seam of the long-image export. The
// strip GEOMETRY comes from the delivered LongImagePlanner (the
// contract); rendering the planned strip into PNG bytes is this seam's
// job. The delivered DrawSurface rasterizer exposes no scaled-bitmap
// blit op (its op set is stroke/fill/glyph/blend/multiply), so the
// production applier composes through android.graphics — that residual
// engine seam is declared in the delivery report. JVM tests inject a
// fake rasterizer and assert the PLANNER-driven contract instead.

/** One page's stored bytes offered to the strip rasterizer. */
data class LongImagePageBytes(
    val pageId: String,
    val bytes: ByteArray,
    val rotationDegrees: Int,
)

/** Renders a planned strip into encoded PNG bytes; null on honest failure. */
interface StripRasterizer {

    /**
     * Composes the strip described by [plan]: every page's bitmap scaled
     * to its placement and drawn at its offset, then PNG-encoded. Returns
     * null when a page's bytes are undecodable or the strip exceeds the
     * implementation's documented pixel budget.
     */
    fun render(plan: LongImagePlan, pages: List<LongImagePageBytes>): ByteArray?
}
