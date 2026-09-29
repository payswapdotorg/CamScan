package org.payswap.camscan.export.compress

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.export.ExportArtifact
import org.payswap.camscan.export.pdf.PageImageEncoder
import org.payswap.camscan.export.sanitizeFileStem

/**
 * Result of a compressed export (CAMSCAN-PROD-008 §6.5): the stored
 * [ExportArtifact] — its UNCHANGED PROD-007 shape, with sizeBytes reporting
 * the COMPRESSED size — plus the honest [report] carrying the delta.
 */
data class CompressedExportResult(
    val artifact: ExportArtifact,
    val report: CompressionReport,
)

/**
 * Compressed-PDF export engine (CAMSCAN-PROD-008 §6.5): a stored document →
 * resampled pages → one compressed PDF artifact written THROUGH the
 * [ContentStore]. Pure Kotlin: page images arrive through PROD-007's
 * [PageImageEncoder] seam, resampling through [ImageResampler], PDF
 * assembly through [CompressedPdfWriter] (which drives the real PdfWriter
 * behind its [PdfAssembler] seam — page order and rotation identical to
 * normal export).
 *
 * Key vocabulary (documented, per the work order): compressed PDF artifacts
 * use `exports/<docId>-<ts>-<n>p-c.pdf` (the `-c` marker distinguishes them
 * from PROD-007's plain `exports/<docId>-<ts>-<n>p.pdf`). Determinism:
 * exactly one [TimeSource.nowMillis] read per export feeds the key and the
 * PDF's /CreationDate, mirroring ExportEngine.
 *
 * API contract: returns null (never throws) when the document is unknown,
 * has no pages, a page's image is missing/unreadable/undecodable, or a page
 * fails to resample — the failure reasons are honest; compressedBytes MAY
 * exceed originalBytes for already-small inputs and the engine NEVER
 * silently falls back to uncompressed bytes.
 */
class CompressExportEngine(
    private val contentStore: ContentStore,
    private val encoder: PageImageEncoder,
    private val resampler: ImageResampler,
    private val timeSource: TimeSource,
    private val dispatcher: CoroutineDispatcher,
) {

    private val writer = CompressedPdfWriter(resampler)

    /** Exports the whole document as one COMPRESSED multi-page PDF artifact. */
    suspend fun compressToPdf(
        repository: DocumentRepository,
        documentId: String,
        options: CompressPdfOptions = CompressPdfOptions(),
    ): CompressedExportResult? {
        val document = repository.getDocument(documentId) ?: return null
        val pages = repository.getPages(documentId).sortedBy { it.index }
        if (pages.isEmpty()) return null

        val pageInputs = withContext(dispatcher) { loadPageInputs(pages) } ?: return null

        val timestamp = timeSource.nowMillis()
        val output = writer.write(pageInputs, options, timestamp) ?: return null

        val key = "exports/$documentId-$timestamp-${pages.size}p-c.pdf"
        val ref = contentStore.put(key, output.pdfBytes)
        val artifact = ExportArtifact(
            ref = ref,
            mime = ExportArtifact.MIME_PDF,
            displayName = sanitizeFileStem(document.title) + ".pdf",
            sizeBytes = output.pdfBytes.size,
            pageCount = pages.size,
        )
        return CompressedExportResult(artifact = artifact, report = output.report)
    }

    /** Loads, encodes, and orders every page's image input; null on any failure. */
    private suspend fun loadPageInputs(pages: List<Page>): List<CompressPageInput>? {
        val inputs = ArrayList<CompressPageInput>(pages.size)
        for (page in pages) {
            val ref = page.processedImageRef ?: return null
            val storedBytes = contentStore.open(ref) ?: return null
            val encoded = encoder.encode(storedBytes) ?: return null
            inputs += CompressPageInput(
                jpegBytes = encoded.bytes,
                widthPx = encoded.widthPx,
                heightPx = encoded.heightPx,
                rotationDegrees = page.rotationDegrees,
                colorComponents = encoded.colorComponents,
            )
        }
        return inputs
    }
}
