package org.payswap.camscan.tools.exportops

import kotlin.math.ceil
import kotlin.math.floor

// CAMSCAN-PROD-015 §6.4 — LongImagePlanner: pure geometry for exporting
// ordered pages as ONE tall image. See LongImageModels.kt for the fully
// documented math (width normalization, alignment offsets, seam policy,
// cumulative y-cursor, half-up rounding at exactly .5). NEVER throws;
// failures are sealed [LongImageError]. Validation order (documented):
// NoPages -> BadPageSize (in page order) -> BadSeamHeight.
// HONEST SCOPE: geometry only — raster composition (bitmap stitching)
// is the lead-owned applier seam.

/** Pure planner computing the pixel geometry of a long-image strip. */
object LongImagePlanner {

    /** Plans the strip for the given pages, alignment and seam policy. */
    fun plan(
        pages: List<LongImagePage>,
        alignment: HorizontalAlignment,
        seamPolicy: SeamPolicy,
    ): LongImageResult {
        if (pages.isEmpty()) {
            return LongImageResult.Error(LongImageError.NoPages)
        }
        for (page in pages) {
            if (page.widthPx <= 0 || page.heightPx <= 0) {
                return LongImageResult.Error(LongImageError.BadPageSize(page.pageId))
            }
        }
        var seamHeight = 0
        if (seamPolicy is SeamPolicy.Separator) {
            if (seamPolicy.heightPx < 1 || seamPolicy.heightPx > 8) {
                return LongImageResult.Error(LongImageError.BadSeamHeight(seamPolicy.heightPx))
            }
            seamHeight = seamPolicy.heightPx
        }
        var targetWidth = 0
        for (page in pages) {
            if (page.widthPx > targetWidth) targetWidth = page.widthPx
        }
        val placements = mutableListOf<LongImagePlacement>()
        var yCursor = 0
        for (index in pages.indices) {
            val page = pages[index]
            val scale = targetWidth.toDouble() / page.widthPx.toDouble()
            val scaledWidth = roundHalfUp(page.widthPx.toDouble() * scale).toInt()
            val scaledHeight = roundHalfUp(page.heightPx.toDouble() * scale).toInt()
            val xOffset = alignmentOffset(alignment, targetWidth, scaledWidth)
            placements.add(
                LongImagePlacement(
                    pageId = page.pageId,
                    xOffsetPx = xOffset,
                    yOffsetPx = yCursor,
                    scaledWidthPx = scaledWidth,
                    scaledHeightPx = scaledHeight,
                ),
            )
            yCursor += scaledHeight
            // Seam strictly between consecutive pages.
            if (index < pages.size - 1) {
                yCursor += seamHeight
            }
        }
        return LongImageResult.Ok(
            LongImagePlan(
                targetWidthPx = targetWidth,
                alignment = alignment,
                seamPolicy = seamPolicy,
                placements = placements,
                pageCount = pages.size,
                totalHeightPx = yCursor,
            ),
        )
    }

    /** Generic alignment offset: Left -> 0, Center -> floor((target - scaled) / 2), Right -> target - scaled. Under the uniform normalization rule scaled == target, so all evaluate to 0. */
    fun alignmentOffset(
        alignment: HorizontalAlignment,
        targetWidthPx: Int,
        scaledWidthPx: Int,
    ): Int {
        return when (alignment) {
            is HorizontalAlignment.Left -> 0
            is HorizontalAlignment.Center -> (targetWidthPx - scaledWidthPx) / 2
            is HorizontalAlignment.Right -> targetWidthPx - scaledWidthPx
        }
    }

    /** Rounds half-up at exactly .5 (ties away from zero). */
    fun roundHalfUp(value: Double): Long {
        if (value >= 0.0) {
            return floor(value + 0.5).toLong()
        }
        return ceil(value - 0.5).toLong()
    }
}
