package org.payswap.camscan.imports.pdf

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.export.pdf.clampJpegQuality
import org.payswap.camscan.imports.ImportFailure
import org.payswap.camscan.imports.ImportResult

/** A PDF page's size in PDF points (72 points per inch). */
data class PdfPageSize(val widthPoints: Int, val heightPoints: Int)

/**
 * Seam over `android.graphics.pdf.PdfRenderer` (CAMSCAN-PROD-008 §6.2):
 * page count, per-page point geometry, and JPEG rendering at caller-chosen
 * pixel dimensions. The pure [PdfImporter] orchestrates against this
 * interface; the android implementation is
 * [org.payswap.camscan.imports.pdf.PdfRendererOpener]'s adapter. JVM tests
 * install a fake that returns fixed rendered bytes.
 */
interface PdfDocumentPages : AutoCloseable {

    /** Number of pages in the document. */
    val pageCount: Int

    /** Page [pageIndex]'s point size, or null when the page is unusable. */
    fun pageSizePoints(pageIndex: Int): PdfPageSize?

    /**
     * Renders page [pageIndex] as JPEG bytes at exactly [widthPx] x
     * [heightPx] and [quality] (1..100). Null = render failure — never
     * throws.
     */
    fun renderPageJpeg(pageIndex: Int, widthPx: Int, heightPx: Int, quality: Int): ByteArray?
}

/**
 * Opening seam: bytes → an open [PdfDocumentPages], or null when the PDF is
 * unopenable or encrypted ([ImportFailure.PdfUnreadable]).
 */
interface PdfDocumentOpener {

    fun open(bytes: ByteArray): PdfDocumentPages?
}

/** Target pixel dimensions for one rendered PDF page. */
data class PdfTargetDims(val widthPx: Int, val heightPx: Int)

/**
 * Pure bounded-scale math for PDF page rendering (documented): a
 * 144 dpi-equivalent render — scale 2.0 over page points (72 pt/inch) —
 * reduced, when necessary, to fit the 40,000,000-pixel per-page cap.
 */
object PdfRenderScale {

    /** PDF points per inch. */
    const val POINTS_PER_INCH = 72

    /** Render resolution: 144 dpi = scale 2.0 over points. */
    const val RENDER_DPI = 144

    /** Documented per-page render cap. */
    const val MAX_PAGE_PIXELS: Long = 40_000_000L

    /**
     * Target pixels for a page of [widthPoints] x [heightPoints]: the
     * 144 dpi-equivalent dims, uniformly reduced to fit [MAX_PAGE_PIXELS].
     * Deterministic IEEE-754 arithmetic (identical on JVM and ART).
     */
    fun targetDimensions(widthPoints: Int, heightPoints: Int): PdfTargetDims {
        val scale = RENDER_DPI.toDouble() / POINTS_PER_INCH
        var widthPx = (widthPoints * scale).roundToInt().coerceAtLeast(1)
        var heightPx = (heightPoints * scale).roundToInt().coerceAtLeast(1)
        val pixels = widthPx.toLong() * heightPx.toLong()
        if (pixels > MAX_PAGE_PIXELS) {
            val factor = sqrt(MAX_PAGE_PIXELS.toDouble() / pixels)
            widthPx = (widthPx * factor).toInt().coerceAtLeast(1)
            heightPx = (heightPx * factor).toInt().coerceAtLeast(1)
        }
        return PdfTargetDims(widthPx, heightPx)
    }
}

/**
 * Bounded PDF import (CAMSCAN-PROD-008 §6.2) — pure Kotlin orchestration on
 * the injected [PdfDocumentOpener] seam; the platform PdfRenderer hides
 * behind it. NO new dependency (PdfRenderer is platform API 26+).
 *
 * Bounded honestly:
 * - [MAX_PDF_IMPORT_PAGES] = 50 pages; beyond → [ImportFailure.PdfTooManyPages]
 *   echoing found + cap;
 * - unopenable/encrypted → [ImportFailure.PdfUnreadable];
 * - per-page render failure → [ImportFailure.PdfPageRenderFailed] echoing the
 *   0-based page index.
 *
 * All-or-nothing: every page is rendered BEFORE any ContentStore put or
 * repository write, so a mid-render failure leaves zero puts and zero
 * upserts — no partial document ever exists.
 *
 * ContentStore key vocabulary (documented; deterministic under the injected
 * ids and time — one timestamp read per import call):
 * - imported PDF original: `imports/<ts>-pdf<importId>.pdf` → every page's
 *   sourceCaptureRef (the non-destructive original, stored once);
 * - rendered page JPEG: `imports/<ts>-pdf<importId>-p<idx>.jpg` → page idx's
 *   processedImageRef (0-based PDF page order).
 *
 * Page semantics: one Page per PDF page in order, cropQuad null,
 * enhancement ORIGINAL, rotationDegrees 0, in a new IMPORTED document
 * (or appended at indices priorCount..n by [appendToDocument]).
 *
 * DETERMINISM LIMITS (documented loudly): PdfRenderer output is
 * PLATFORM-RENDERED — rendered bytes vary across Android versions and
 * devices. The pipeline here is deterministic given fixed rendered bytes +
 * injected ids/time, but the rendered bytes themselves are not
 * cross-version stable. JVM tests inject fake rendered bytes; platform
 * rendering is verified at the lead's integration station only.
 */
