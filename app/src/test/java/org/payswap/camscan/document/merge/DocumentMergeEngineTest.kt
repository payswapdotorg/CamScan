package org.payswap.camscan.document.merge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.ocr.FakeTimeSource

// JVM tests for DocumentMergeEngine + MergeExecutor (CAMSCAN-PROD-008 §6.3
// and §6.7): the pure plan laws (order, title, id re-minting, provenance,
// COPY/CONSUME ref plans) and the LOAD-BEARING executor orders, asserted
// through a recording repository + recording store sharing one event log.
// Style follows the established ExportEngineTest fake conventions.
class DocumentMergeEngineTest {

    // ------------------------------------------------------------ plan laws

    @Test
    fun orderLaw_sourceDocumentOrder_stableWithinEachSource() {
        val fixture = Fixture()
        // Pages handed over SHUFFLED inside each source: the engine must sort
        // by index within a source and concatenate sources in document order.
        val shuffled = mapOf(
            "doc-a" to listOf(fixture.aPages[1], fixture.aPages[0]),
            "doc-b" to listOf(fixture.bPages[2], fixture.bPages[0], fixture.bPages[1]),
            "doc-c" to listOf(fixture.cPages[0]),
        )
        val plan = fixture.engine.merge(fixture.documents, shuffled, MergeMode.CONSUME, fixture.ids, fixture.time)

        // CONSUME preserves source refs verbatim, so ref order proves page order.
        assertEquals(
            listOf("ref-a0", "ref-a1", "ref-b0", "ref-b1", "ref-b2", "ref-c0"),
            plan.mergedPages.map { it.processedImageRef },
        )
        assertEquals((0..5).toList(), plan.mergedPages.map { it.index })
        assertEquals(6, plan.mergedPages.size)
    }

    @Test
    fun titleRule_firstSourceTitlePlusCount() {
        val fixture = Fixture()
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.CONSUME, fixture.ids, fixture.time)

