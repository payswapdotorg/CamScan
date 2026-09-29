package org.payswap.camscan.export.compress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.export.ExportArtifact
import org.payswap.camscan.export.pdf.EncodedJpeg
import org.payswap.camscan.export.pdf.PageImageEncoder
import org.payswap.camscan.ocr.FakeTimeSource

// JVM tests for CompressExportEngine (CAMSCAN-PROD-008 §6.5/§6.7) with the
// established fakes (InMemoryDocumentRepository, FakeTimeSource, recording
// ContentStore, deterministic encoder/resampler): artifact metadata + key
// vocabulary, HONEST compressed-size reporting (compressedBytes may exceed
// originalBytes — the truth, never a silent fallback), rotation flow-through
// on the real PdfWriter, and the failure-as-null contract with zero residue.
class CompressExportEngineTest {

    @Test
    fun compressToPdf_artifactMetadataAndKeyVocabulary() = runTest {
        val fixture = Fixture()

        val result = fixture.engine.compressToPdf(fixture.repository, DOC_ID)!!

        // Documented key vocabulary: the -c marker distinguishes compressed
        // artifacts from PROD-007's plain exports/<docId>-<ts>-<n>p.pdf.
        assertEquals("exports/" + DOC_ID + "-" + FIXED_MILLIS + "-3p-c.pdf", result.artifact.ref)
        assertEquals(listOf("exports/" + DOC_ID + "-" + FIXED_MILLIS + "-3p-c.pdf"), fixture.store.keys)
        assertEquals(ExportArtifact.MIME_PDF, result.artifact.mime)
        assertEquals("Contract.pdf", result.artifact.displayName)
        assertEquals(3, result.artifact.pageCount)
        // ExportArtifact's UNCHANGED shape: sizeBytes reports the COMPRESSED
        // size; the report carries the delta.
        assertEquals(result.report.compressedBytes, result.artifact.sizeBytes.toLong())

        val pdf = String(fixture.store.stored[result.artifact.ref]!!, Charsets.US_ASCII)
        assertTrue(pdf.startsWith("%PDF-"))
        // Pages embedded in index order with the resampler's outputs.
        val a = pdf.indexOf("RESAMPLED(STORED-A)")
        val b = pdf.indexOf("RESAMPLED(STORED-B)")
        val c = pdf.indexOf("RESAMPLED(STORED-C)")
        assertTrue(a >= 0 && b >= 0 && c >= 0)
        assertTrue("index order preserved", a < b && b < c)
        // Every source page payload was resampled (3200x2400 is over budget).
        assertEquals(listOf("STORED-A", "STORED-B", "STORED-C"), fixture.resampler.calls)
        assertEquals(3, result.report.pageCount)
        assertTrue(result.report.pages.none { it.skipped })
    }

    @Test
    fun honestReporting_compressedMayExceedOriginal_truthNeverFallsBack() = runTest {
        // Within-budget pages (128x96) with tiny payloads: every page skips,
        // and the PDF's structural overhead DWARFS the source payload.
        val fixture = Fixture(encoderWidthPx = 128, encoderHeightPx = 96)

        val result = fixture.engine.compressToPdf(fixture.repository, DOC_ID)!!

        assertTrue("resampler never consulted for within-budget pages", fixture.resampler.calls.isEmpty())
        val original = ("STORED-A".length + "STORED-B".length + "STORED-C".length).toLong()
        assertEquals(original, result.report.originalBytes)
        assertTrue(
            "compressedBytes honestly exceeds originalBytes for already-small inputs",
            result.report.compressedBytes > result.report.originalBytes,
        )
        // The truth is reported — the artifact and the report agree on the
        // compressed size; nothing silently falls back to the original bytes.
        assertEquals(result.report.compressedBytes, result.artifact.sizeBytes.toLong())
        assertTrue(result.report.pages.all { it.skipped && it.afterBytes == it.beforeBytes })
    }

    @Test
    fun rotationCarriesFromPages_identicalToNormalExport() = runTest {
        val fixture = Fixture(rotations = listOf(0, 90, 180))

        val result = fixture.engine.compressToPdf(fixture.repository, DOC_ID)!!

        val pdf = String(fixture.store.stored[result.artifact.ref]!!, Charsets.US_ASCII)
        assertTrue(pdf.contains("/Rotate 90"))
        assertTrue(pdf.contains("/Rotate 180"))
        assertTrue(pdf.contains("/CreationDate (D:19700101001640+00'00')"))
        assertTrue(pdf.contains("/Producer (CamScan)"))
    }

    @Test
    fun timeReadOnce_keyAndCreationDateShareTheFirstStamp() = runTest {
        // A stepping fake makes a second read observable: the key and the
        // PDF's CreationDate must both carry the FIRST stamp.
        val fixture = Fixture(time = FakeTimeSource(startMillis = FIXED_MILLIS, stepMillis = 7))

        val result = fixture.engine.compressToPdf(fixture.repository, DOC_ID)!!

        assertEquals("exports/" + DOC_ID + "-" + FIXED_MILLIS + "-3p-c.pdf", result.artifact.ref)
        val pdf = String(fixture.store.stored[result.artifact.ref]!!, Charsets.US_ASCII)
        assertTrue(pdf.contains("D:19700101001640"))
    }

