package org.payswap.camscan.imports.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.imports.ImportFailure
import org.payswap.camscan.imports.ImportResult
import org.payswap.camscan.ocr.FakeTimeSource

// JVM tests for PdfImporter (CAMSCAN-PROD-008 §6.2/§6.7) on FAKE rendered
// bytes behind the PdfDocumentOpener seam — the platform PdfRenderer is
// lead-station-only (rendered bytes are platform-rendered and vary across
// Android versions; the PIPELINE here is deterministic given fixed rendered
// bytes + injected ids/time). Covers page count -> Page count/order, the
// 50-page cap echo, mid-render all-or-nothing, unopenable PDFs, the append
// flow, quality clamping, and the documented bounded render scale.
class PdfImporterTest {

    // ------------------------------------------------------ import as new

    @Test
    fun pageCount_drivesPageCountAndOrder() = runTest {
        val fixture = Fixture(pageCount = 3)

        val result = fixture.importer.importAsNewDocument(PDF_BYTES, "Manual.pdf") as ImportResult.Success

        assertEquals(3, result.pages.size)
        assertEquals((0..2).toList(), result.pages.map { it.index })
        assertEquals("pdf-id-0", result.document.id)
        assertEquals(result.pages.map { it.id }, result.document.pageIds)
        assertTrue(result.pages.all { it.documentId == result.document.id })
        assertEquals("Manual.pdf", result.document.title)
        assertEquals(DocumentSource.IMPORTED, result.document.sourceType)
        assertEquals(FIXED_MILLIS, result.document.createdAtMillis)
        assertEquals(FIXED_MILLIS, result.document.updatedAtMillis)

        // Key vocabulary: the original stored ONCE, then one JPEG per PDF
        // page in 0-based order. Id minting order: document, import, pages.
        assertEquals(
            listOf(
                "imports/" + FIXED_MILLIS + "-pdfpdf-id-1.pdf",
                "imports/" + FIXED_MILLIS + "-pdfpdf-id-1-p0.jpg",
                "imports/" + FIXED_MILLIS + "-pdfpdf-id-1-p1.jpg",
                "imports/" + FIXED_MILLIS + "-pdfpdf-id-1-p2.jpg",
            ),
            fixture.store.keys,
        )
        // Every page's non-destructive original is the stored PDF itself.
        assertTrue(result.pages.all { it.sourceCaptureRef == "imports/" + FIXED_MILLIS + "-pdfpdf-id-1.pdf" })
        assertArrayEquals(PDF_BYTES, fixture.store.stored["imports/" + FIXED_MILLIS + "-pdfpdf-id-1.pdf"])
        assertEquals(
            "RENDERED-PAGE-1",
            String(fixture.store.stored["imports/" + FIXED_MILLIS + "-pdfpdf-id-1-p1.jpg"]!!),
        )
        // Page semantics identical to a camera capture without processing.
        assertTrue(result.pages.all { it.cropQuad == null && it.rotationDegrees == 0 })
        assertTrue(result.pages.all { it.enhancement == PageEnhancementMode.ORIGINAL })
        assertTrue(result.pages.all { it.createdAtMillis == FIXED_MILLIS })
        // The renderer surface is CLOSED after the import (AutoCloseable use).
        assertTrue("renderer closed after use", fixture.pages!!.closed)
    }

    @Test
    fun beyondCap_failsEchoingFoundAndCap_zeroSideEffects() = runTest {
        val fixture = Fixture(pageCount = PdfImporter.MAX_PDF_IMPORT_PAGES + 1)

        val result = fixture.importer.importAsNewDocument(PDF_BYTES, "Big.pdf")

        val failure = (result as ImportResult.Failure).failure as ImportFailure.PdfTooManyPages
        assertEquals(51, failure.foundPages)
        assertEquals(50, failure.maxPages)
        assertEquals(50, PdfImporter.MAX_PDF_IMPORT_PAGES)
        // All-or-nothing: nothing stored, nothing upserted.
        assertTrue(fixture.store.keys.isEmpty())
        assertTrue(fixture.repository.observeDocuments().first().isEmpty())
    }

