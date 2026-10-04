package org.payswap.camscan.export.conversion

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
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.ocr.FakeTimeSource
import org.payswap.camscan.tools.conversion.ConversionDocument
import org.payswap.camscan.tools.conversion.ConversionPage
import org.payswap.camscan.tools.conversion.DocxExportAdapter
import org.payswap.camscan.tools.conversion.PptxExportAdapter
import org.payswap.camscan.tools.conversion.XlsxExportAdapter
import org.payswap.camscan.tools.ui.ExportFormatCatalog

// CAMSCAN-VERIFY-002 — JVM tests of the Office conversion FLOW engine
// with the established fakes (InMemoryDocumentRepository, FakeTimeSource,
// a local recording ContentStore) and a deterministic fake text source.
// Asserts the PROD-007-matching key vocabulary, adapter-output equality,
// honest nulls, and determinism under a fixed clock.

class OfficeExportEngineTest {

    @Test
    fun exportPptx_producesAdapterBytesInTheExportLocation() = runTest {
        val fixture = Fixture()
        val result = fixture.engine.export(fixture.repository, DOC_ID, "pptx")

        assertNotNull(result)
        val artifact = result!!.artifact
        assertEquals("exports/" + DOC_ID + "-" + FIXED_MILLIS + "-2p.pptx", artifact.ref)
        assertEquals(ExportArtifactMimePptx, artifact.mime)
        assertEquals("Contract.pptx", artifact.displayName)
        assertEquals(2, artifact.pageCount)
        val expected = PptxExportAdapter.export(fixture.expectedConversionDocument())
        assertEquals(expected.size, artifact.sizeBytes)
        assertArrayEquals(expected, fixture.store.stored[artifact.ref])
        assertEquals("pptx", result.formatId)
    }

    @Test
    fun exportDocx_routesToTheDocxAdapter() = runTest {
        val fixture = Fixture()
        val result = fixture.engine.export(fixture.repository, DOC_ID, "docx")

        assertNotNull(result)
        assertEquals("Contract.docx", result!!.artifact.displayName)
        assertEquals(ExportArtifactMimeDocx, result.artifact.mime)
        val expected = DocxExportAdapter.export(fixture.expectedConversionDocument())
        assertArrayEquals(expected, fixture.store.stored[result.artifact.ref])
    }

    @Test
    fun exportXlsx_routesToTheXlsxAdapter() = runTest {
        val fixture = Fixture()
        val result = fixture.engine.export(fixture.repository, DOC_ID, "xlsx")

        assertNotNull(result)
        assertEquals("Contract.xlsx", result!!.artifact.displayName)
        assertEquals(ExportArtifactMimeXlsx, result.artifact.mime)
        val expected = XlsxExportAdapter.export(fixture.expectedConversionDocument())
        assertArrayEquals(expected, fixture.store.stored[result.artifact.ref])
    }

    @Test
    fun export_feedsPagesInIndexOrderWithTheRecognizedLines() = runTest {
        val fixture = Fixture()
        fixture.engine.export(fixture.repository, DOC_ID, "pptx")

        assertEquals(listOf(PAGE_A_BYTES, PAGE_B_BYTES), fixture.textSource.seenBytes)
        assertEquals(listOf(0, 90), fixture.textSource.seenRotations)
    }

    @Test
    fun export_unknownDocument_returnsNull() = runTest {
        val fixture = Fixture()
        assertNull(fixture.engine.export(fixture.repository, "no-such-doc", "pptx"))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun export_emptyDocument_returnsNull() = runTest {
        val fixture = Fixture(pageCount = 0)
        assertNull(fixture.engine.export(fixture.repository, DOC_ID, "pptx"))
    }

    @Test
    fun export_pageWithoutImageRef_returnsNull() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", null))
        assertNull(fixture.engine.export(fixture.repository, DOC_ID, "pptx"))
    }

