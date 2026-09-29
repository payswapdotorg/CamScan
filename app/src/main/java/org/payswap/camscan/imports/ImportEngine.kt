package org.payswap.camscan.imports

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.imports.pdf.PdfImporter

/**
 * Pixel geometry of a decodable image, as produced by [BoundsDecoder] — the
 * inJustDecodeBounds semantics without any decoded bitmap.
 */
data class ImageBounds(val widthPx: Int, val heightPx: Int)

/**
 * Seam between the engine and the platform content resolver
 * (CAMSCAN-PROD-008 §6.1): bytes + the picker's display name for the picked
 * URI. JVM tests fake it; the android implementation is
 * [org.payswap.camscan.imports.AndroidUriReader].
 */
interface UriReader {

    /** The URI's full byte content, or null when it cannot be opened/read. */
    suspend fun readBytes(uri: String): ByteArray?

    /** The picker's display name for the URI, or null when unobtainable. */
    suspend fun displayName(uri: String): String?
}

/**
 * Bounds-decoding seam with inJustDecodeBounds semantics: geometry only,
 * never a decoded bitmap. JVM tests use a pure fake; the android
 * implementation ([org.payswap.camscan.imports.AndroidBoundsDecoder]) reuses
 * the pure [org.payswap.camscan.export.pdf.JpegDimensionParser] for JPEG
 * bytes and BitmapFactory's inJustDecodeBounds for everything else.
 */
interface BoundsDecoder {

    /** Decoded geometry, or null when the bytes are not a decodable image. */
    fun decodeBounds(bytes: ByteArray): ImageBounds?
}

/** One image entering the import pipeline. */
data class ImportImageInput(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
)

/** One image leaving the import pipeline. */
data class ImportPageOutput(
    val bytes: ByteArray,
    val cropQuad: List<Corner>?,
    val enhancement: PageEnhancementMode,
)

/**
 * Routing seam into the delivered capture/processing surfaces
 * (CAMSCAN-PROD-008 §6.1): the DEFAULT implementation passes the image
 * through UNCHANGED (page semantics identical to a camera capture with no
 * detection/enhancement: cropQuad null, enhancement ORIGINAL). An android
 * adapter MAY delegate detection/enhancement to W1's seams read-only in a
 * later order; [org.payswap.camscan.capture] and
 * [org.payswap.camscan.processing] are never edited for this.
 */
interface ImportPagePipeline {

    suspend fun process(input: ImportImageInput): ImportPageOutput
}

/** The default pipeline: pass-through, no detection, no enhancement. */
object PassThroughImportPipeline : ImportPagePipeline {

    override suspend fun process(input: ImportImageInput): ImportPageOutput =
        ImportPageOutput(
            bytes = input.bytes,
            cropQuad = null,
            enhancement = PageEnhancementMode.ORIGINAL,
        )
}

/**
 * Image import engine (CAMSCAN-PROD-008 §6.1) — pure Kotlin, fully
 * JVM-testable: platform work (content reads, bounds decoding, PDF
 * rendering) hides behind the injected seams, ids behind [IdGenerator], time
 * behind [TimeSource].
 *
 * Documented caps:
 * - [MAX_IMPORT_BYTES] = 25 MB per image;
 * - [MAX_IMPORT_PIXELS] = 40,000,000 pixels per image.
 * A violation returns [ImportFailure.ImageTooLarge] echoing the numbers.
 *
 * ContentStore key vocabulary (documented; deterministic under the injected
 * ids and time — one timestamp read per import call feeds keys, page
 * timestamps and the fallback title alike):
 * - imported image original: `imports/<ts>-<pageId>.img` → sourceCaptureRef;
 * - imported image processed page: `imports/<ts>-<pageId>.page` →
 *   processedImageRef. This put happens ONLY when the pipeline's output
 *   differs from the original bytes; with the pass-through default the page
 *   image IS the stored original, so processedImageRef = sourceCaptureRef
 *   (one put, no duplicate storage — the honest representation of
 *   "passed through unchanged").
 *
 * Page semantics IDENTICAL to camera captures: sourceCaptureRef = the
 * imported original (non-destructive), processedImageRef = the page image,
 * cropQuad/enhancement from the pipeline (null/ORIGINAL by default),
 * rotationDegrees = 0 (imports carry no rotation metadata).
 *
 * Routing: a single-URI call whose bytes sniff as a PDF (`%PDF-` magic)
 * delegates to the injected [PdfImporter] — the single-pick picker's
 * product. In multi-URI calls every URI goes down the image path, where PDF
 * bytes fail honestly as [ImportFailure.ImageUndecodable] (a PDF cannot
 * join an image document; the single-pick picker never produces such a
 * selection).
 *
 * Flows (both all-or-nothing: a failure leaves zero store puts and zero
 * repository writes):
 * - [importAsNewDocument]: a NEW document (sourceType IMPORTED; title =
 *   picker display name when obtainable, else "Imported " + UTC timestamp
 *   through the injectable [titleFormatter]);
 * - [appendToDocument]: Page(s) at indices priorCount..n, the document's
 *   updatedAtMillis bumped, via upsertDocument(document, pages). Returns
 *   null (never throws) when the target document is unknown.
 */
