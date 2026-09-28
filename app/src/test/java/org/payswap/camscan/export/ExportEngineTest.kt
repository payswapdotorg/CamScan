package org.payswap.camscan.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.export.pdf.EncodedJpeg
import org.payswap.camscan.export.pdf.PageImageEncoder
import org.payswap.camscan.ocr.FakeTimeSource

/**
 * JVM tests for [ExportEngine] (CAMSCAN-PROD-007 §6.6) with the established
 * fakes: [InMemoryDocumentRepository] (main tree), [FakeTimeSource] (ocr
 * test tree), and a local recording [ContentStore] + deterministic encoder.
 */
class ExportEngineTest {

    @Test
    fun exportPdf_usesDocumentedKeyVocabularyAndMetadata() = runTest {
        val fixture = Fixture()
        val artifact = fixture.engine.exportPdf(fixture.repository, DOC_ID)

        assertNotNull(artifact)
        assertEquals("exports/$DOC_ID-$FIXED_MILLIS-3p.pdf", artifact!!.ref)
        assertEquals(1, fixture.store.keys.size)
        assertEquals("exports/$DOC_ID-$FIXED_MILLIS-3p.pdf", fixture.store.keys.single())
        assertEquals(ExportArtifact.MIME_PDF, artifact.mime)
        assertEquals("Contract.pdf", artifact.displayName)
        assertEquals(3, artifact.pageCount)
        assertEquals(fixture.store.stored[artifact.ref]!!.size, artifact.sizeBytes)
        assertTrue(fixture.store.stored[artifact.ref]!!.size > 0)
    }

    @Test
    fun exportPdf_embedsPagesInDocumentIndexOrder() = runTest {
        val fixture = Fixture()
        val artifact = fixture.engine.exportPdf(fixture.repository, DOC_ID)!!

        val pdf = String(fixture.store.stored[artifact.ref]!!, Charsets.US_ASCII)
        val a = pdf.indexOf(PAGE_PAYLOAD_A)
        val b = pdf.indexOf(PAGE_PAYLOAD_B)
        val c = pdf.indexOf(PAGE_PAYLOAD_C)
        assertTrue("page A embedded", a >= 0)
        assertTrue("page B embedded", b >= 0)
        assertTrue("page C embedded", c >= 0)
        assertTrue("index order preserved (A before B before C)", a < b && b < c)
    }

    @Test
    fun exportPdf_carriesRotationAndInjectedCreationDate() = runTest {
        val fixture = Fixture(rotations = listOf(0, 90, 180))
        val artifact = fixture.engine.exportPdf(fixture.repository, DOC_ID)!!

        val pdf = String(fixture.store.stored[artifact.ref]!!, Charsets.US_ASCII)
        assertTrue("page 2 rotation applied", pdf.contains("/Rotate 90"))
        assertTrue("page 3 rotation applied", pdf.contains("/Rotate 180"))
        assertTrue(
            "CreationDate comes from the injected TimeSource",
            pdf.contains("/CreationDate (D:19700101001640+00'00')"),
        )
        assertTrue(pdf.contains("/Producer (CamScan)"))
    }

    @Test
    fun exportPdf_isDeterministic_forFixedInputsAndTime() = runTest {
        val first = Fixture()
        val second = Fixture()
        val firstArtifact = first.engine.exportPdf(first.repository, DOC_ID)!!
        val secondArtifact = second.engine.exportPdf(second.repository, DOC_ID)!!

        assertEquals(firstArtifact.ref, secondArtifact.ref)
        assertArrayEquals(
            "byte-identical PDFs for identical inputs + injected time",
            first.store.stored[firstArtifact.ref],
            second.store.stored[secondArtifact.ref],
        )
    }