class PdfImporter(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
    private val idGenerator: IdGenerator,
    private val timeSource: TimeSource,
    private val opener: PdfDocumentOpener,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Imports one PDF as a new IMPORTED document titled [title]. */
    suspend fun importAsNewDocument(bytes: ByteArray, title: String): ImportResult =
        withContext(dispatcher) { importAsNewDocumentInternal(bytes, title) }

    private suspend fun importAsNewDocumentInternal(bytes: ByteArray, title: String): ImportResult {
        val rendered = renderAllPages(bytes)
        if (rendered is RenderOutcome.Failed) {
            return ImportResult.Failure(rendered.failure)
        }
        val pages = (rendered as RenderOutcome.Rendered).jpegs

        val documentId = idGenerator.newId()
        val importId = idGenerator.newId()
        val now = timeSource.nowMillis()

        val originalRef = contentStore.put("imports/$now-pdf$importId.pdf", bytes)
        val documentPages = pages.mapIndexed { index, jpeg ->
            Page(
                id = idGenerator.newId(),
                documentId = documentId,
                index = index,
                sourceCaptureRef = originalRef,
                processedImageRef = contentStore.put("imports/$now-pdf$importId-p$index.jpg", jpeg),
                cropQuad = null,
                enhancement = PageEnhancementMode.ORIGINAL,
                rotationDegrees = 0,
                ocrResultId = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        }
        val document = Document(
            id = documentId,
            title = title,
            pageIds = documentPages.map { it.id },
            sourceType = DocumentSource.IMPORTED,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        repository.upsertDocument(document, documentPages)
        return ImportResult.Success(document, documentPages)
    }

    /**
     * Appends one PDF's rendered pages to an existing document. Null when
     * the document is unknown (flag discipline); a PDF failure is an honest
     * [ImportResult.Failure] and leaves the document untouched.
     */
    suspend fun appendToDocument(documentId: String, bytes: ByteArray): ImportResult? =
        withContext(dispatcher) { appendToDocumentInternal(documentId, bytes) }

    private suspend fun appendToDocumentInternal(documentId: String, bytes: ByteArray): ImportResult? {
        val document = repository.getDocument(documentId) ?: return null
        val existingPages = repository.getPages(documentId).sortedBy { it.index }

        val rendered = renderAllPages(bytes)
        if (rendered is RenderOutcome.Failed) {
            return ImportResult.Failure(rendered.failure)
        }
        val jpegs = (rendered as RenderOutcome.Rendered).jpegs

        val importId = idGenerator.newId()
        val now = timeSource.nowMillis()

        val originalRef = contentStore.put("imports/$now-pdf$importId.pdf", bytes)
        val appended = jpegs.mapIndexed { offset, jpeg ->
            Page(
                id = idGenerator.newId(),
                documentId = documentId,
                index = existingPages.size + offset,
                sourceCaptureRef = originalRef,
                processedImageRef = contentStore.put(
                    "imports/$now-pdf$importId-p$offset.jpg",
                    jpeg,
                ),
                cropQuad = null,
                enhancement = PageEnhancementMode.ORIGINAL,
                rotationDegrees = 0,
                ocrResultId = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        }
        val allPages = existingPages + appended
        val updated = document.copy(
            pageIds = allPages.map { it.id },
            updatedAtMillis = now,
        )
        repository.upsertDocument(updated, allPages)
        return ImportResult.Success(updated, allPages)
    }

    // ------------------------------------------------------------ renders

    private sealed interface RenderOutcome {
        data class Rendered(val jpegs: List<ByteArray>) : RenderOutcome
        data class Failed(val failure: ImportFailure) : RenderOutcome
    }

    /**
     * Renders every page up front (page count cap → point geometry →
     * bounded target dims → JPEG) — the all-or-nothing boundary: nothing is
     * stored until every page has rendered.
     */
    private fun renderAllPages(bytes: ByteArray): RenderOutcome {
        val pages = try {
            opener.open(bytes)
        } catch (expected: Exception) {
            null
        } ?: return RenderOutcome.Failed(ImportFailure.PdfUnreadable)
        return pages.use { open ->
            val pageCount = open.pageCount
            if (pageCount > MAX_PDF_IMPORT_PAGES) {
                return RenderOutcome.Failed(
                    ImportFailure.PdfTooManyPages(
                        foundPages = pageCount,
                        maxPages = MAX_PDF_IMPORT_PAGES,
                    ),
                )
            }
            val quality = clampJpegQuality(jpegQuality)
            val jpegs = ArrayList<ByteArray>(pageCount)
            for (index in 0 until pageCount) {
                val size = open.pageSizePoints(index)
                    ?: return RenderOutcome.Failed(ImportFailure.PdfPageRenderFailed(index))
                val target = PdfRenderScale.targetDimensions(size.widthPoints, size.heightPoints)
                val jpeg = open.renderPageJpeg(index, target.widthPx, target.heightPx, quality)
                    ?: return RenderOutcome.Failed(ImportFailure.PdfPageRenderFailed(index))
                jpegs += jpeg
            }
            RenderOutcome.Rendered(jpegs)
        }
    }

    companion object {
        /** Documented PDF page-count import cap. */
        const val MAX_PDF_IMPORT_PAGES = 50

        /** Documented default render JPEG quality. */
        const val DEFAULT_JPEG_QUALITY = 85
    }
}
