package org.payswap.camscan.export.conversion

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.export.ExportArtifact
import org.payswap.camscan.tools.conversion.ConversionDocument
import org.payswap.camscan.tools.conversion.ConversionPage
import org.payswap.camscan.tools.conversion.DocxExportAdapter
import org.payswap.camscan.tools.conversion.PptxExportAdapter
import org.payswap.camscan.tools.conversion.XlsxExportAdapter
import org.payswap.camscan.tools.ui.ExportFormatCatalog

// CAMSCAN-VERIFY-002 — the Office conversion FLOW engine: stored
// document -> recognized text (the injected [PageTextSource] seam) ->
// the delivered text-level adapters -> an [ExportArtifact] in the
// app's export location, exactly matching the PROD-007 PDF/JPG export
// vocabulary ("exports/<docId>-<ts>-<n>p<ext>"). The adapter is
// selected through the format catalog id; the produced file lands in
// the ContentStore and the UI hands it to the share sheet.
//
// HONEST SCOPE: the docx/xlsx/pptx adapters are TEXT-LEVEL converters
// (layout parity explicitly UNVERIFIED — the picker carries the
// declaration, not a post-hoc toast). Null is returned — never a
// throw — when the document is unknown, has no pages, a page image is
// missing/unreadable, or text recognition fails for any page.
//
// Determinism: [TimeSource.nowMillis] is read exactly once per run,
// after the pages load; the single timestamp feeds the export key.

/** One Office conversion run's output (artifact + the source format id). */
data class OfficeExportResult(
    val artifact: ExportArtifact,
    val formatId: String,
)

/** Converts a stored document into an Office artifact via its text. */
class OfficeExportEngine(
    private val contentStore: ContentStore,
    private val textSource: PageTextSource,
    private val timeSource: TimeSource,
    private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Exports the document as the given format's Office artifact. The
     * format id must be one of pptx/docx/xlsx (the long-image format is
     * served by its own engine). Returns null on any honest failure.
     */
    suspend fun export(
        repository: DocumentRepository,
        documentId: String,
        formatId: String,
    ): OfficeExportResult? {
        val format = ExportFormatCatalog.byId(formatId) ?: return null
        if (format === ExportFormatCatalog.LONG_IMAGE) return null
        val document = repository.getDocument(documentId) ?: return null
        val pages = repository.getPages(documentId).sortedBy { it.index }
        if (pages.isEmpty()) return null

        val conversionPages = withContext(dispatcher) {
            val collected = ArrayList<ConversionPage>(pages.size)
            for (page in pages) {
                val ref = page.processedImageRef ?: return@withContext null
                val bytes = contentStore.open(ref) ?: return@withContext null
                val lines = textSource.textLinesFor(bytes, page.rotationDegrees)
                    ?: return@withContext null
                collected.add(ConversionPage(lines))
            }
            collected
        } ?: return null

        val conversionDocument = ConversionDocument(
            title = document.title,
            pages = conversionPages,
        )
        val bytes: ByteArray = when (format.formatId) {
            ExportFormatCatalog.PPTX.formatId -> PptxExportAdapter.export(conversionDocument)
            ExportFormatCatalog.DOCX.formatId -> DocxExportAdapter.export(conversionDocument)
            ExportFormatCatalog.XLSX.formatId -> XlsxExportAdapter.export(conversionDocument)
            else -> return null
        }
        val displayName: String = when (format.formatId) {
            ExportFormatCatalog.PPTX.formatId -> PptxExportAdapter.fileNameFor(document.title)
            ExportFormatCatalog.DOCX.formatId -> DocxExportAdapter.fileNameFor(document.title)
            ExportFormatCatalog.XLSX.formatId -> XlsxExportAdapter.fileNameFor(document.title)
            else -> return null
        }

        val timestamp = timeSource.nowMillis()
        val key = "exports/" + documentId + "-" + timestamp + "-" +
            pages.size + "p" + format.extension
        val storedRef = contentStore.put(key, bytes)
        val artifact = ExportArtifact(
            ref = storedRef,
            mime = mimeFor(format.formatId),
            displayName = displayName,
            sizeBytes = bytes.size,
            pageCount = pages.size,
        )
        return OfficeExportResult(artifact = artifact, formatId = format.formatId)
    }

    /** MIME type of each Office format. */
    fun mimeFor(formatId: String): String {
        return when (formatId) {
            ExportFormatCatalog.PPTX.formatId -> ExportArtifact.MIME_PPTX
            ExportFormatCatalog.DOCX.formatId -> ExportArtifact.MIME_DOCX
            ExportFormatCatalog.XLSX.formatId -> ExportArtifact.MIME_XLSX
            else -> ExportArtifact.MIME_PDF
        }
    }
}
