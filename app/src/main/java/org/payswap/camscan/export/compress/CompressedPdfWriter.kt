package org.payswap.camscan.export.compress

import org.payswap.camscan.export.pdf.PdfPageImage
import org.payswap.camscan.export.pdf.PdfWriter

/**
 * Functional seam onto PROD-007's real [PdfWriter] (CAMSCAN-PROD-008 §6.5):
 * one adapter line joins the pure writer to the compression flow — the
 * default implementation IS `PdfWriter.write`. Injected only so tests (and
 * a future writer swap) can intercept; page order and rotation semantics
 * are IDENTICAL to normal export because the same [PdfPageImage] contract
 * feeds the same writer.
 */
fun interface PdfAssembler {

    fun assemble(pages: List<PdfPageImage>, creationDateMillis: Long): ByteArray
}

/** One caller-supplied page awaiting compression. */
data class CompressPageInput(
    val jpegBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int = 0,
    val colorComponents: Int = PdfPageImage.COLOR_COMPONENTS_RGB,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressPageInput) return false
        return widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            rotationDegrees == other.rotationDegrees &&
            colorComponents == other.colorComponents &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * widthPx + heightPx) + rotationDegrees) + colorComponents) +
            jpegBytes.contentHashCode()

    override fun toString(): String =
        "CompressPageInput(widthPx=$widthPx, heightPx=$heightPx, rotationDegrees=" +
            "$rotationDegrees, colorComponents=$colorComponents, jpegBytes=${jpegBytes.size})"
}

/** Per-page before/after truth for the [CompressionReport]. */
data class PageCompression(
    val pageIndex: Int,
    /** Source page JPEG byte count (the "before"). */
    val beforeBytes: Int,
    /** Output page JPEG byte count (the "after"; equals before on skipped pages). */
    val afterBytes: Int,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    /** True when the page was within budget and its bytes passed through. */
    val skipped: Boolean,
)

/**
 * HONEST size report (CAMSCAN-PROD-008 §6.5, deliberately OUTSIDE the frozen
 * model): [originalBytes] = the source page-image payload (Σ beforeBytes);
 * [compressedBytes] = the ASSEMBLED PDF's actual byte size (the artifact the
 * caller stores/shares); [pageCount] with per-page [pages] before/after.
 * The fixed per-page PDF structure overhead applies to both sides, so the
 * comparison is apples-to-apples. compressedBytes MAY exceed originalBytes
 * for already-small inputs — the report carries the truth, NEVER a silent
 * fallback.
 */
data class CompressionReport(
    val originalBytes: Long,
    val compressedBytes: Long,
    val pageCount: Int,
    val pages: List<PageCompression>,
)

/** The writer's output: final PDF bytes plus the honest report. */
data class CompressedPdfOutput(
    val pdfBytes: ByteArray,
    val report: CompressionReport,
)

/**
 * Compressed PDF writer (CAMSCAN-PROD-008 §6.5): the caller supplies
 * per-page JPEG bytes + dims + rotations; each page is planned
 * ([DownsamplePlanner]), resampled through the injected [ImageResampler]
 * (or passed through verbatim when within budget), and assembled into the
 * final PDF THROUGH the real [PdfWriter] behind the [PdfAssembler] seam.
 *
 * Returns null — an honest "cannot compress" signal, never a throw and
 * never a silent fallback — when the page list is empty or any resample
 * fails; page order and rotation are identical to normal export.
 *
 * Determinism (documented): fixed page inputs + options + creation date +
 * a deterministic resampler produce byte-identical PDFs — the underlying
 * [PdfWriter] is byte-deterministic and this flow adds no non-determinism.
 * The production [BitmapResampler] is platform-dependent (JPEG encode), so
 * END-TO-END device determinism is not claimed — see [CompressExportEngine].
 */
class CompressedPdfWriter(
    private val resampler: ImageResampler,
    private val assembler: PdfAssembler = DEFAULT_ASSEMBLER,
    private val planner: DownsamplePlanner = DownsamplePlanner(),
) {

    fun write(
        pages: List<CompressPageInput>,
        options: CompressPdfOptions,
        creationDateMillis: Long,
    ): CompressedPdfOutput? {
        if (pages.isEmpty()) return null

        val pdfPages = ArrayList<PdfPageImage>(pages.size)
        val pageReports = ArrayList<PageCompression>(pages.size)
        var originalBytes = 0L

        for ((index, page) in pages.withIndex()) {
            val plan = planner.plan(page.widthPx, page.heightPx, options)
            originalBytes += page.jpegBytes.size

            val outputBytes: ByteArray
            val outputWidthPx: Int
            val outputHeightPx: Int
            if (plan.skipDownsample) {
                // Within budget: the original bytes pass through VERBATIM,
                // recorded honestly (after == before, skipped = true).
                outputBytes = page.jpegBytes
                outputWidthPx = page.widthPx
                outputHeightPx = page.heightPx
            } else {
                val resampled = resampler.resample(
                    jpegBytes = page.jpegBytes,
                    targetWidthPx = plan.targetWidthPx,
                    targetHeightPx = plan.targetHeightPx,
                    quality = options.quality,
                ) ?: return null
                outputBytes = resampled.jpegBytes
                outputWidthPx = resampled.widthPx
                outputHeightPx = resampled.heightPx
            }

            pdfPages += PdfPageImage(
                jpegBytes = outputBytes,
                widthPx = outputWidthPx,
                heightPx = outputHeightPx,
                rotationDegrees = page.rotationDegrees,
                colorComponents = page.colorComponents,
            )
            pageReports += PageCompression(
                pageIndex = index,
                beforeBytes = page.jpegBytes.size,
                afterBytes = outputBytes.size,
                sourceWidthPx = page.widthPx,
                sourceHeightPx = page.heightPx,
                targetWidthPx = plan.targetWidthPx,
                targetHeightPx = plan.targetHeightPx,
                skipped = plan.skipDownsample,
            )
        }

        val pdfBytes = assembler.assemble(pdfPages, creationDateMillis)
        return CompressedPdfOutput(
            pdfBytes = pdfBytes,
            report = CompressionReport(
                originalBytes = originalBytes,
                compressedBytes = pdfBytes.size.toLong(),
                pageCount = pages.size,
                pages = pageReports,
            ),
        )
    }

    companion object {
        /** The one adapter line onto PROD-007's real writer. */
        val DEFAULT_ASSEMBLER: PdfAssembler = PdfAssembler { pages, creationDateMillis ->
            PdfWriter.write(pages, creationDateMillis)
        }
    }
}
