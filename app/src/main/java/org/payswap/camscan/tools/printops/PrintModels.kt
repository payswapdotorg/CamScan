package org.payswap.camscan.tools.printops

// CAMSCAN-PROD-015 §6.3 — the pure request/response models of the print
// job planner. Placement rects are exposed in three views: millimetres
// (Double, the internal math), print points (Double, mm * 72 / 25.4) and
// px (Long, the point value rounded half-up at exactly .5 — the 1 px = 1
// pt preview convention, documented in PrintUnits).

/** One page offered to the print planner, sized in millimetres. */
data class PrintPage(
    val pageId: String,
    val widthMm: Double,
    val heightMm: Double,
)

/** Everything the planner needs: paper, orientation, margins, scaling, range, copies. */
data class PrintRequest(
    val paperId: String,
    val orientation: Orientation,
    val margins: PrintMargins,
    val scaleMode: PrintScaleMode,
    val pageRange: String,
    val copies: Int,
)

/** A rectangle in one unit view, expressed as x/y offset + width/height. */
data class DoubleRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** Long-integer rectangle (the rounded px view). */
data class LongRect(
    val x: Long,
    val y: Long,
    val width: Long,
    val height: Long,
)

/** Where one copy of one page lands on its sheet, in all three unit views. */
data class PrintPlacement(
    val pageId: String,
    val pageNumber: Int,
    val copyIndex: Int,
    val rectMm: DoubleRect,
    val rectPoints: DoubleRect,
    val rectPx: LongRect,
)

/** The complete planned print job. */
data class PrintJobPlan(
    val paperId: String,
    val paperWidthMm: Double,
    val paperHeightMm: Double,
    val orientation: Orientation,
    val scaleMode: PrintScaleMode,
    val contentAreaMm: DoubleRect,
    val placements: List<PrintPlacement>,
    val totalSheetCount: Int,
)

/** Sealed failure of planning a print job (the planner never throws). */
sealed class PrintPlanError {

    /** The page list was empty. */
    object NoPages : PrintPlanError()

    /** A page had a non-positive or non-finite dimension. */
    class BadPageSize(val pageId: String) : PrintPlanError()

    /** The paperId is not in the catalog. */
    class UnknownPaper(val paperId: String) : PrintPlanError()

    /** Margins were negative or non-finite. */
    class BadMargins(val reason: String) : PrintPlanError()

    /** copies was below 1. */
    class BadCopies(val copies: Int) : PrintPlanError()

    /** The page-range expression did not parse or referenced pages out of range. */
    class BadPageRange(val expression: String) : PrintPlanError()

    /** Margins consumed the whole sheet; no printable area is left. */
    object EmptyContentArea : PrintPlanError()
}

/** Sealed result of the planner. */
sealed class PrintResult {

    /** The planned job. */
    class Ok(val plan: PrintJobPlan) : PrintResult()

    /** The sealed failure. */
    class Error(val error: PrintPlanError) : PrintResult()
}
