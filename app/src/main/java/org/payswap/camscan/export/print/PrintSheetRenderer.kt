package org.payswap.camscan.export.print

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import org.payswap.camscan.export.pdf.PdfPageImage
import org.payswap.camscan.export.pdf.PdfWriter
import org.payswap.camscan.imports.AndroidBoundsDecoder
import org.payswap.camscan.imports.BoundsDecoder
import org.payswap.camscan.tools.printops.DoubleRect
import org.payswap.camscan.tools.printops.PrintJobPlan
import org.payswap.camscan.tools.printops.PrintJobPlanner
import org.payswap.camscan.tools.printops.PrintPage
import org.payswap.camscan.tools.printops.PrintPlanError
import org.payswap.camscan.tools.printops.PrintRequest
import org.payswap.camscan.tools.printops.PrintResult

// CAMSCAN-VERIFY-002 — renders a print job into PDF bytes for the
// Android print framework. Pipeline (documented): decode each page's
// bounds -> derive planner pages through [PrintGeometry] (assumed
// 300 dpi) -> plan via the delivered [PrintJobPlanner] -> render each
// placement's SHEET at 150 dpi (white background; the page bitmap
// drawn into the planned content rect with bilinear filtering) ->
// JPEG-encode each sheet -> one multi-page PDF through the delivered
// [PdfWriter]. One sheet per placement (the planner's no-N-up rule).
// Honest caps: a sheet beyond 16 megapixels or an undecodable page
// fails the whole render with null. All geometry comes from the plan;
// nothing here re-derives placements.

/** One page offered to the print renderer. */
data class PrintPageInput(
    val pageId: String,
    val bytes: ByteArray,
    val rotationDegrees: Int,
)

/** Sealed outcome of rendering a print job. */
sealed class PrintRenderResult {

    /** The rendered multi-sheet PDF bytes plus the plan that drove them. */
    class Ok(val pdfBytes: ByteArray, val plan: PrintJobPlan) : PrintRenderResult()

    /** The planner rejected the job; the honest reason is carried. */
    class Rejected(val reason: PrintPlanErrorCarrier) : PrintRenderResult()

    /** Rendering failed (decode failure or the sheet pixel budget). */
    object RenderFailed : PrintRenderResult()
}

/** The planner's sealed error, re-exposed without variance pain. */
class PrintPlanErrorCarrier(val description: String)