    @Test
    fun pageImagesArriveInIndexOrder() = runTest {
        val seen = mutableListOf<ByteArray>()
        val fixture = Fixture(encoder = RecordingEncoder(3200, 2400, seen))

        fixture.engine.compressToPdf(fixture.repository, DOC_ID)

        assertEquals(3, seen.size)
        assertEquals("STORED-A", String(seen[0], Charsets.US_ASCII))
        assertEquals("STORED-B", String(seen[1], Charsets.US_ASCII))
        assertEquals("STORED-C", String(seen[2], Charsets.US_ASCII))
    }

    @Test
    fun unknownDocument_returnsNull_withZeroPuts() = runTest {
        val fixture = Fixture()
        assertNull(fixture.engine.compressToPdf(fixture.repository, "no-such-doc"))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun emptyDocument_returnsNull_withZeroPuts() = runTest {
        val fixture = Fixture(pageCount = 0)
        assertNull(fixture.engine.compressToPdf(fixture.repository, DOC_ID))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun pageWithoutImageRef_returnsNull_withZeroPuts() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", null, "ref-c"))
        assertNull(fixture.engine.compressToPdf(fixture.repository, DOC_ID))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun pageBytesMissingFromStore_returnsNull_withZeroPuts() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", "unopened-ref", "ref-c"))
        assertNull(fixture.engine.compressToPdf(fixture.repository, DOC_ID))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun encoderRejectingBytes_returnsNull_withZeroPuts() = runTest {
        val fixture = Fixture(rejectPayload = "STORED-C")
        assertNull(fixture.engine.compressToPdf(fixture.repository, DOC_ID))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun resampleFailure_returnsNull_withZeroPuts() = runTest {
        val fixture = Fixture(resampleFailsFor = "STORED-B")
        assertNull(fixture.engine.compressToPdf(fixture.repository, DOC_ID))
        // All-or-nothing: a mid-document resample failure leaves NOTHING
        // stored — no partial artifact, no silent uncompressed fallback.
        assertTrue(fixture.store.keys.isEmpty())
    }

    // ---------------------------------------------------------- fixtures

    // Encoder fake: passthrough bytes with configurable geometry.
    private class FakeEncoder(
        private val widthPx: Int,
        private val heightPx: Int,
        private val rejectPayload: String? = null,
    ) : PageImageEncoder {
        override fun encode(storedBytes: ByteArray): EncodedJpeg? {
            if (storedBytes.isEmpty()) return null
            if (rejectPayload != null && String(storedBytes, Charsets.US_ASCII) == rejectPayload) {
                return null
            }
            return EncodedJpeg(storedBytes, widthPx, heightPx)
        }
    }

    // Encoder fake recording the stored bytes it was asked to encode.
    private class RecordingEncoder(
        private val widthPx: Int,
        private val heightPx: Int,
        private val seen: MutableList<ByteArray>,
    ) : PageImageEncoder {
        override fun encode(storedBytes: ByteArray): EncodedJpeg? {
            if (storedBytes.isEmpty()) return null
            seen += storedBytes
            return EncodedJpeg(storedBytes, widthPx, heightPx)
        }
    }

    // Resampler fake: deterministic payload-echoing outputs.
    private class FakeResampler(
        private val failFor: String? = null,
    ) : ImageResampler {
        val calls = mutableListOf<String>()

        override fun resample(
            jpegBytes: ByteArray,
            targetWidthPx: Int,
            targetHeightPx: Int,
            quality: Int,
        ): ResampledJpeg? {
            val input = String(jpegBytes, Charsets.US_ASCII)
            if (failFor != null && input == failFor) return null
            calls += input
            return ResampledJpeg(("RESAMPLED(" + input + ")").toByteArray(), targetWidthPx, targetHeightPx)
        }
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
        pageRefs: List<String?>? = null,
        rotations: List<Int>? = null,
        encoderWidthPx: Int = 3200,
        encoderHeightPx: Int = 2400,
        encoder: PageImageEncoder? = null,
        rejectPayload: String? = null,
        resampleFailsFor: String? = null,
        time: TimeSource = FakeTimeSource(startMillis = FIXED_MILLIS),
    ) {
        val store = FakeContentStore()
        val repository = InMemoryDocumentRepository(FakeTimeSource())
        val resampler = FakeResampler(failFor = resampleFailsFor)

        init {
            val payloads = listOf("STORED-A", "STORED-B", "STORED-C")
            val pages = (0 until pageCount).map { index ->
                val ref = if (pageRefs != null && index < pageRefs.size) {
                    pageRefs[index]
                } else {
                    "ref-" + ('a' + index)
                }
                if (ref != null && ref != "unopened-ref") {
                    store.stored[ref] = payloads[index].toByteArray()
                }
                Page(
                    id = DOC_ID + "-p" + index,
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
            kotlinx.coroutines.runBlocking {
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

        val engine = CompressExportEngine(
            contentStore = store,
            encoder = encoder ?: FakeEncoder(encoderWidthPx, encoderHeightPx, rejectPayload),
            resampler = resampler,
            timeSource = time,
            dispatcher = Dispatchers.Unconfined,
        )
    }

    private companion object {
        const val DOC_ID = "doc-under-test"
        const val DOCUMENT_TITLE = "Contract"
        const val FIXED_MILLIS = 1_000_000L
    }
}