    @Test
    fun exportPdf_readsTimeOnce_keyAndCreationDateShareTheStamp() = runTest {
        // A stepping fake makes a second read observable: the key and the
        // PDF's CreationDate must both carry the FIRST stamp.
        val fixture = Fixture(time = FakeTimeSource(startMillis = FIXED_MILLIS, stepMillis = 7))
        val artifact = fixture.engine.exportPdf(fixture.repository, DOC_ID)!!

        assertEquals("exports/$DOC_ID-$FIXED_MILLIS-3p.pdf", artifact.ref)
        val pdf = String(fixture.store.stored[artifact.ref]!!, Charsets.US_ASCII)
        assertTrue(pdf.contains("D:19700101001640"))
    }

    @Test
    fun exportJpg_selectsRequestedPageAndUsesJpgVocabulary() = runTest {
        val fixture = Fixture()
        val artifact = fixture.engine.exportJpg(fixture.repository, DOC_ID, pageIndex = 1)

        assertNotNull(artifact)
        assertEquals("exports/$DOC_ID-p1.jpg", artifact!!.ref)
        assertEquals(ExportArtifact.MIME_JPEG, artifact.mime)
        assertEquals("Contract_p2.jpg", artifact.displayName)
        assertEquals(1, artifact.pageCount)
        // The JPG artifact holds the ENCODER output for page index 1.
        assertArrayEquals(PAGE_PAYLOAD_B.toByteArray(), fixture.store.stored[artifact.ref])
        assertEquals(PAGE_PAYLOAD_B.length, artifact.sizeBytes)
    }

    @Test
    fun exportJpg_invalidIndex_returnsNull() = runTest {
        val fixture = Fixture()
        assertNull(fixture.engine.exportJpg(fixture.repository, DOC_ID, pageIndex = 3))
        assertNull(fixture.engine.exportJpg(fixture.repository, DOC_ID, pageIndex = -1))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun missingDocument_returnsNull() = runTest {
        val fixture = Fixture()
        assertNull(fixture.engine.exportPdf(fixture.repository, "no-such-doc"))
        assertNull(fixture.engine.exportJpg(fixture.repository, "no-such-doc", pageIndex = 0))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun emptyDocument_returnsNull() = runTest {
        val fixture = Fixture(pageCount = 0)
        assertNull(fixture.engine.exportPdf(fixture.repository, DOC_ID))
        assertNull(fixture.engine.exportJpg(fixture.repository, DOC_ID, pageIndex = 0))
    }

    @Test
    fun pageWithoutImageRef_returnsNull() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", null, "ref-c"))
        assertNull(fixture.engine.exportPdf(fixture.repository, DOC_ID))
        assertNull("the null-ref page is index 1", fixture.engine.exportJpg(fixture.repository, DOC_ID, pageIndex = 1))
    }

    @Test
    fun pageBytesMissingFromStore_returnsNull() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", "unopened-ref", "ref-c"))
        assertNull(fixture.engine.exportPdf(fixture.repository, DOC_ID))
        assertNull(fixture.engine.exportJpg(fixture.repository, DOC_ID, pageIndex = 1))
    }

    @Test
    fun encoderRejectingBytes_returnsNull() = runTest {
        val fixture = Fixture(rejectPayload = PAGE_PAYLOAD_C)
        assertNull(fixture.engine.exportPdf(fixture.repository, DOC_ID))
        assertNull(fixture.engine.exportJpg(fixture.repository, DOC_ID, pageIndex = 2))
    }

    @Test
    fun displayNames_sanitizeSeparatorsAndBlankTitles() {
        assertEquals("Invoices_Q3_2026", sanitizeFileStem("Invoices/Q3\\2026"))
        assertEquals("A_B_C_D_E_F", sanitizeFileStem("A:B*C?D\"E|F"))
        assertEquals("document", sanitizeFileStem("   "))
        assertEquals("separator-only titles stay filesystem-safe", "___", sanitizeFileStem("///"))
        assertEquals("Trimmed", sanitizeFileStem("  Trimmed  "))
    }