        assertEquals("Alpha + 2 more", plan.mergedDocument.title)
    }

    @Test
    fun idReMinting_noIdSharedWithAnySourcePage() {
        val fixture = Fixture()
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.CONSUME, fixture.ids, fixture.time)

        val sourcePageIds = (fixture.aPages + fixture.bPages + fixture.cPages).map { it.id }.toSet()
        val mergedPageIds = plan.mergedPages.map { it.id }
        assertEquals(mergedPageIds.size, mergedPageIds.toSet().size)
        assertTrue("no merged page id is a source page id", mergedPageIds.none { it in sourcePageIds })
        assertTrue(plan.mergedDocument.id !in setOf("doc-a", "doc-b", "doc-c"))
        assertTrue(plan.mergedPages.all { it.documentId == plan.mergedDocument.id })
        assertEquals(mergedPageIds, plan.mergedDocument.pageIds)
        // Re-minted pages carry the merge timestamp; other fields pass through.
        assertTrue(plan.mergedPages.all { it.createdAtMillis == MERGE_MILLIS && it.updatedAtMillis == MERGE_MILLIS })
        assertEquals(90, plan.mergedPages[0].rotationDegrees)
        assertEquals(4, plan.mergedPages[0].cropQuad!!.size)
        assertEquals(PageEnhancementMode.GRAYSCALE, plan.mergedPages[0].enhancement)
        assertEquals("src-a0", plan.mergedPages[0].sourceCaptureRef)
    }

    @Test
    fun provenance_scanOnlyWhenEverySourceIsScan() {
        val fixture = Fixture()
        // Mixed sources (a=SCAN, b=IMPORTED, c=SCAN) -> IMPORTED.
        val mixed = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.CONSUME, fixture.ids, fixture.time)
        assertEquals(DocumentSource.IMPORTED, mixed.mergedDocument.sourceType)

        // Every source SCAN -> SCAN.
        val allScan = listOf(
            fixture.documents[0],
            fixture.documents[2],
            Document(
                id = "doc-d",
                title = "Delta",
                sourceType = DocumentSource.SCAN,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
            ),
        )
        val scanPages = fixture.pagesByDocument + ("doc-d" to emptyList<Page>())
        val pure = fixture.engine.merge(allScan, scanPages, MergeMode.CONSUME, fixture.ids, fixture.time)
        assertEquals(DocumentSource.SCAN, pure.mergedDocument.sourceType)
    }

    @Test
    fun fewerThanTwoSources_throwsIllegalArgument() {
        val fixture = Fixture()
        try {
            fixture.engine.merge(emptyList(), emptyMap(), MergeMode.CONSUME, fixture.ids, fixture.time)
            fail("expected IllegalArgumentException for zero sources")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least 2"))
        }
        try {
            fixture.engine.merge(listOf(fixture.documents[0]), emptyMap(), MergeMode.CONSUME, fixture.ids, fixture.time)
            fail("expected IllegalArgumentException for one source")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("at least 2"))
        }
    }

    @Test
    fun duplicateSourceIds_throwIllegalArgument() {
        val fixture = Fixture()
        try {
            fixture.engine.merge(
                listOf(fixture.documents[0], fixture.documents[0]),
                emptyMap(),
                MergeMode.CONSUME,
                fixture.ids,
                fixture.time,
            )
            fail("expected IllegalArgumentException for duplicate source ids")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("distinct ids"))
        }
    }

    @Test
    fun copyPlan_rePutsEveryDistinctRef_oncePerSharedRef() {
        val fixture = Fixture()
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.COPY, fixture.ids, fixture.time)

        // Doc b is the imported-PDF shape: THREE pages, ONE shared original
        // (every PdfImporter page's sourceCaptureRef is the same stored PDF).
        // 12 ref slots across pages, 10 DISTINCT refs.
        val distinctRefs = (fixture.aPages + fixture.bPages + fixture.cPages)
            .flatMap { listOfNotNull(it.processedImageRef, it.sourceCaptureRef) }
            .toSet()
        assertEquals(10, distinctRefs.size)
        assertEquals(distinctRefs.size, plan.refCopies.size)
        assertEquals(distinctRefs, plan.refCopies.map { it.sourceRef }.toSet())
        // CONSUME plans never carry ref copies.
        assertTrue(
            fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.CONSUME, fixture.ids, fixture.time)
                .refCopies.isEmpty(),
        )
    }

    @Test
    fun pagesWithResolvedRefs_swapsKnownRefs_passesUnknownThrough() {
        val fixture = Fixture()
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.COPY, fixture.ids, fixture.time)

        val resolution = mapOf("ref-a0" to "copy-of-ref-a0")
        val resolved = plan.pagesWithResolvedRefs(resolution)
        assertEquals("copy-of-ref-a0", resolved[0].processedImageRef)
        assertEquals("src-a0", resolved[0].sourceCaptureRef)
        // Unknown refs pass through unchanged.
        assertEquals("ref-a1", resolved[1].processedImageRef)
        // An empty mapping is the identity (CONSUME shape).
        assertEquals(plan.mergedPages, plan.pagesWithResolvedRefs(emptyMap()))
    }

    // ------------------------------------------------------ executor laws

    @Test
    fun consumeMode_zeroStorePuts_sourcesDeleted_refsPreserved() = runTest {
        val fixture = Fixture()
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.CONSUME, fixture.ids, fixture.time)

        fixture.executor.apply(plan)

        // CONSUME never touches the store: refs are PRESERVED, not copied.
        assertTrue("CONSUME performs zero store puts", fixture.store.putKeys.isEmpty())
        val stored = fixture.repository.pagesByDoc[plan.mergedDocument.id]!!
        assertEquals(
            listOf("ref-a0", "ref-a1", "ref-b0", "ref-b1", "ref-b2", "ref-c0"),
            stored.map { it.processedImageRef },
        )
        assertEquals(plan.mergedDocument, fixture.repository.documentsById[plan.mergedDocument.id])
        // Sources are consumed: deleted from the repository.
        assertTrue(fixture.repository.documentsById.none { it.key in setOf("doc-a", "doc-b", "doc-c") })
    }

    @Test
    fun executorOrder_consume_upsertsMergedBeforeSourceDeletes() = runTest {
        val fixture = Fixture()
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.CONSUME, fixture.ids, fixture.time)

        fixture.executor.apply(plan)

        // LOAD-BEARING (packet §6.3): the merged document is upserted FIRST,
        // so its pages already reference the preserved refs on the index
        // BEFORE any source delete runs its orphan sweep. Reversed, each
        // delete would sweep the refs the merged document is about to record.
        val mergedUpsert = fixture.events.indexOf("upsert:" + plan.mergedDocument.id)
        assertTrue(mergedUpsert >= 0)
        for (sourceId in listOf("doc-a", "doc-b", "doc-c")) {
            val delete = fixture.events.indexOf("delete:" + sourceId)
            assertTrue("source " + sourceId + " deleted", delete >= 0)
            assertTrue("upsert of merged precedes delete of " + sourceId, mergedUpsert < delete)
        }
        assertEquals(
            listOf(
                "upsert:" + plan.mergedDocument.id,
                "delete:doc-a",
                "delete:doc-b",
                "delete:doc-c",
            ),
            fixture.events,
        )
    }

    @Test
    fun copyMode_putsEveryRefBeforeTheMergedUpsert_sourcesRetained() = runTest {
        val fixture = Fixture()
        // Pre-seed the sources so retention is observable as "still present".
        for (pair in listOf(
            fixture.documents[0] to fixture.aPages,
            fixture.documents[1] to fixture.bPages,
            fixture.documents[2] to fixture.cPages,
        )) {
            fixture.repository.upsertDocument(pair.first, pair.second)
        }
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.COPY, fixture.ids, fixture.time)

        fixture.executor.apply(plan)

        // Documented COPY key vocabulary: merge/<ts>-<pageId>.img / .src,
        // deterministic under the counting ids (doc id new-0, pages new-1..6).
        assertEquals(
            listOf(
                "merge/" + MERGE_MILLIS + "-new-1.img",
                "merge/" + MERGE_MILLIS + "-new-1.src",
                "merge/" + MERGE_MILLIS + "-new-2.img",
                "merge/" + MERGE_MILLIS + "-new-2.src",
                "merge/" + MERGE_MILLIS + "-new-3.img",
                "merge/" + MERGE_MILLIS + "-new-3.src",
                "merge/" + MERGE_MILLIS + "-new-4.img",
                "merge/" + MERGE_MILLIS + "-new-5.img",
                "merge/" + MERGE_MILLIS + "-new-6.img",
                "merge/" + MERGE_MILLIS + "-new-6.src",
            ),
            fixture.store.putKeys,
        )
        // Every re-put copies the source bytes byte-identically.
        for (copy in plan.refCopies) {
            assertArrayEquals(fixture.store.stored[copy.sourceRef], fixture.store.stored[copy.targetKey])
        }
        // The merged pages reference ONLY the new keys, never a source ref.
        val merged = fixture.repository.pagesByDoc[plan.mergedDocument.id]!!
        val targetKeys = fixture.store.putKeys.toSet()
        assertTrue(merged.all { it.processedImageRef in targetKeys && it.sourceCaptureRef in targetKeys })
        // Sources are RETAINED in COPY mode: no deletes at all.
        assertTrue(fixture.events.none { it.startsWith("delete:") })
        assertTrue(fixture.repository.documentsById.keys.containsAll(setOf("doc-a", "doc-b", "doc-c")))
        // All puts happen BEFORE the merged upsert.
        val mergedUpsert = fixture.events.indexOf("upsert:" + plan.mergedDocument.id)
        assertTrue(fixture.events.indexOfLast { it.startsWith("put:") } < mergedUpsert)
    }

    @Test
    fun copyMode_missingSourceRef_failsHonestlyBeforeAnyWrite() = runTest {
        // Refs deliberately NEVER seeded: the store cannot serve any copy.
        val fixture = Fixture(seedRefs = false)
        val plan = fixture.engine.merge(fixture.documents, fixture.pagesByDocument, MergeMode.COPY, fixture.ids, fixture.time)

        try {
            fixture.executor.apply(plan)
            fail("expected IllegalStateException for the missing source ref")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("missing from store"))
        }
        // Honest failure BEFORE any partial write: nothing put, nothing upserted.
        assertTrue(fixture.store.putKeys.isEmpty())
        assertTrue(fixture.repository.documentsById.isEmpty())
    }

    // ---------------------------------------------------------- fixtures

    // Repository fake recording every call into the shared event log.
    private class RecordingRepository(private val events: MutableList<String>) : DocumentRepository {
        val documentsById = LinkedHashMap<String, Document>()
        val pagesByDoc = HashMap<String, List<Page>>()
        private val state = MutableStateFlow<List<Document>>(emptyList())

        override fun observeDocuments(): Flow<List<Document>> = state

        override suspend fun getDocument(id: String): Document? = documentsById[id]

        override suspend fun getPages(documentId: String): List<Page> =
            pagesByDoc[documentId].orEmpty().sortedBy { it.index }

        override suspend fun upsertDocument(document: Document, pages: List<Page>) {
            events += "upsert:" + document.id
            documentsById[document.id] = document
            pagesByDoc[document.id] = pages.toList()
            state.value = documentsById.values.toList()
        }

        override suspend fun deleteDocument(id: String) {
            events += "delete:" + id
            documentsById.remove(id)
            pagesByDoc.remove(id)
            state.value = documentsById.values.toList()
        }
    }

    // Store fake recording puts into the shared event log + a key list.
    private class RecordingStore(private val events: MutableList<String>) : ContentStore {
        val stored = LinkedHashMap<String, ByteArray>()
        val putKeys = mutableListOf<String>()

        override suspend fun put(key: String, bytes: ByteArray): String {
            events += "put:" + key
            putKeys += key
            stored[key] = bytes
            return key
        }

        override suspend fun open(ref: String): ByteArray? = stored[ref]

        override suspend fun delete(ref: String): Boolean = stored.remove(ref) != null

        override suspend fun exists(ref: String): Boolean = stored.containsKey(ref)
    }

    private inner class Fixture(private val seedRefs: Boolean = true) {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val repository = RecordingRepository(events)
        private val counter = intArrayOf(0)
        val ids = IdGenerator {
            val value = "new-" + counter[0]
            counter[0] += 1
            value
        }
        val time = FakeTimeSource(startMillis = MERGE_MILLIS)
        val engine = DocumentMergeEngine()
        val executor = MergeExecutor(repository, store)

        val documents = listOf(
            Document(
                id = "doc-a",
                title = "Alpha",
                pageIds = listOf("a-p0", "a-p1"),
                sourceType = DocumentSource.SCAN,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
            ),
            Document(
                id = "doc-b",
                title = "Bravo",
                pageIds = listOf("b-p0", "b-p1", "b-p2"),
                sourceType = DocumentSource.IMPORTED,
                createdAtMillis = 2_000L,
                updatedAtMillis = 2_000L,
            ),
            Document(
                id = "doc-c",
                title = "Charlie",
                pageIds = listOf("c-p0"),
                sourceType = DocumentSource.SCAN,
                createdAtMillis = 3_000L,
                updatedAtMillis = 3_000L,
            ),
        )

        val aPages = listOf(
            page("a-p0", "doc-a", 0, "ref-a0", "src-a0", rotationDegrees = 90, grayscale = true, cropped = true),
            page("a-p1", "doc-a", 1, "ref-a1", "src-a1"),
        )
        val bPages = listOf(
            page("b-p0", "doc-b", 0, "ref-b0", "src-b"),
            page("b-p1", "doc-b", 1, "ref-b1", "src-b"),
            page("b-p2", "doc-b", 2, "ref-b2", "src-b"),
        )
        val cPages = listOf(page("c-p0", "doc-c", 0, "ref-c0", "src-c0"))

        val pagesByDocument: Map<String, List<Page>> = mapOf(
            "doc-a" to aPages,
            "doc-b" to bPages,
            "doc-c" to cPages,
        )

        init {
            if (seedRefs) {
                // COPY-mode byte re-puts read real bytes: seed every source ref.
                for (ref in listOf("ref-a0", "ref-a1", "ref-b0", "ref-b1", "ref-b2", "ref-c0")) {
                    store.stored[ref] = ("BYTES-" + ref).toByteArray()
                }
                for (ref in listOf("src-a0", "src-a1", "src-b", "src-c0")) {
                    store.stored[ref] = ("BYTES-" + ref).toByteArray()
                }
            }
        }

        private fun page(
            id: String,
            documentId: String,
            index: Int,
            processedRef: String,
            sourceRef: String,
            rotationDegrees: Int = 0,
            grayscale: Boolean = false,
            cropped: Boolean = false,
        ): Page = Page(
            id = id,
            documentId = documentId,
            index = index,
            sourceCaptureRef = sourceRef,
            processedImageRef = processedRef,
            cropQuad = if (cropped) {
                listOf(Corner(0f, 0f), Corner(1f, 0f), Corner(1f, 1f), Corner(0f, 1f))
            } else {
                null
            },
            enhancement = if (grayscale) PageEnhancementMode.GRAYSCALE else PageEnhancementMode.ORIGINAL,
            rotationDegrees = rotationDegrees,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
    }

    private companion object {
        const val MERGE_MILLIS = 1_000_000L
    }
}
