package org.payswap.camscan.tools.printops

import kotlin.math.max
import kotlin.math.min

// CAMSCAN-PROD-015 §6.3 — PrintJobPlanner: pure print job planning.
// NEVER throws — every failure is a sealed [PrintPlanError]. One
// placement per (selected page, copy): no N-up, so totalSheetCount
// equals the placement count. Placement order: selected pages in
// ascending order (duplicates preserved), copies 1..N within a page.
//
// Content-rect math (documented per scale mode), with content area
//   areaW = orientedWidth - left - right, areaH = orientedHeight - top - bottom:
//   Fit    — scale = min(areaW / contentW, areaH / contentH); the scaled
//             content is CENTERED in the content area; it fits fully and
//             may letterbox (empty bands on two sides).
//   Fill   — scale = max(areaW / contentW, areaH / contentH); centered in
//             the content area; it covers the area fully and may overflow
//             it (cropping).
//   Actual — scale = 1.0; centered in the content area at original size;
//             may overflow the area and the sheet.
// Centering formula (all modes): x = left + (areaW - placedW) / 2,
// y = top + (areaH - placedH) / 2 — negative offsets are legal overflow.
//
// Validation order (documented): NoPages -> BadPageSize (in page order)
// -> UnknownPaper -> BadMargins -> BadCopies -> BadPageRange ->
// EmptyContentArea.
//
// HONEST SCOPE: planning only — the Android print-framework glue is the
// lead-owned integration seam.

/** Pure planner that turns pages + a request into a placement-level plan. */
object PrintJobPlanner {

    /** Plans the print job. Returns [PrintResult.Ok] with one placement per (page, copy) or [PrintResult.Error] with the sealed failure; never throws. */
    fun plan(pages: List<PrintPage>, request: PrintRequest): PrintResult {
        if (pages.isEmpty()) {
            return PrintResult.Error(PrintPlanError.NoPages)
        }
        for (page in pages) {
            if (!page.widthMm.isFinite() || !page.heightMm.isFinite()) {
                return PrintResult.Error(PrintPlanError.BadPageSize(page.pageId))
            }
            if (page.widthMm <= 0.0 || page.heightMm <= 0.0) {
                return PrintResult.Error(PrintPlanError.BadPageSize(page.pageId))
            }
        }
        val paper = PaperSizes.byId(request.paperId)
        if (paper == null) {
            return PrintResult.Error(PrintPlanError.UnknownPaper(request.paperId))
        }
        if (!request.margins.isValid) {
            return PrintResult.Error(
                PrintPlanError.BadMargins("margins must be finite and non-negative"),
            )
        }
        if (request.copies < 1) {
            return PrintResult.Error(PrintPlanError.BadCopies(request.copies))
        }
        val selectedPages = PageRanges.parse(request.pageRange, pages.size)
        if (selectedPages == null) {
            return PrintResult.Error(PrintPlanError.BadPageRange(request.pageRange))
        }
        val oriented = request.orientation.apply(paper)
        val areaX = request.margins.leftMm
        val areaY = request.margins.topMm
        val areaWidth = oriented.widthMm - request.margins.leftMm - request.margins.rightMm
        val areaHeight = oriented.heightMm - request.margins.topMm - request.margins.bottomMm
        if (areaWidth <= 0.0 || areaHeight <= 0.0) {
            return PrintResult.Error(PrintPlanError.EmptyContentArea)
        }
        val placements = mutableListOf<PrintPlacement>()
        for (pageNumber in selectedPages) {
            val page = pages[pageNumber - 1]
            val scale = when (request.scaleMode) {
                is PrintScaleMode.Fit ->
                    min(areaWidth / page.widthMm, areaHeight / page.heightMm)
                is PrintScaleMode.Fill ->
                    max(areaWidth / page.widthMm, areaHeight / page.heightMm)
                is PrintScaleMode.Actual -> 1.0
            }
            val placedWidth = page.widthMm * scale
            val placedHeight = page.heightMm * scale
            val x = areaX + (areaWidth - placedWidth) / 2.0
            val y = areaY + (areaHeight - placedHeight) / 2.0
            for (copyIndex in 1..request.copies) {
                placements.add(
                    PrintPlacement(
                        pageId = page.pageId,
                        pageNumber = pageNumber,
                        copyIndex = copyIndex,
                        rectMm = DoubleRect(x, y, placedWidth, placedHeight),
                        rectPoints = toPointsRect(x, y, placedWidth, placedHeight),
                        rectPx = toPxRect(x, y, placedWidth, placedHeight),
                    ),
                )
            }
        }
        return PrintResult.Ok(
            PrintJobPlan(
                paperId = paper.paperId,
                paperWidthMm = oriented.widthMm,
                paperHeightMm = oriented.heightMm,
                orientation = request.orientation,
                scaleMode = request.scaleMode,
                contentAreaMm = DoubleRect(areaX, areaY, areaWidth, areaHeight),
                placements = placements,
                totalSheetCount = placements.size,
            ),
        )
    }

    private fun toPointsRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ): DoubleRect {
        return DoubleRect(
            PrintUnits.mmToPoints(x),
            PrintUnits.mmToPoints(y),
            PrintUnits.mmToPoints(width),
            PrintUnits.mmToPoints(height),
        )
    }

    private fun toPxRect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ): LongRect {
        return LongRect(
            PrintUnits.roundHalfUp(PrintUnits.mmToPoints(x)),
            PrintUnits.roundHalfUp(PrintUnits.mmToPoints(y)),
            PrintUnits.roundHalfUp(PrintUnits.mmToPoints(width)),
            PrintUnits.roundHalfUp(PrintUnits.mmToPoints(height)),
        )
    }
}