    @Test
    fun midRenderFailure_isAllOrNothing() = runTest {
        val fixture = Fixture(pageCount = 3, failRenderAt = setOf(1))

        val result = fixture.importer.importAsNewDocument(PDF_BYTES, "Broken.pdf")

        val failure = (result as ImportResult.Failure).failure as ImportFailure.PdfPageRenderFailed
        assertEquals(1, failure.pageIndex)
        // Page 0 rendered fine, page 1 failed: NO partial upsert, ZERO store
        // residue — every page renders BEFORE any put or repository write.
        assertTrue(fixture.store.keys.isEmpty())
        assertTrue(fixture.repository.observeDocuments().first().isEmpty())
        // Page 0 WAS rendered (the boundary is render-time, not skip-ahead).
        assertEquals(listOf("p0@1190x1684q85"), fixture.pages!!.renders)
    }

    @Test
    fun unusablePageSize_failsEchoingThePageIndex() = runTest {
        val fixture = Fixture(pageCount = 3, failSizeAt = setOf(2))

        val result = fixture.importer.importAsNewDocument(PDF_BYTES, "Geometry.pdf")

        val failure = (result as ImportResult.Failure).failure as ImportFailure.PdfPageRenderFailed
        assertEquals(2, failure.pageIndex)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun unopenablePdf_failsPdfUnreadable_zeroSideEffects() = runTest {
        val fixture = Fixture(unopenable = true)

        val result = fixture.importer.importAsNewDocument(PDF_BYTES, "Locked.pdf")

        assertEquals(ImportFailure.PdfUnreadable, (result as ImportResult.Failure).failure)
        assertTrue(fixture.store.keys.isEmpty())
        assertTrue(fixture.repository.observeDocuments().first().isEmpty())
    }

    @Test
    fun openerThrowing_failsPdfUnreadable_notAnException() = runTest {
        val fixture = Fixture(throwing = true)

        val result = fixture.importer.importAsNewDocument(PDF_BYTES, "Corrupt.pdf")

        // The opener's failure VALUE maps to PdfUnreadable — never a crash.
        assertEquals(ImportFailure.PdfUnreadable, (result as ImportResult.Failure).failure)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun ctorQuality_isClampedIntoTheEncoderRange() = runTest {
        val default = Fixture(pageCount = 1)
        default.importer.importAsNewDocument(PDF_BYTES, "Q.pdf")
        // Documented default quality 85, clamped through the encoder range.
        assertEquals("p0@1190x1684q85", default.pages!!.renders.single())

        val overshoot = Fixture(pageCount = 1, jpegQuality = 999)
        overshoot.importer.importAsNewDocument(PDF_BYTES, "Q.pdf")
        assertEquals("p0@1190x1684q100", overshoot.pages!!.renders.single())
    }

    // ---------------------------------------------------------- append

    @Test
    fun appendFlow_appendsAtPriorCountWithVocabulary() = runTest {
        val fixture = Fixture(pageCount = 2)
        fixture.seedExistingDocument()

        val result = fixture.importer.appendToDocument(DOC_ID, PDF_BYTES) as ImportResult.Success

        assertEquals(4, result.pages.size)
        assertEquals((0..3).toList(), result.pages.map { it.index })
        assertEquals(DOC_ID, result.document.id)
        assertEquals(result.pages.map { it.id }, result.document.pageIds)
        assertEquals(FIXED_MILLIS, result.document.updatedAtMillis)
        // Existing pages untouched.
        assertEquals("seed-p0", result.pages[0].id)
        assertEquals("seed-p1", result.pages[1].id)
        // Appended pages carry the PDF vocabulary; the append mints the
        // import id FIRST (no new document id needed).
        assertEquals(
            "imports/" + FIXED_MILLIS + "-pdfpdf-id-0-p0.jpg",
            result.pages[2].processedImageRef,
        )
        assertEquals(
            "imports/" + FIXED_MILLIS + "-pdfpdf-id-0-p1.jpg",
            result.pages[3].processedImageRef,
        )
        assertTrue(
            result.pages.drop(2).all { it.documentId == DOC_ID && it.sourceCaptureRef == "imports/" + FIXED_MILLIS + "-pdfpdf-id-0.pdf" },
        )
    }

    @Test
    fun appendToUnknownDocument_returnsNull_zeroSideEffects() = runTest {
        val fixture = Fixture(pageCount = 2)

        val result = fixture.importer.appendToDocument("no-such-doc", PDF_BYTES)

        assertNull(result)
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun appendMidRenderFailure_leavesDocumentUntouched() = runTest {
        val fixture = Fixture(pageCount = 2, failRenderAt = setOf(0))
        fixture.seedExistingDocument()

        val result = fixture.importer.appendToDocument(DOC_ID, PDF_BYTES)

        val failure = (result as ImportResult.Failure).failure as ImportFailure.PdfPageRenderFailed
        assertEquals(0, failure.pageIndex)
        assertEquals(2, fixture.repository.getPages(DOC_ID).size)
        assertTrue(fixture.store.keys.isEmpty())
    }

    // ------------------------------------------------- bounded render math

    @Test
    fun renderScale_isTheDocumentedBoundedMath() {
        // 144 dpi-equivalent = scale 2.0 over page points.
        assertEquals(PdfTargetDims(1190, 1684), PdfRenderScale.targetDimensions(595, 842))
        assertEquals(PdfTargetDims(200, 200), PdfRenderScale.targetDimensions(100, 100))
        assertEquals(2.0, PdfRenderScale.RENDER_DPI.toDouble() / PdfRenderScale.POINTS_PER_INCH, 0.0)
        // 20000x20000 pt -> 40000x40000 px = 1.6e9 px, over the 40 MP cap:
        // uniformly reduced to 6324x6324 = 39,992,976 px (within the cap).
        val huge = PdfRenderScale.targetDimensions(20000, 20000)
        assertEquals(6324, huge.widthPx)
        assertEquals(6324, huge.heightPx)
        assertTrue(huge.widthPx.toLong() * huge.heightPx.toLong() <= PdfRenderScale.MAX_PAGE_PIXELS)
    }

    // ---------------------------------------------------------- fixtures

    // Fake renderer surface: fixed geometry + deterministic rendered bytes.
    private class FakePdfPages(
        override val pageCount: Int,
        private val pageSize: PdfPageSize = PdfPageSize(595, 842),
        private val failRenderAt: Set<Int> = emptySet(),
        private val failSizeAt: Set<Int> = emptySet(),
    ) : PdfDocumentPages {
        val renders = mutableListOf<String>()
        var closed = false

        override fun pageSizePoints(pageIndex: Int): PdfPageSize? =
            if (pageIndex in failSizeAt) null else pageSize

        override fun renderPageJpeg(
            pageIndex: Int,
            widthPx: Int,
            heightPx: Int,
            quality: Int,
        ): ByteArray? {
            if (pageIndex in failRenderAt) return null
            renders += ("p" + pageIndex + "@" + widthPx + "x" + heightPx + "q" + quality)
            return ("RENDERED-PAGE-" + pageIndex).toByteArray()
        }

        override fun close() {
            closed = true
        }
    }

    // Opener fake serving one pages instance (or null = unopenable).
    private class FakeOpener(private val pages: PdfDocumentPages?) : PdfDocumentOpener {
        override fun open(bytes: ByteArray): PdfDocumentPages? = pages
    }

    // Opener fake whose platform surface blows up on open.
    private class ThrowingOpener : PdfDocumentOpener {
        override fun open(bytes: ByteArray): PdfDocumentPages? =
            throw IllegalStateException("renderer exploded")
    }

    // In-memory store recording key vocabulary verbatim.
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
        pageCount: Int = 3,
        failRenderAt: Set<Int> = emptySet(),
        failSizeAt: Set<Int> = emptySet(),
        unopenable: Boolean = false,
        throwing: Boolean = false,
        jpegQuality: Int? = null,
    ) {
        val store = FakeContentStore()
        val repository = InMemoryDocumentRepository(FakeTimeSource())
        val pages = if (unopenable || throwing) {
            null
        } else {
            FakePdfPages(pageCount, failRenderAt = failRenderAt, failSizeAt = failSizeAt)
        }
        private val opener = if (throwing) ThrowingOpener() else FakeOpener(pages)
        private val counter = intArrayOf(0)
        private val ids = IdGenerator {
            val value = "pdf-id-" + counter[0]
            counter[0] += 1
            value
        }

        val importer = PdfImporter(
            repository = repository,
            contentStore = store,
            idGenerator = ids,
            timeSource = FakeTimeSource(startMillis = FIXED_MILLIS),
            opener = opener,
            jpegQuality = jpegQuality ?: PdfImporter.DEFAULT_JPEG_QUALITY,
            dispatcher = Dispatchers.Unconfined,
        )

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
    }

    private companion object {
        const val DOC_ID = "doc-under-test"
        const val FIXED_MILLIS = 1_000_000L
        val PDF_BYTES = "%PDF-1.4\nfake-imported-pdf\n%%EOF".toByteArray()
    }
}
