package org.payswap.camscan.tools.exportops

// CAMSCAN-PROD-015 §6.4 — the pure models of the long-image strip
// planner (exporting ordered pages as ONE tall image). Geometry only:
// raster composition (bitmap stitching) is the lead-owned applier seam.
//
// Width normalization (documented): targetWidth = MAX page width;
// per-page scale = targetWidth / pageWidth (Double math); every page is
// placed exactly targetWidth wide (the scaled width rounds to the target
// by construction), so under this uniform rule the alignment x-offsets
// evaluate to 0 — the alignment seam exists and is computed generically
// (xOffset = distance between scaled width and target width) so future
// non-scaling policies keep working.
//
// Seam policy: NONE adds nothing; Separator(h) inserts an h-pixel band
// strictly BETWEEN consecutive pages (never before the first or after
// the last), with h validated to 1..8 at plan time.
//
// Vertical math: pages are stacked top to bottom with a cumulative
// y-cursor; per-page scaledHeight = roundHalfUp(pageHeight * scale)
// (ties at exactly .5 round up); totalHeight = sum of scaled heights +
// all seams. Determinism: the plan is a pure function of its inputs.

/** One page offered to the long-image planner, sized in pixels. */
data class LongImagePage(
    val pageId: String,
    val widthPx: Int,
    val heightPx: Int,
)

/** Horizontal placement of a page inside the strip. */
sealed class HorizontalAlignment {

    /** Scaled content sticks to the left edge of the strip. */
    object Left : HorizontalAlignment()

    /** Scaled content is centered horizontally. */
    object Center : HorizontalAlignment()

    /** Scaled content sticks to the right edge of the strip. */
    object Right : HorizontalAlignment()
}

/** Whether separator bands are drawn between consecutive pages. */
sealed class SeamPolicy {

    /** No seams: pages are stacked directly on each other. */
    object None : SeamPolicy()

    /** A uniform separator band of heightPx pixels between pages (1..8). */
    data class Separator(val heightPx: Int) : SeamPolicy()
}

/** Where one page lands inside the strip (pixel geometry). */
data class LongImagePlacement(
    val pageId: String,
    val xOffsetPx: Int,
    val yOffsetPx: Int,
    val scaledWidthPx: Int,
    val scaledHeightPx: Int,
)

/** The complete strip plan. */
data class LongImagePlan(
    val targetWidthPx: Int,
    val alignment: HorizontalAlignment,
    val seamPolicy: SeamPolicy,
    val placements: List<LongImagePlacement>,
    val pageCount: Int,
    val totalHeightPx: Int,
)

/** Sealed failure of planning a strip (the planner never throws). */
sealed class LongImageError {

    /** The page list was empty. */
    object NoPages : LongImageError()

    /** A page had a non-positive dimension. */
    class BadPageSize(val pageId: String) : LongImageError()

    /** A separator height outside the documented 1..8 range. */
    class BadSeamHeight(val heightPx: Int) : LongImageError()
}

/** Sealed result of the strip planner. */
sealed class LongImageResult {

    /** The strip plan. */
    class Ok(val plan: LongImagePlan) : LongImageResult()

    /** The sealed failure. */
    class Error(val error: LongImageError) : LongImageResult()
}