    @Test
    fun export_pageBytesMissingFromStore_returnsNull() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", "unopened-ref"))
        assertNull(fixture.engine.export(fixture.repository, DOC_ID, "pptx"))
    }

    @Test
    fun export_textRecognitionFailure_returnsNull() = runTest {
        val fixture = Fixture(rejectBytes = PAGE_B_BYTES)
        assertNull(fixture.engine.export(fixture.repository, DOC_ID, "pptx"))
    }

    @Test
    fun export_unknownFormatId_returnsNull() = runTest {
        val fixture = Fixture()
        assertNull(fixture.engine.export(fixture.repository, DOC_ID, "pdf"))
        assertNull(fixture.engine.export(fixture.repository, DOC_ID, ExportFormatCatalog.LONG_IMAGE.formatId))
    }

    @Test
    fun export_isDeterministic_forFixedInputsAndTime() = runTest {
        val first = Fixture()
        val second = Fixture()
        val firstArtifact = first.engine.export(first.repository, DOC_ID, "pptx")!!
        val secondArtifact = second.engine.export(second.repository, DOC_ID, "pptx")!!

        assertEquals(firstArtifact.artifact.ref, secondArtifact.artifact.ref)
        assertArrayEquals(
            first.store.stored[firstArtifact.artifact.ref],
            second.store.stored[secondArtifact.artifact.ref],
        )
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private companion object {
        const val DOC_ID = "doc-1"
        const val FIXED_MILLIS = 1_000_000L
        const val ExportArtifactMimePptx =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val ExportArtifactMimeDocx =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val ExportArtifactMimeXlsx =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val PAGE_A_BYTES = "page-a-bytes".toByteArray()
        val PAGE_B_BYTES = "page-b-bytes".toByteArray()
        val PAGE_A_LINES = listOf("First line of A", "Second line of A")
        val PAGE_B_LINES = listOf("Only line of B")
    }

    /** Deterministic fake text source: fixed lines per page payload. */
    private class FakeTextSource(private val rejectBytes: ByteArray?) : PageTextSource {
        val seenBytes = mutableListOf<ByteArray>()
        val seenRotations = mutableListOf<Int>()

        override suspend fun textLinesFor(
            pageBytes: ByteArray,
            rotationDegrees: Int,
        ): List<String>? {
            seenBytes.add(pageBytes)
            seenRotations.add(rotationDegrees)
            if (rejectBytes != null && pageBytes.contentEquals(rejectBytes)) return null
            return if (pageBytes.contentEquals(PAGE_A_BYTES)) PAGE_A_LINES else PAGE_B_LINES
        }

        override fun close() {}
    }

    /** Local recording store (the ExportEngineTest pattern). */
    private class RecordingStore : ContentStore {
        val stored = HashMap<String, ByteArray>()
        val keys = ArrayList<String>()

        override suspend fun put(key: String, bytes: ByteArray): String {
            stored[key] = bytes
            keys.add(key)
            return key
        }

        override suspend fun open(ref: String): ByteArray? = stored[ref]

        override suspend fun delete(ref: String): Boolean = stored.remove(ref) != null

        override suspend fun exists(ref: String): Boolean = stored.containsKey(ref)
    }

    private inner class Fixture(
        pageCount: Int = 2,
        pageRefs: List<String?>? = null,
        rejectBytes: ByteArray? = null,
    ) {
        val store = RecordingStore()
        val textSource = FakeTextSource(rejectBytes)
        val repository = InMemoryDocumentRepository(FakeTimeSource(FIXED_MILLIS))
        val engine = OfficeExportEngine(
            contentStore = store,
            textSource = textSource,
            timeSource = FakeTimeSource(FIXED_MILLIS),
            dispatcher = Dispatchers.Unconfined,
        )

        init {
            val refs = pageRefs ?: List(pageCount) { index -> "ref-" + index }
            store.stored["ref-0"] = PAGE_A_BYTES
            if (refs.size > 1) {
                store.stored["ref-1"] = PAGE_B_BYTES
            }
            val pages = refs.mapIndexed { index, ref ->
                Page(
                    id = "page-" + index,
                    documentId = DOC_ID,
                    index = index,
                    processedImageRef = ref,
                    rotationDegrees = if (index == 1) 90 else 0,
                    createdAtMillis = FIXED_MILLIS,
                    updatedAtMillis = FIXED_MILLIS,
                )
            }
            runBlocking {
                repository.upsertDocument(
                    Document(
                        id = DOC_ID,
                        title = "Contract",
                        pageIds = pages.map { it.id },
                        createdAtMillis = FIXED_MILLIS,
                        updatedAtMillis = FIXED_MILLIS,
                    ),
                    pages,
                )
            }
        }

        fun expectedConversionDocument(): ConversionDocument {
            return ConversionDocument(
                title = "Contract",
                pages = listOf(ConversionPage(PAGE_A_LINES), ConversionPage(PAGE_B_LINES)),
            )
        }
    }
}
