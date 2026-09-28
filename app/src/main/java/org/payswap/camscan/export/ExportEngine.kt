package org.payswap.camscan.export

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.export.pdf.EncodedJpeg
import org.payswap.camscan.export.pdf.PageImageEncoder
import org.payswap.camscan.export.pdf.PdfPageImage
import org.payswap.camscan.export.pdf.PdfWriter

/**
 * Export engine (CAMSCAN-PROD-007 §6.3): turns a stored document into a PDF
 * or per-page JPG artifact, written **through** the [ContentStore] (export
 * artifacts are durable binary assets like any other). Pure Kotlin — all
 * platform work (JPEG re-encoding) hides behind the injected
 * [PageImageEncoder] seam, and all time behind [TimeSource], so the engine
 * is fully JVM-testable and deterministic for fixed inputs.
 *
 * Key vocabulary (documented, per the work order):
 * - PDF: `exports/<docId>-<ts>-<n>p.pdf` — timestamp and page count inline;
 * - JPG: `exports/<docId>-p<idx>.jpg` — 0-based page index, no timestamp.
 *
 * Determinism: each export reads [TimeSource.nowMillis] exactly once, after
 * the pages load; that single timestamp feeds both the PDF's
 * `/CreationDate` and the key. Display names derive from the document title
 * via [sanitizeFileStem] ("<title>.pdf", "<title>_p<idx+1>.jpg").
 *
 * API contract: both verbs return null (never throw) when the document is
 * unknown, has no pages, the page index is out of range, a page's
 * [Page.processedImageRef] is missing, its bytes cannot be opened, or the
 * encoder rejects them. Successful runs return the stored [ExportArtifact].
 */
class ExportEngine(
    private val contentStore: ContentStore,
    private val encoder: PageImageEncoder,
    private val timeSource: TimeSource,
    private val dispatcher: CoroutineDispatcher,
) {

    /** Exports the whole document as one multi-page PDF artifact. */
    suspend fun exportPdf(repository: DocumentRepository, documentId: String): ExportArtifact? {
        val document = repository.getDocument(documentId) ?: return null
        val pages = repository.getPages(documentId).sortedBy { it.index }
        if (pages.isEmpty()) return null

        val pageImages = loadPdfPages(pages) ?: return null
        val timestamp = timeSource.nowMillis()
        val pdfBytes = PdfWriter.write(pageImages, timestamp)

        val key = "exports/$documentId-$timestamp-${pages.size}p.pdf"
        val ref = contentStore.put(key, pdfBytes)
        return ExportArtifact(
            ref = ref,
            mime = ExportArtifact.MIME_PDF,
            displayName = sanitizeFileStem(document.title) + ".pdf",
            sizeBytes = pdfBytes.size,
            pageCount = pages.size,
        )
    }

    /**
     * Exports one page (0-based [pageIndex]) as a JPG artifact containing the
     * encoder's JPEG output (stored pages are PNG; already-JPEG pages pass
     * through the encoder's fast path byte-identically).
     */
    suspend fun exportJpg(
        repository: DocumentRepository,
        documentId: String,
        pageIndex: Int,
    ): ExportArtifact? {
        val document = repository.getDocument(documentId) ?: return null
        val pages = repository.getPages(documentId).sortedBy { it.index }
        if (pages.isEmpty()) return null
        val page = pages.getOrNull(pageIndex) ?: return null

        val storedBytes = openPageBytes(page) ?: return null
        val encoded = withContext(dispatcher) { encoder.encode(storedBytes) } ?: return null

        val key = "exports/$documentId-p$pageIndex.jpg"
        val ref = contentStore.put(key, encoded.bytes)
        return ExportArtifact(
            ref = ref,
            mime = ExportArtifact.MIME_JPEG,
            displayName = sanitizeFileStem(document.title) + "_p${pageIndex + 1}.jpg",
            sizeBytes = encoded.bytes.size,
            pageCount = 1,
        )
    }

    /** Loads, encodes, and orders every page's image on the injected dispatcher. */
    private suspend fun loadPdfPages(pages: List<Page>): List<PdfPageImage>? =
        withContext(dispatcher) {
            val images = ArrayList<PdfPageImage>(pages.size)
            for (page in pages) {
                val storedBytes = openPageBytes(page) ?: return@withContext null
                val encoded: EncodedJpeg = encoder.encode(storedBytes) ?: return@withContext null
                images += PdfPageImage(
                    jpegBytes = encoded.bytes,
                    widthPx = encoded.widthPx,
                    heightPx = encoded.heightPx,
                    rotationDegrees = page.rotationDegrees,
                    colorComponents = encoded.colorComponents,
                )
            }
            images
        }

    private suspend fun openPageBytes(page: Page): ByteArray? {
        val ref = page.processedImageRef ?: return null
        return contentStore.open(ref)
    }
}

/**
 * Filesystem-safe stem for a display name: trims, replaces path separators
 * and other filename-hostile characters (`/ \ : * ? " < > |`) with `_`, and
 * falls back to "document" for blank titles.
 */
internal fun sanitizeFileStem(title: String): String {
    val cleaned = buildString(title.length) {
        for (char in title.trim()) {
            append(if (char in UNSAFE_FILENAME_CHARS) '_' else char)
        }
    }
    return if (cleaned.isEmpty()) FALLBACK_STEM else cleaned
}

private val UNSAFE_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
private const val FALLBACK_STEM = "document"