/** Renders planned print jobs into print-framework PDF bytes. */
class PrintSheetRenderer(
    private val boundsDecoder: BoundsDecoder = AndroidBoundsDecoder(),
) {

    /**
     * Plans and renders the whole job. The timestamp feeds the PDF's
     * CreationDate (read once, after pages decode).
     */
    fun render(
        pages: List<PrintPageInput>,
        request: PrintRequest,
        creationDateMillis: Long,
    ): PrintRenderResult {
        if (pages.isEmpty()) {
            return PrintRenderResult.Rejected(PrintPlanErrorCarrier("no pages"))
        }
        val printPages = ArrayList<PrintPage>(pages.size)
        val pixelGeometry = ArrayList<Triple<String, Int, Int>>(pages.size)
        val rotationById = HashMap<String, Int>(pages.size)
        for (page in pages) {
            val bounds = boundsDecoder.decodeBounds(page.bytes)
                ?: return PrintRenderResult.RenderFailed
            pixelGeometry.add(Triple(page.pageId, bounds.widthPx, bounds.heightPx))
            rotationById[page.pageId] = page.rotationDegrees
        }
        printPages.addAll(
            PrintGeometry.printPagesFrom(pixelGeometry) { pageId ->
                rotationById[pageId] ?: 0
            },
        )
        val plan = when (val outcome = PrintJobPlanner.plan(printPages, request)) {
            is PrintResult.Error -> {
                return PrintRenderResult.Rejected(
                    PrintPlanErrorCarrier(describe(outcome)),
                )
            }
            is PrintResult.Ok -> outcome.plan
        }

        val bytesById = HashMap<String, PrintPageInput>(pages.size)
        for (page in pages) {
            bytesById[page.pageId] = page
        }
        val sheetImages = ArrayList<PdfPageImage>(plan.placements.size)
        for (placement in plan.placements) {
            val input = bytesById[placement.pageId] ?: return PrintRenderResult.RenderFailed
            val sheet = renderSheet(plan, placement.rectMm, input)
                ?: return PrintRenderResult.RenderFailed
            val encoded = ByteArrayOutputStream()
            val ok = sheet.compress(Bitmap.CompressFormat.JPEG, SHEET_JPEG_QUALITY, encoded)
            val widthPx = sheet.width
            val heightPx = sheet.height
            sheet.recycle()
            if (!ok) return PrintRenderResult.RenderFailed
            sheetImages.add(
                PdfPageImage(
                    jpegBytes = encoded.toByteArray(),
                    widthPx = widthPx,
                    heightPx = heightPx,
                ),
            )
        }
        return PrintRenderResult.Ok(PdfWriter.write(sheetImages, creationDateMillis), plan)
    }

    /** Renders one placement's sheet at the render dpi. */
    private fun renderSheet(
        plan: PrintJobPlan,
        rectMm: DoubleRect,
        input: PrintPageInput,
    ): Bitmap? {
        val sheetWidthPx = mmToPx(plan.paperWidthMm)
        val sheetHeightPx = mmToPx(plan.paperHeightMm)
        if (sheetWidthPx <= 0 || sheetHeightPx <= 0) return null
        if (sheetWidthPx.toLong() * sheetHeightPx.toLong() > MAX_SHEET_PIXELS) return null
        val decoded = decodeRotated(input) ?: return null
        val sheet = Bitmap.createBitmap(sheetWidthPx, sheetHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(SHEET_BACKGROUND_COLOR)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val destination = RectF(
            mmToPx(rectMm.x).toFloat(),
            mmToPx(rectMm.y).toFloat(),
            mmToPx(rectMm.x + rectMm.width).toFloat(),
            mmToPx(rectMm.y + rectMm.height).toFloat(),
        )
        canvas.drawBitmap(decoded, null, destination, paint)
        decoded.recycle()
        return sheet
    }

    /** Decodes and right-angle-rotates one page's bitmap. */
    private fun decodeRotated(input: PrintPageInput): Bitmap? {
        val base = BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size) ?: return null
        val rotation = ((input.rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return base
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        val rotated = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
        if (rotated !== base) {
            base.recycle()
        }
        return rotated
    }

    /** Millimetres -> sheet pixels at the documented render dpi. */
    private fun mmToPx(mm: Double): Int {
        val px = mm / PrintGeometry.MM_PER_INCH * RENDER_DPI
        return Math.round(px).toInt()
    }

    /** Human-readable planner failure (single line, honest). */
    private fun describe(outcome: PrintResult.Error): String {
        return when (val error = outcome.error) {
            is PrintPlanError.NoPages -> "no pages"
            is PrintPlanError.BadPageSize -> {
                "bad page size: " + error.pageId
            }
            is PrintPlanError.UnknownPaper -> {
                "unknown paper: " + error.paperId
            }
            is PrintPlanError.BadMargins -> error.reason
            is PrintPlanError.BadCopies -> {
                "bad copies: " + error.copies
            }
            is PrintPlanError.BadPageRange -> {
                "bad page range: " + error.expression
            }
            is PrintPlanError.EmptyContentArea -> {
                "margins leave no printable area"
            }
        }
    }

    companion object {
        /** Sheet render density (a quality/speed middle ground). */
        const val RENDER_DPI = 150.0

        /** JPEG quality of encoded sheets. */
        const val SHEET_JPEG_QUALITY = 90

        /** White sheet background. */
        const val SHEET_BACKGROUND_COLOR = 0xFFFFFFFF.toInt()

        /** 16 megapixel per-sheet budget. */
        const val MAX_SHEET_PIXELS = 16_000_000L
    }
}
