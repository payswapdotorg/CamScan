package org.payswap.camscan.document.split

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.ocr.FakeTimeSource

// JVM tests for DocumentSplitEngine + SplitExecutor (CAMSCAN-PROD-008 §6.4
// and §6.7): subset extraction + complement compaction, id re-minting, the
// zero-put ref-transfer law, the honest empty source on ALL-pages selection,
// the documented API contracts, and the LOAD-BEARING executor order
// (extracted upsert BEFORE the compacted source), asserted through a
// recording repository sharing one event log with the store fake.
class DocumentSplitEngineTest {

    // ------------------------------------------------------------ plan laws

    @Test
    fun subsetExtraction_andComplementCompaction() {
        val fixture = Fixture()
        // Pages handed over SHUFFLED: the engine works in index order.
        val shuffled = listOf(fixture.pages[3], fixture.pages[0], fixture.pages[4], fixture.pages[1], fixture.pages[2])
        val plan = fixture.engine.extract(
            document = fixture.source,
            pages = shuffled,
            selectedPageIds = listOf("p1", "p3"),
            idGenerator = fixture.ids,
            timeSource = fixture.time,
        )

        // Extracted subset: source order (p1 before p3), re-indexed 0..M-1.
        assertEquals(listOf("ref-1", "ref-3"), plan.extractedPages.map { it.processedImageRef })
        assertEquals(listOf(0, 1), plan.extractedPages.map { it.index })
        assertTrue(plan.extractedPages.all { it.documentId == plan.extractedDocument.id })
        assertTrue(plan.extractedPages.all { it.createdAtMillis == SPLIT_MILLIS && it.updatedAtMillis == SPLIT_MILLIS })
        // Ownership TRANSFERS with refs preserved: p1 keeps "src-1"/"ref-1".
        assertEquals("src-1", plan.extractedPages[0].sourceCaptureRef)
        assertEquals("src-3", plan.extractedPages[1].sourceCaptureRef)
        // Non-id page fields pass through (rotation, crop, enhancement).
        assertEquals(90, plan.extractedPages[0].rotationDegrees)
        assertEquals(4, plan.extractedPages[0].cropQuad!!.size)

        // Complement: ids/refs/timestamps UNTOUCHED, indices compacted 0..K-1,
        // relative order preserved (p0, p2, p4).
        assertEquals(listOf("p0", "p2", "p4"), plan.compactedSourcePages.map { it.id })
        assertEquals(listOf(0, 1, 2), plan.compactedSourcePages.map { it.index })
        assertEquals(listOf("ref-0", "ref-2", "ref-4"), plan.compactedSourcePages.map { it.processedImageRef })
        assertTrue(plan.compactedSourcePages.all { it.createdAtMillis == 1_000L && it.updatedAtMillis == 1_000L })
        assertTrue(plan.compactedSourcePages.all { it.documentId == fixture.source.id })

        // Source document: keeps id + title, bumps updatedAtMillis only.
        assertEquals(fixture.source.id, plan.compactedSourceDocument.id)
        assertEquals("Invoice", plan.compactedSourceDocument.title)
        assertEquals(SPLIT_MILLIS, plan.compactedSourceDocument.updatedAtMillis)
        assertEquals(fixture.source.createdAtMillis, plan.compactedSourceDocument.createdAtMillis)
        assertEquals(listOf("p0", "p2", "p4"), plan.compactedSourceDocument.pageIds)
        assertEquals(fixture.source.sourceType, plan.compactedSourceDocument.sourceType)
    }

    @Test
    fun titleRule_sourceTitlePlusExtractedSuffix() {
        val fixture = Fixture()
        val plan = fixture.engine.extract(fixture.source, fixture.pages, listOf("p2"), fixture.ids, fixture.time)

        assertEquals("Invoice (extracted)", plan.extractedDocument.title)
        assertEquals(fixture.source.sourceType, plan.extractedDocument.sourceType)
        assertEquals(SPLIT_MILLIS, plan.extractedDocument.createdAtMillis)
        assertEquals(plan.extractedPages.map { it.id }, plan.extractedDocument.pageIds)
    }

    @Test
    fun reMintedIds_shareNothingWithTheSource() {
        val fixture = Fixture()
        val plan = fixture.engine.extract(fixture.source, fixture.pages, listOf("p0", "p4"), fixture.ids, fixture.time)

        val sourcePageIds = fixture.pages.map { it.id }.toSet()
        val extractedIds = plan.extractedPages.map { it.id }
        assertEquals(extractedIds.size, extractedIds.toSet().size)
        assertTrue(extractedIds.none { it in sourcePageIds })
        assertTrue(plan.extractedDocument.id !in sourcePageIds)
        assertTrue(plan.extractedDocument.id != fixture.source.id)
        assertTrue(plan.extractedPages.all { it.documentId == plan.extractedDocument.id })
    }

    @Test
    fun emptySelection_throwsIllegalArgument() {
        val fixture = Fixture()
        try {
            fixture.engine.extract(fixture.source, fixture.pages, emptyList(), fixture.ids, fixture.time)
            fail("expected IllegalArgumentException for an empty selection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("non-empty"))
        }
    }

    @Test
    fun unknownPageIds_throwIllegalArgument() {
        val fixture = Fixture()
        try {
            fixture.engine.extract(fixture.source, fixture.pages, listOf("p0", "no-such-page"), fixture.ids, fixture.time)
            fail("expected IllegalArgumentException for unknown page ids")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("unknown page ids"))
        }
    }