class ImportEngine(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
    private val idGenerator: IdGenerator,
    private val timeSource: TimeSource,
    private val uriReader: UriReader,
    private val boundsDecoder: BoundsDecoder,
    private val pdfImporter: PdfImporter,
    private val pipeline: ImportPagePipeline = PassThroughImportPipeline,
    private val titleFormatter: (Long) -> String = DEFAULT_TITLE_FORMATTER,
) {

    /** Imports the picked URIs as one new IMPORTED document. */
    suspend fun importAsNewDocument(uris: List<String>): ImportResult {
        if (uris.isEmpty()) return ImportResult.Failure(ImportFailure.EmptySelection)

        val bytesByUri = readAll(uris)
        if (bytesByUri is ReadOutcome.Unreadable) {
            return ImportResult.Failure(ImportFailure.UriUnreadable(bytesByUri.uri))
        }
        val reads = (bytesByUri as ReadOutcome.Read).bytesByUri
        if (uris.size == 1) {
            val only = uris.single()
            if (isPdf(reads.getValue(only))) {
                return pdfImporter.importAsNewDocument(
                    bytes = reads.getValue(only),
                    title = resolveTitle(only, timeSource.nowMillis()),
                )
            }
        }
        return importImagesAsNewDocument(uris, reads)
    }

    /**
     * Appends the picked URIs to an existing document. Null when the
     * document is unknown (flag discipline); a content failure is an honest
     * [ImportResult.Failure] and leaves the document untouched.
     */
    suspend fun appendToDocument(documentId: String, uris: List<String>): ImportResult? {
        if (uris.isEmpty()) return ImportResult.Failure(ImportFailure.EmptySelection)

        val document = repository.getDocument(documentId) ?: return null
        val existingPages = repository.getPages(documentId).sortedBy { it.index }

        val bytesByUri = readAll(uris)
        if (bytesByUri is ReadOutcome.Unreadable) {
            return ImportResult.Failure(ImportFailure.UriUnreadable(bytesByUri.uri))
        }
        val reads = (bytesByUri as ReadOutcome.Read).bytesByUri
        if (uris.size == 1) {
            val only = uris.single()
            if (isPdf(reads.getValue(only))) {
                return pdfImporter.appendToDocument(documentId, reads.getValue(only))
            }
        }
        return appendImages(document, existingPages, uris, reads)
    }

    // ------------------------------------------------------------- flows

    private suspend fun importImagesAsNewDocument(
        uris: List<String>,
        bytesByUri: Map<String, ByteArray>,
    ): ImportResult {
        val prepared = when (val outcome = prepareImagePages(uris, bytesByUri)) {
            is Preparation.Rejected -> return ImportResult.Failure(outcome.failure)
            is Preparation.Ready -> outcome.pages
        }
        val documentId = idGenerator.newId()
        val now = timeSource.nowMillis()
        val pages = storePreparedPages(prepared, documentId, now)

        val document = Document(
            id = documentId,
            title = resolveTitle(uris.first(), now),
            pageIds = pages.map { it.id },
            sourceType = DocumentSource.IMPORTED,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        repository.upsertDocument(document, pages)
        return ImportResult.Success(document, pages)
    }

    private suspend fun appendImages(
        document: Document,
        existingPages: List<Page>,
        uris: List<String>,
        bytesByUri: Map<String, ByteArray>,
    ): ImportResult {
        val now = timeSource.nowMillis()

        val prepared = when (val outcome = prepareImagePages(uris, bytesByUri)) {
            is Preparation.Rejected -> return ImportResult.Failure(outcome.failure)
            is Preparation.Ready -> outcome.pages
        }
        val newPages = storePreparedPages(prepared, document.id, now)
            .mapIndexed { offset, page -> page.copy(index = existingPages.size + offset) }

        val allPages = existingPages + newPages
        val updated = document.copy(
            pageIds = allPages.map { it.id },
            updatedAtMillis = now,
        )
        repository.upsertDocument(updated, allPages)
        return ImportResult.Success(updated, allPages)
    }

    /**
     * Stores one prepared page pair: the original always, the processed page
     * only when the pipeline changed the bytes (see the class KDoc's key
     * vocabulary). Returns the finished core [Page]s with coherent indices.
     */
    private suspend fun storePreparedPages(
        prepared: List<PreparedImagePage>,
        documentId: String,
        now: Long,
    ): List<Page> = prepared.mapIndexed { index, item ->
        val pageId = idGenerator.newId()
        val originalRef = contentStore.put("imports/$now-$pageId.img", item.originalBytes)
        val processedRef = if (item.output.bytes.contentEquals(item.originalBytes)) {
            originalRef
        } else {
            contentStore.put("imports/$now-$pageId.page", item.output.bytes)
        }
        Page(
            id = pageId,
            documentId = documentId,
            index = index,
            sourceCaptureRef = originalRef,
            processedImageRef = processedRef,
            cropQuad = item.output.cropQuad,
            enhancement = item.output.enhancement,
            rotationDegrees = 0,
            ocrResultId = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
    }

    // --------------------------------------------------------- validation

    private sealed interface Preparation {
        data class Ready(val pages: List<PreparedImagePage>) : Preparation
        data class Rejected(val failure: ImportFailure) : Preparation
    }

    /**
     * Validates and pipeline-processes every URI BEFORE any store put or
     * repository write: bounds decode → pixel cap → byte cap → pipeline.
     * The first violation rejects the whole call (all-or-nothing).
     */
    private suspend fun prepareImagePages(
        uris: List<String>,
        bytesByUri: Map<String, ByteArray>,
    ): Preparation {
        val prepared = ArrayList<PreparedImagePage>(uris.size)
        for (uri in uris) {
            val bytes = bytesByUri.getValue(uri)
            val bounds = boundsDecoder.decodeBounds(bytes)
                ?: return Preparation.Rejected(ImportFailure.ImageUndecodable(uri))
            val pixelCount = bounds.widthPx.toLong() * bounds.heightPx.toLong()
            val byteCount = bytes.size.toLong()
            if (pixelCount > MAX_IMPORT_PIXELS || byteCount > MAX_IMPORT_BYTES) {
                return Preparation.Rejected(
                    ImportFailure.ImageTooLarge(
                        uri = uri,
                        pixelCount = pixelCount,
                        maxPixels = MAX_IMPORT_PIXELS,
                        byteCount = byteCount,
                        maxBytes = MAX_IMPORT_BYTES,
                    ),
                )
            }
            val output = pipeline.process(
                ImportImageInput(bytes = bytes, widthPx = bounds.widthPx, heightPx = bounds.heightPx),
            )
            prepared += PreparedImagePage(originalBytes = bytes, output = output)
        }
        return Preparation.Ready(prepared)
    }

    private data class PreparedImagePage(
        val originalBytes: ByteArray,
        val output: ImportPageOutput,
    )

    // ------------------------------------------------------------ helpers

    /** Reads every URI up front; the first unreadable one fails the call. */
    private suspend fun readAll(uris: List<String>): ReadOutcome {
        val result = LinkedHashMap<String, ByteArray>(uris.size)
        for (uri in uris) {
            val bytes = uriReader.readBytes(uri) ?: return ReadOutcome.Unreadable(uri)
            result[uri] = bytes
        }
        return ReadOutcome.Read(result)
    }

    private sealed interface ReadOutcome {
        data class Read(val bytesByUri: Map<String, ByteArray>) : ReadOutcome
        data class Unreadable(val uri: String) : ReadOutcome
    }

    /** Title rule: picker display name when obtainable, else the formatter. */
    private suspend fun resolveTitle(uri: String, now: Long): String {
        val name = uriReader.displayName(uri)
        return if (name.isNullOrBlank()) titleFormatter(now) else name
    }

    companion object {
        /** Documented per-image byte cap: 25 MB. */
        const val MAX_IMPORT_BYTES: Long = 25L * 1024L * 1024L

        /** Documented per-image pixel cap: 40,000,000 pixels. */
        const val MAX_IMPORT_PIXELS: Long = 40_000_000L

        /**
         * Default fallback title formatter: "Imported yyyy-MM-dd HH:mm" in
         * UTC (fixed pattern, injected time — mirrors the capture flow's
         * "Scan yyyy-MM-dd HH:mm" convention).
         */
        val DEFAULT_TITLE_FORMATTER: (Long) -> String = { millis ->
            "Imported " + TITLE_TIMESTAMP_FORMAT
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(millis))
        }

        private val TITLE_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** PDF magic prefix sniff (`%PDF-`), the documented routing test. */
        internal fun isPdf(bytes: ByteArray): Boolean =
            bytes.size >= PDF_MAGIC.size &&
                PDF_MAGIC.indices.all { bytes[it] == PDF_MAGIC[it] }

        private val PDF_MAGIC = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte())
    }
}
