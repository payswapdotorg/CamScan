package org.payswap.camscan.imports

import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.imports.pdf.PdfDocumentOpener
import org.payswap.camscan.imports.pdf.PdfDocumentPages
import org.payswap.camscan.imports.pdf.PdfImporter
import org.payswap.camscan.imports.pdf.PdfPageSize
import org.payswap.camscan.ocr.FakeTimeSource

/**
 * JVM tests for [ImportEngine] (CAMSCAN-PROD-008 §6.7) with the established
 * fakes: [InMemoryDocumentRepository], [FakeTimeSource], a local recording
 * [ContentStore], plus pure fake UriReader/BoundsDecoder seams and a fake
 * PDF opener (fake rendered bytes — platform PdfRenderer is lead-station
 * only). Covers the full error taxonomy with echoed numbers, both flows,
 * the key vocabulary, and the page-field semantics.
 */
class ImportEngineTest {

    // -------------------------------------------------------- taxonomy

    @Test
    fun emptySelection_failsWithNoSideEffects() = runTest {
        val fixture = Fixture()

        val newDoc = fixture.engine.importAsNewDocument(emptyList())
        val appended = fixture.engine.appendToDocument(DOC_ID, emptyList())

        assertEquals(ImportFailure.EmptySelection, (newDoc as ImportResult.Failure).failure)
        assertEquals(ImportFailure.EmptySelection, (appended as ImportResult.Failure).failure)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun unreadableUri_failsWithEcho() = runTest {
        val fixture = Fixture() // "content://gone" reads null.

        val result = fixture.engine.importAsNewDocument(listOf("content://gone"))

        val failure = (result as ImportResult.Failure).failure as ImportFailure.UriUnreadable
        assertEquals("content://gone", failure.uri)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun undecodableImage_failsWithEcho() = runTest {
        val fixture = Fixture()
        // Adjudication (watch-cycle-24b): the ENGINE is right — a null
        // decodeBounds signal already fails as the ImageUndecodable VALUE
        // echoing the URI (packet §6.1), with zero side effects. The old
        // fixture was blind: seedImage marked EVERY payload decodable, so
        // the fake never signalled undecodable and the engine honestly
        // imported the bytes. Fixture fixed (decodableImage = false).
        val uri = fixture.seedImage(bytes = "NOT-AN-IMAGE".toByteArray(), displayName = null, decodableImage = false)

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        val failure = (result as ImportResult.Failure).failure as ImportFailure.ImageUndecodable
        assertEquals(uri, failure.uri)
        assertTrue("all-or-nothing: zero puts", fixture.store.keys.isEmpty())
    }

    @Test
    fun pixelCapViolation_failsEchoingAllNumbers() = runTest {
        val fixture = Fixture()
        // 7000 x 6000 = 42,000,000 pixels > 40,000,000.
        val uri = fixture.seedImage(
            bytes = PAYLOAD_A.toByteArray(),
            displayName = null,
            bounds = ImageBounds(7_000, 6_000),
        )

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        val failure = (result as ImportResult.Failure).failure as ImportFailure.ImageTooLarge
        assertEquals(42_000_000L, failure.pixelCount)
        assertEquals(ImportEngine.MAX_IMPORT_PIXELS, failure.maxPixels)
        assertEquals(PAYLOAD_A.length.toLong(), failure.byteCount)
        assertEquals(ImportEngine.MAX_IMPORT_BYTES, failure.maxBytes)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun byteCapViolation_failsEchoingAllNumbers() = runTest {
        val fixture = Fixture()
        val oversize = ByteArray(ImportEngine.MAX_IMPORT_BYTES.toInt() + 1) { 'x'.code.toByte() }
        val uri = fixture.seedImage(bytes = oversize, displayName = null)

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        val failure = (result as ImportResult.Failure).failure as ImportFailure.ImageTooLarge
        assertEquals(oversize.size.toLong(), failure.byteCount)
        assertEquals(ImportEngine.MAX_IMPORT_BYTES, failure.maxBytes)
        // Bounds were decodable, so the pixel numbers are echoed too.
        assertEquals(
            DEFAULT_BOUNDS.widthPx.toLong() * DEFAULT_BOUNDS.heightPx.toLong(),
            failure.pixelCount,
        )
        assertEquals(ImportEngine.MAX_IMPORT_PIXELS, failure.maxPixels)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun documentCaps_areTheDocumentedValues() {
        assertEquals(25L * 1024L * 1024L, ImportEngine.MAX_IMPORT_BYTES)
        assertEquals(40_000_000L, ImportEngine.MAX_IMPORT_PIXELS)
    }

    // ------------------------------------------------- new-document flow

    @Test
    fun singleImage_createsImportedDocumentWithCaptureSemantics() = runTest {
        val fixture = Fixture()
        val uri = fixture.seedImage(PAYLOAD_A.toByteArray(), displayName = "Receipt.jpg")

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        val success = result as ImportResult.Success
        val document = success.document
        val page = success.pages.single()

        assertEquals(DocumentSource.IMPORTED, document.sourceType)
        assertEquals("Receipt.jpg", document.title)
        assertEquals(listOf(page.id), document.pageIds)
        assertEquals(FIXED_MILLIS, document.createdAtMillis)
        assertEquals(FIXED_MILLIS, document.updatedAtMillis)

        // Ids: doc id minted first, then the page id.
        assertEquals("id-0", document.id)
        assertEquals("id-1", page.id)
        assertEquals(document.id, page.documentId)
        assertEquals(0, page.index)
        assertEquals("imports/$FIXED_MILLIS-id-1.img", page.sourceCaptureRef)
        // Pass-through pipeline: the page image IS the stored original.
        assertEquals(page.sourceCaptureRef, page.processedImageRef)
        assertNull(page.cropQuad)
        assertEquals(PageEnhancementMode.ORIGINAL, page.enhancement)
        assertEquals(0, page.rotationDegrees)
        assertEquals(FIXED_MILLIS, page.createdAtMillis)
        assertEquals(FIXED_MILLIS, page.updatedAtMillis)

        // Key vocabulary + ref recording: exactly ONE put.
        assertEquals(listOf("imports/$FIXED_MILLIS-id-1.img"), fixture.store.keys)
        assertArrayEquals(PAYLOAD_A.toByteArray(), fixture.store.stored[page.processedImageRef])
        assertEquals(document, fixture.repository.getDocument(document.id))
    }

    @Test
    fun titleRule_fallsBackToInjectedFormatter() = runTest {
        val fixture = Fixture(titleFormatter = { "Imported#" + it })
        val uri = fixture.seedImage(PAYLOAD_A.toByteArray(), displayName = null)

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        assertEquals("Imported#$FIXED_MILLIS", (result as ImportResult.Success).document.title)
    }

    @Test
    fun titleRule_defaultFormatterIsUtcFixedPattern() {
        // Adjudication (watch-cycle-24b): the ENGINE is right per packet
        // §6.1 — "Imported " + UTC timestamp via an injectable fixed-pattern
        // formatter fed by the injected TimeSource. The old expectation
        // conflated seconds with milliseconds: 1,000,000 ms is
        // 1970-01-01T00:16:40Z (the engine's rendering); 1970-01-12T13:46:40Z
        // is 1,000,000,000 ms. Blind test expectation fixed, not the engine.
        assertEquals("Imported 1970-01-01 00:16", ImportEngine.DEFAULT_TITLE_FORMATTER(1_000_000L))
        assertEquals("Imported 1970-01-12 13:46", ImportEngine.DEFAULT_TITLE_FORMATTER(1_000_000_000L))
        // Zone independence, tested honestly: swap the host default zone and
        // assert the fixed UTC rendering does not move.
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals("Imported 1970-01-12 13:46", ImportEngine.DEFAULT_TITLE_FORMATTER(1_000_000_000L))
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun multiImage_singleDocumentWithCoherentIndices() = runTest {
        val fixture = Fixture()
        val first = fixture.seedImage(PAYLOAD_A.toByteArray(), displayName = "Two pages")
        val second = fixture.seedImage(PAYLOAD_B.toByteArray(), displayName = null)

        val result = fixture.engine.importAsNewDocument(listOf(first, second))

        val success = result as ImportResult.Success
        assertEquals(2, success.pages.size)
        assertEquals(listOf(0, 1), success.pages.map { it.index })
        assertEquals(
            listOf("imports/$FIXED_MILLIS-id-1.img", "imports/$FIXED_MILLIS-id-2.img"),
            fixture.store.keys,
        )
        assertEquals(success.pages.map { it.id }, success.document.pageIds)
        assertEquals("Two pages", success.document.title)
    }

    @Test
    fun transformingPipeline_storesProcessedPageUnderSecondKey() = runTest {
        val fixture = Fixture(
            pipeline = { input ->
                ImportPageOutput(
                    bytes = ("PROCESSED(" + String(input.bytes, Charsets.US_ASCII) + ")").toByteArray(),
                    cropQuad = listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f)),
                    enhancement = PageEnhancementMode.GRAYSCALE,
                )
            },
        )
        val uri = fixture.seedImage(PAYLOAD_A.toByteArray(), displayName = null)

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        val page = (result as ImportResult.Success).pages.single()
        assertEquals(
            listOf("imports/$FIXED_MILLIS-id-1.img", "imports/$FIXED_MILLIS-id-1.page"),
            fixture.store.keys,
        )
        assertEquals("imports/$FIXED_MILLIS-id-1.img", page.sourceCaptureRef)
        assertEquals("imports/$FIXED_MILLIS-id-1.page", page.processedImageRef)
        assertEquals(4, page.cropQuad!!.size)
        assertEquals(PageEnhancementMode.GRAYSCALE, page.enhancement)
        assertArrayEquals(
            ("PROCESSED(" + PAYLOAD_A + ")").toByteArray(),
            fixture.store.stored[page.processedImageRef],
        )
        assertArrayEquals(PAYLOAD_A.toByteArray(), fixture.store.stored[page.sourceCaptureRef])
    }

    // ----------------------------------------------------- append flow

    @Test
    fun appendToDocument_appendsAtPriorCountWithBumpedTimestamp() = runTest {
        val fixture = Fixture()
        fixture.seedExistingDocument()
        val first = fixture.seedImage(PAYLOAD_B.toByteArray(), displayName = null)
        val second = fixture.seedImage(PAYLOAD_C.toByteArray(), displayName = null)

        val result = fixture.engine.appendToDocument(DOC_ID, listOf(first, second))

        val success = result as ImportResult.Success
        val pages = success.pages
        assertEquals(4, pages.size)
        // Index continuity: priorCount..n.
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.index })
        // Existing pages untouched.
        assertEquals("seed-p0", pages[0].id)
        assertEquals("seed-p1", pages[1].id)
        // New pages appended with import semantics.
        assertEquals("imports/$FIXED_MILLIS-id-0.img", pages[2].sourceCaptureRef)
        assertEquals("imports/$FIXED_MILLIS-id-0.img", pages[2].processedImageRef)
        assertEquals("imports/$FIXED_MILLIS-id-1.img", pages[3].sourceCaptureRef)
        assertEquals(4, pages.map { it.id }.toSet().size)
        assertEquals(pages.map { it.id }, success.document.pageIds)
        assertEquals(FIXED_MILLIS, success.document.updatedAtMillis)
        assertEquals(
            listOf("imports/$FIXED_MILLIS-id-0.img", "imports/$FIXED_MILLIS-id-1.img"),
            fixture.store.keys,
        )
    }

    @Test
    fun appendToDocument_unknownDocument_returnsNull() = runTest {
        val fixture = Fixture()
        val uri = fixture.seedImage(PAYLOAD_A.toByteArray(), displayName = null)

        assertNull(fixture.engine.appendToDocument("no-such-doc", listOf(uri)))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun appendToDocument_failureLeavesDocumentUntouched() = runTest {
        val fixture = Fixture()
        fixture.seedExistingDocument()
        // Adjudication (watch-cycle-24b): same blind fixture as
        // undecodableImage_failsWithEcho — the payload must be seeded
        // UNDECODABLE for the engine's null-signal path to run. The engine
        // validates everything before any store put or repository write
        // (all-or-nothing, packet §6.1), so the failure leaves the document
        // byte-identical: no upsert, no puts.
        val uri = fixture.seedImage("NOT-AN-IMAGE".toByteArray(), displayName = null, decodableImage = false)

        val result = fixture.engine.appendToDocument(DOC_ID, listOf(uri))

        assertTrue(result is ImportResult.Failure)
        assertEquals(2, fixture.repository.getPages(DOC_ID).size)
        assertTrue(fixture.store.keys.isEmpty())
    }

    // ------------------------------------------------------ PDF routing

    @Test
    fun singlePdfUri_delegatesToPdfImporter_newDocument() = runTest {
        val fixture = Fixture(pdfPages = 2)
        val uri = fixture.seedImage(PDF_BYTES, displayName = "Manual.pdf")

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        val success = result as ImportResult.Success
        assertEquals("Manual.pdf", success.document.title)
        assertEquals(DocumentSource.IMPORTED, success.document.sourceType)
        assertEquals(2, success.pages.size)
        // Key vocabulary: original once + one JPEG per page.
        assertEquals(
            listOf(
                "imports/$FIXED_MILLIS-pdfid-1.pdf",
                "imports/$FIXED_MILLIS-pdfid-1-p0.jpg",
                "imports/$FIXED_MILLIS-pdfid-1-p1.jpg",
            ),
            fixture.store.keys,
        )
        // Every page's non-destructive original is the stored PDF.
        assertTrue(success.pages.all { it.sourceCaptureRef == "imports/$FIXED_MILLIS-pdfid-1.pdf" })
        assertArrayEquals(PDF_BYTES, fixture.store.stored["imports/$FIXED_MILLIS-pdfid-1.pdf"])
    }

    @Test
    fun singlePdfUri_routingFailurePassesThrough() = runTest {
        val fixture = Fixture(pdfUnreadable = true)
        val uri = fixture.seedImage(PDF_BYTES, displayName = null)

        val result = fixture.engine.importAsNewDocument(listOf(uri))

        assertEquals(ImportFailure.PdfUnreadable, (result as ImportResult.Failure).failure)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun appendPdfUri_delegatesToPdfImporter_appendFlow() = runTest {
        val fixture = Fixture(pdfPages = 2)
        fixture.seedExistingDocument()
        val uri = fixture.seedImage(PDF_BYTES, displayName = null)

        val result = fixture.engine.appendToDocument(DOC_ID, listOf(uri))

        val success = result as ImportResult.Success
        assertEquals(4, success.pages.size)
        assertEquals(listOf(0, 1, 2, 3), success.pages.map { it.index })
        assertTrue(success.pages.drop(2).all { it.documentId == DOC_ID })
        assertTrue(
            "appended page refs use the pdf vocabulary",
            success.pages[2].processedImageRef == "imports/$FIXED_MILLIS-pdfid-0-p0.jpg",
        )
    }

    @Test
    fun multiUriSelection_containingPdf_failsHonestlyAsImage() = runTest {
        val fixture = Fixture()
        val image = fixture.seedImage(PAYLOAD_A.toByteArray(), displayName = null)
        val pdf = fixture.seedImage(PDF_BYTES, displayName = null)

        val result = fixture.engine.importAsNewDocument(listOf(image, pdf))

        // Documented: a PDF cannot join a multi-image selection — it fails
        // as an undecodable image rather than being dropped silently.
        val failure = (result as ImportResult.Failure).failure as ImportFailure.ImageUndecodable
        assertEquals(pdf, failure.uri)
        assertTrue(fixture.store.keys.isEmpty())
    }

    // --------------------------------------------------------- fixtures

    private class FakeUriReader(
        private val bytesByUri: MutableMap<String, ByteArray>,
        private val namesByUri: MutableMap<String, String?>,
    ) : UriReader {
        override suspend fun readBytes(uri: String): ByteArray? = bytesByUri[uri]

        override suspend fun displayName(uri: String): String? = namesByUri[uri]
    }

    private class FakeBoundsDecoder(
        private val decodable: Set<String>,
        private val boundsByPayload: Map<String, ImageBounds>,
    ) : BoundsDecoder {
        override fun decodeBounds(bytes: ByteArray): ImageBounds? {
            val payload = String(bytes, Charsets.US_ASCII)
            // A real decoder cannot extract image geometry from PDF bytes.
            if (payload.startsWith("%PDF-")) return null
            if (payload !in decodable) return null
            return boundsByPayload[payload] ?: DEFAULT_BOUNDS
        }
    }

    /** Fake PDF source: fixed page geometry + deterministic rendered bytes. */
    private class FakePdfPages(
        override val pageCount: Int,
        private val pageSize: PdfPageSize = PdfPageSize(595, 842),
    ) : PdfDocumentPages {
        val renderedQualities = mutableListOf<Int>()

        override fun pageSizePoints(pageIndex: Int): PdfPageSize? = pageSize

        override fun renderPageJpeg(
            pageIndex: Int,
            widthPx: Int,
            heightPx: Int,
            quality: Int,
        ): ByteArray? {
            renderedQualities += quality
            return "PDF-PAGE-$pageIndex-${widthPx}x$heightPx".toByteArray()
        }

        override fun close() = Unit
    }

    private class FakePdfOpener(
        private val pagesToServe: FakePdfPages?,
        private val onOpen: (ByteArray) -> Unit = {},
    ) : PdfDocumentOpener {
        override fun open(bytes: ByteArray): PdfDocumentPages? {
            onOpen(bytes)
            return pagesToServe
        }
    }

    /** In-memory store recording key vocabulary verbatim. */
    private class FakeContentStore : ContentStore {
        val stored = LinkedHashMap<String, ByteArray>()
        val keys = mutableListOf<String>()

        override suspend fun put(key: String, bytes: ByteArray): String {
            keys += key
            stored[key] = bytes
            return key
        }

        override suspend fun open(ref: String): ByteArray? = stored[ref]

        override suspend fun delete(ref: String): Boolean = stored.remove(ref) != null

        override suspend fun exists(ref: String): Boolean = stored.containsKey(ref)
    }

    private inner class Fixture(
        private val pdfPages: Int = 0,
        private val pdfUnreadable: Boolean = false,
        titleFormatter: (Long) -> String = ImportEngine.DEFAULT_TITLE_FORMATTER,
        pipeline: suspend (ImportImageInput) -> ImportPageOutput = { input ->
            ImportPageOutput(input.bytes, null, PageEnhancementMode.ORIGINAL)
        },
    ) {
        val store = FakeContentStore()
        val repository = InMemoryDocumentRepository(FakeTimeSource())
        private val idCounter = IntArray(1)
        val idGenerator = IdGenerator {
            val value = "id-" + idCounter[0]
            idCounter[0] += 1
            value
        }
        private val bytesByUri = HashMap<String, ByteArray>()
        private val namesByUri = HashMap<String, String?>()
        private val boundsByPayload = HashMap<String, ImageBounds>()
        private val decodable = HashSet<String>()

        val pdfFakePages = if (pdfUnreadable) null else FakePdfPages(pdfPages)
        private val pdfOpener = FakePdfOpener(pdfFakePages)

        val engine = ImportEngine(
            repository = repository,
            contentStore = store,
            idGenerator = idGenerator,
            timeSource = FakeTimeSource(startMillis = FIXED_MILLIS),
            uriReader = FakeUriReader(bytesByUri, namesByUri),
            boundsDecoder = FakeBoundsDecoder(decodable, boundsByPayload),
            pdfImporter = PdfImporter(
                repository = repository,
                contentStore = store,
                idGenerator = idGenerator,
                timeSource = FakeTimeSource(startMillis = FIXED_MILLIS),
                opener = pdfOpener,
                dispatcher = Dispatchers.Unconfined,
            ),
            pipeline = object : ImportPagePipeline {
                override suspend fun process(input: ImportImageInput): ImportPageOutput =
                    pipeline(input)
            },
            titleFormatter = titleFormatter,
        )

        fun seedImage(
            bytes: ByteArray,
            displayName: String?,
            bounds: ImageBounds? = null,
            decodableImage: Boolean = true,
        ): String {
            val uri = "content://import/" + bytesByUri.size
            bytesByUri[uri] = bytes
            namesByUri[uri] = displayName
            if (decodableImage) {
                decodable += String(bytes, Charsets.US_ASCII)
            }
            if (bounds != null) {
                boundsByPayload[String(bytes, Charsets.US_ASCII)] = bounds
            }
            return uri
        }

        fun seedExistingDocument() {
            val document = Document(
                id = DOC_ID,
                title = "Existing",
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
            )
            val pages = listOf(
                Page(
                    id = "seed-p0",
                    documentId = DOC_ID,
                    index = 0,
                    processedImageRef = "seed-ref-0",
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                ),
                Page(
                    id = "seed-p1",
                    documentId = DOC_ID,
                    index = 1,
                    processedImageRef = "seed-ref-1",
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                ),
            )
            store.stored["seed-ref-0"] = "SEED-0".toByteArray()
            store.stored["seed-ref-1"] = "SEED-1".toByteArray()
            kotlinx.coroutines.runBlocking {
                repository.upsertDocument(document, pages)
            }
        }

        suspend fun repositoryCallCount(): Int = repository.getPages(DOC_ID).size
    }

    private companion object {
        const val DOC_ID = "doc-under-test"
        const val FIXED_MILLIS = 1_000_000L
        const val PAYLOAD_A = "STORED-IMPORT-IMAGE-ALPHA"
        const val PAYLOAD_B = "STORED-IMPORT-IMAGE-BRAVO"
        const val PAYLOAD_C = "STORED-IMPORT-IMAGE-CHARLIE"
        val PDF_BYTES = "%PDF-1.4\nfake-imported-pdf\n%%EOF".toByteArray()

        /** Fake default geometry: small, within every cap. */
        val DEFAULT_BOUNDS = ImageBounds(128, 96)
    }
}