    @Test
    fun duplicateSelection_throwsIllegalArgument() {
        val fixture = Fixture()
        try {
            fixture.engine.extract(fixture.source, fixture.pages, listOf("p0", "p0"), fixture.ids, fixture.time)
            fail("expected IllegalArgumentException for a duplicated selection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("duplicates"))
        }
    }

    @Test
    fun allPagesSelected_sourceBecomesHonestEmptyDocument() {
        val fixture = Fixture()
        val plan = fixture.engine.extract(
            document = fixture.source,
            pages = fixture.pages,
            selectedPageIds = fixture.pages.map { it.id },
            idGenerator = fixture.ids,
            timeSource = fixture.time,
        )

        // Every page transferred to the extracted document.
        assertEquals(5, plan.extractedPages.size)
        assertEquals(listOf("ref-0", "ref-1", "ref-2", "ref-3", "ref-4"), plan.extractedPages.map { it.processedImageRef })
        // The source stays behind as an HONEST empty document (PROD-006's
        // empty state + delete affordance apply downstream).
        assertTrue(plan.compactedSourcePages.isEmpty())
        assertTrue(plan.compactedSourceDocument.pageIds.isEmpty())
        assertEquals(fixture.source.id, plan.compactedSourceDocument.id)
        assertEquals(SPLIT_MILLIS, plan.compactedSourceDocument.updatedAtMillis)
    }

    // ------------------------------------------------------ executor laws

    @Test
    fun executor_zeroStorePuts_refsPreservedAndTransferred() = runTest {
        val fixture = Fixture()
        val plan = fixture.engine.extract(fixture.source, fixture.pages, listOf("p1", "p3"), fixture.ids, fixture.time)

        fixture.executor.apply(plan)

        // Split = MOVE: zero ContentStore puts — the refs were never the
        // store's to re-write; ownership transfers on the index alone.
        assertTrue("split performs zero store puts", fixture.store.putKeys.isEmpty())
        // Every source ref is still referenced exactly once: extracted or
        // complement. Nothing lost, nothing minted.
        val allRefs = (plan.extractedPages + plan.compactedSourcePages)
            .flatMap { listOfNotNull(it.processedImageRef, it.sourceCaptureRef) }
        assertEquals(fixture.sourceRefSet, allRefs.toSet())
        // Repository state matches the plan.
        assertEquals(plan.extractedDocument, fixture.repository.documentsById[plan.extractedDocument.id])
        assertEquals(plan.compactedSourceDocument, fixture.repository.documentsById[fixture.source.id])
        assertEquals(
            plan.extractedPages.map { it.id },
            fixture.repository.pagesByDoc[plan.extractedDocument.id]!!.map { it.id },
        )
        assertEquals(
            plan.compactedSourcePages.map { it.id },
            fixture.repository.pagesByDoc[fixture.source.id]!!.map { it.id },
        )
    }

    @Test
    fun executorOrder_extractedUpsertedBeforeCompactedSource() = runTest {
        val fixture = Fixture()
        val plan = fixture.engine.extract(fixture.source, fixture.pages, listOf("p2"), fixture.ids, fixture.time)

        fixture.executor.apply(plan)

        // LOAD-BEARING (packet §6.4): the extracted document — the
        // transferred refs' NEW owner — is upserted FIRST. Reversed, the
        // source's orphan sweep would delete the transferred refs before
        // the extracted document records them.
        assertEquals(
            listOf("upsert:" + plan.extractedDocument.id, "upsert:" + fixture.source.id),
            fixture.events,
        )
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

    // Store fake present ONLY to prove the split never touches it.
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

    private inner class Fixture {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val repository = RecordingRepository(events)
        private val counter = intArrayOf(0)
        val ids = IdGenerator {
            val value = "x-" + counter[0]
            counter[0] += 1
            value
        }
        val time = FakeTimeSource(startMillis = SPLIT_MILLIS)
        val engine = DocumentSplitEngine()
        val executor = SplitExecutor(repository)

        val source = Document(
            id = "doc-src",
            title = "Invoice",
            pageIds = listOf("p0", "p1", "p2", "p3", "p4"),
            sourceType = DocumentSource.IMPORTED,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )

        val pages = listOf(
            page("p0", 0, "ref-0", "src-0"),
            page("p1", 1, "ref-1", "src-1", rotationDegrees = 90, cropped = true),
            page("p2", 2, "ref-2", "src-2"),
            page("p3", 3, "ref-3", "src-3"),
            page("p4", 4, "ref-4", "src-4"),
        )

        val sourceRefSet: Set<String> = pages
            .flatMap { listOfNotNull(it.processedImageRef, it.sourceCaptureRef) }
            .toSet()

        init {
            // The store holds every source ref; the split must not add to it.
            for (ref in sourceRefSet) {
                store.stored[ref] = ("BYTES-" + ref).toByteArray()
            }
        }

        private fun page(
            id: String,
            index: Int,
            processedRef: String,
            sourceRef: String,
            rotationDegrees: Int = 0,
            cropped: Boolean = false,
        ): Page = Page(
            id = id,
            documentId = "doc-src",
            index = index,
            sourceCaptureRef = sourceRef,
            processedImageRef = processedRef,
            cropQuad = if (cropped) {
                listOf(
                    org.payswap.camscan.core.model.Corner(0f, 0f),
                    org.payswap.camscan.core.model.Corner(1f, 0f),
                    org.payswap.camscan.core.model.Corner(1f, 1f),
                    org.payswap.camscan.core.model.Corner(0f, 1f),
                )
            } else {
                null
            },
            rotationDegrees = rotationDegrees,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
    }

    private companion object {
        const val SPLIT_MILLIS = 1_000_000L
    }
}