    @Test
    fun encoderSeesStoredBytes_notRefs() = runTest {
        val seen = mutableListOf<ByteArray>()
        val fixture = Fixture(encoder = RecordingEncoder(seen))
        fixture.engine.exportPdf(fixture.repository, DOC_ID)

        assertEquals(3, seen.size)
        assertArrayEquals(PAGE_PAYLOAD_A.toByteArray(), seen[0])
        assertArrayEquals(PAGE_PAYLOAD_B.toByteArray(), seen[1])
        assertArrayEquals(PAGE_PAYLOAD_C.toByteArray(), seen[2])
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private class RecordingEncoder(val seen: MutableList<ByteArray>) : PageImageEncoder {
        override fun encode(storedBytes: ByteArray): EncodedJpeg? {
            if (storedBytes.isEmpty()) return null
            seen += storedBytes
            return EncodedJpeg(storedBytes, widthPx = 128, heightPx = 96)
        }
    }

    /** Deterministic fake: passthrough bytes with size-derived geometry. */
    private class FakeEncoder(private val rejectPayload: String? = null) : PageImageEncoder {
        override fun encode(storedBytes: ByteArray): EncodedJpeg? {
            if (storedBytes.isEmpty()) return null
            if (rejectPayload != null && String(storedBytes, Charsets.US_ASCII) == rejectPayload) {
                return null
            }
            return EncodedJpeg(storedBytes, widthPx = 128, heightPx = 96)
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
        pageCount: Int = 3,
        pageRefs: List<String?>? = null,
        rotations: List<Int>? = null,
        time: TimeSource = FakeTimeSource(startMillis = FIXED_MILLIS),
        encoder: PageImageEncoder? = null,
        rejectPayload: String? = null,
    ) {
        val store = FakeContentStore()
        val repository = InMemoryDocumentRepository(FakeTimeSource())
        init {
            val payloads = listOf(PAGE_PAYLOAD_A, PAGE_PAYLOAD_B, PAGE_PAYLOAD_C)
            val pages = (0 until pageCount).map { index ->
                // Distinguish an explicit null ref from "use the default".
                val ref = if (pageRefs != null && index < pageRefs.size) {
                    pageRefs[index]
                } else {
                    "ref-${'a' + index}"
                }
                if (ref != null && ref in seededRefs) {
                    store.stored[ref] = payloads[index].toByteArray()
                }
                Page(
                    id = "$DOC_ID-p$index",
                    documentId = DOC_ID,
                    index = index,
                    processedImageRef = ref,
                    rotationDegrees = rotations?.getOrNull(index) ?: 0,
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                )
            }
            // Fixture construction happens inside runTest bodies, but the
            // constructor itself is not suspend: seed synchronously.
            runBlocking {
                repository.upsertDocument(
                    Document(
                        id = DOC_ID,
                        title = DOCUMENT_TITLE,
                        createdAtMillis = 1_000L,
                        updatedAtMillis = 1_000L,
                    ),
                    pages,
                )
            }
        }

        val engine = ExportEngine(
            contentStore = store,
            encoder = encoder ?: FakeEncoder(rejectPayload),
            timeSource = time,
            dispatcher = Dispatchers.Unconfined,
        )
    }

    private companion object {
        const val DOC_ID = "doc-under-test"
        const val DOCUMENT_TITLE = "Contract"
        const val FIXED_MILLIS = 1_000_000L

        const val PAGE_PAYLOAD_A = "STORED-PAGE-IMAGE-ALPHA"
        const val PAGE_PAYLOAD_B = "STORED-PAGE-IMAGE-BRAVO"
        const val PAGE_PAYLOAD_C = "STORED-PAGE-IMAGE-CHARLIE"

        /** Refs the fixture actually seeds bytes for (others read as missing). */
        val seededRefs = setOf("ref-a", "ref-b", "ref-c")
    }
}
