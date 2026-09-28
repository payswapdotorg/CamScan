package org.payswap.camscan.document.persistence

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.core.time.TimeSource

/**

JVM tests for the durable repository (CAMSCAN-PROD-006): temp dirs, fixed

TimeSource, counting IdGenerator. Aligned to the FROZEN contract —

upsertDocument(document, pages) two-arg Unit form, deleteDocument Unit, no

updateTitle/observeDocument — with page mutations exercised through the

RepositoryPageOps extensions. Pins the save/reopen cycle, crash recovery,

orphan sweeping, byte-stable index rewrites, and observer consistency.
*/
class PersistentDocumentRepositoryTest {

private class FixedTimeSource(var now: Long = 0L) : TimeSource {
override fun nowMillis(): Long = now
}

private class CountingIdGenerator : IdGenerator {
private var counter = 0
override fun newId(): String = "minted-${++counter}"
}

private lateinit var root: File
private lateinit var timeSource: FixedTimeSource
private lateinit var repository: PersistentDocumentRepository

@Before
fun setUp() {
root = Files.createTempDirectory("persrepo").toFile()
timeSource = FixedTimeSource()
repository = newRepo(root)
}

@After
fun tearDown() {
root.deleteRecursively()
}

private fun newRepo(
dir: File,
idGenerator: IdGenerator = CountingIdGenerator(),
timeSource: TimeSource = this.timeSource,
): PersistentDocumentRepository = PersistentDocumentRepository(
contentDir = dir,
timeSource = timeSource,
idGenerator = idGenerator,
)

private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

private fun document(
id: String,
updatedAtMillis: Long,
title: String = "Document $id",
createdAtMillis: Long = updatedAtMillis - 1_000L,
): Document = Document(
id = id,
title = title,
createdAtMillis = createdAtMillis,
updatedAtMillis = updatedAtMillis,
)

private fun page(id: String, index: Int, ref: String? = null): Page = Page(
id = id,
index = index,
processedImageRef = ref,
thumbnailRef = null,
cropQuad = if (ref == null) {
null
} else {
listOf(
Corner(0f, 0f),
Corner(1f, 0f),
Corner(1f, 1f),
Corner(0f, 1f),
)
},
enhancement = PageEnhancementMode.NONE,
rotationDegrees = 0,
)

private fun indexFile(dir: File): File = File(dir, PersistentDocumentRepository.INDEX_FILE)

// --- construction ---

@Test
fun freshRepository_startsHealthyAndEmpty() = runTest {
assertEquals(PersistentDocumentRepository.IndexHealth.HEALTHY, repository.indexHealth)
assertTrue(repository.observeDocuments().first().isEmpty())
}

// --- id minting ---

@Test
fun upsertDocument_mintsIdsForBlankDocumentAndPageIds() = runTest {
val generator = CountingIdGenerator()
val repo = newRepo(root, idGenerator = generator)

repo.upsertDocument(document(" ", 100L, title = "Auto"), emptyList())
assertEquals(listOf("minted-1"), repo.observeDocuments().first().map { it.id })

repo.upsertDocument(
document("doc-2", 200L),
listOf(page(" ", 0), page("p1", 1)),
)
val pages = repo.getPages("doc-2")
assertEquals("minted-2", pages[0].id)
assertEquals("p1", pages[1].id)
assertEquals(listOf(0, 1), pages.map { it.index })
}

// --- contract: observe emissions ---

@Test
fun observeDocuments_emitsAddedUpdatedAndDeletedDocuments() = runTest {
repository.upsertDocument(document("doc-1", 100L), emptyList())
assertEquals(listOf("doc-1"), repository.observeDocuments().first().map { it.id })

repository.upsertDocument(document("doc-1", 200L, title = "Updated"), emptyList())
val afterUpdate = repository.observeDocuments().first()
assertEquals(listOf("doc-1"), afterUpdate.map { it.id })
assertEquals("Updated", afterUpdate.single().title)

repository.deleteDocument("doc-1")
assertTrue(repository.observeDocuments().first().isEmpty())
}

// --- contract: atomic replace of document AND pages ---

@Test
fun upsertDocument_replacesDocumentAndPagesAtomically() = runTest {
repository.upsertDocument(
document("doc-1", 100L, title = "Old"),
listOf(page("p0", 0)),
)
repository.upsertDocument(
document("doc-1", 200L, title = "New"),
listOf(page("p0", 0), page("p1", 1)),
)
val observed = repository.observeDocuments().first()
assertEquals(1, observed.size)
assertEquals("New", observed.single().title)
assertEquals(200L, observed.single().updatedAtMillis)
assertEquals(listOf("p0", "p1"), repository.getPages("doc-1").map { it.id })
}

// --- contract: ordering ---

@Test
fun observeDocuments_ordersByUpdatedAtMillis_descending() = runTest {
repository.upsertDocument(document("a", 100L), emptyList())
repository.upsertDocument(document("b", 300L), emptyList())
repository.upsertDocument(document("c", 200L), emptyList())
assertEquals(listOf("b", "c", "a"), repository.observeDocuments().first().map { it.id })
}

// --- contract: unknown ids ---

@Test
fun unknownIds_behaveBenignly() = runTest {
assertNull(repository.getDocument("missing"))
assertTrue(repository.getPages("missing").isEmpty())
repository.deleteDocument("missing") // Unit contract: benign
assertTrue(repository.observeDocuments().first().isEmpty())
}

// --- THE save/reopen test ---

@Test
fun saveReopen_newInstanceSeesIdenticalDocumentsAndBlobs() = runTest {
val store = FileContentStore(root)
val ref0 = store.put(byteArrayOf(1, 2, 3), "p0")
val ref1 = store.put(byteArrayOf(4, 5, 6, 7), "p1")
val savedDocument = Document(
id = "doc-1",
title = "Quarterly scan",
createdAtMillis = 1_000L,
updatedAtMillis = 2_000L,
)
val savedPages = listOf(
page("p0", 0, ref0).copy(
enhancement = PageEnhancementMode.GRAYSCALE,
rotationDegrees = 90,
),
page("p1", 1, ref1),
)
repository.upsertDocument(savedDocument, savedPages)

val reopened = newRepo(root)

assertEquals(PersistentDocumentRepository.IndexHealth.HEALTHY, reopened.indexHealth)
assertEquals(listOf("doc-1"), reopened.observeDocuments().first().map { it.id })
assertEquals(savedDocument, reopened.getDocument("doc-1"))
assertEquals(savedPages, reopened.getPages("doc-1"))
assertEquals(
listOf(ref0, ref1),
reopened.getPages("doc-1").map { it.processedImageRef },
)
assertTrue(store.exists(ref0))
assertTrue(store.exists(ref1))
assertArrayEquals(byteArrayOf(1, 2, 3), store.open(ref0))
}

// --- reorder persistence ---

@Test
fun reorder_persistsAcrossReload() = runTest {
repository.upsertDocument(
document("doc-1", 100L),
listOf(page("p0", 0), page("p1", 1), page("p2", 2)),
)
timeSource.now = 900L
val updated = repository.reorderPages("doc-1", listOf("p2", "p0", "p1"), timeSource)
assertEquals(listOf("p2", "p0", "p1"), updated!!.pages.map { it.id })
assertEquals(900L, updated.updatedAtMillis)

val reopened = newRepo(root)
val pages = reopened.getPages("doc-1")
assertEquals(listOf("p2", "p0", "p1"), pages.map { it.id })
assertEquals(listOf(0, 1, 2), pages.map { it.index })
assertEquals(900L, reopened.getDocument("doc-1")!!.updatedAtMillis)
}

// --- orphan sweep on page delete ---

@Test
fun removePage_sweepsOrphanBlob_liveRefsIntact() = runTest {
val store = FileContentStore(root)
val ref0 = store.put(byteArrayOf(1, 1), "p0")
val ref1 = store.put(byteArrayOf(2, 2), "p1")
repository.upsertDocument(
document("doc-1", 100L),
listOf(page("p0", 0, ref0), page("p1", 1, ref1)),
)
timeSource.now = 777L
val updated = repository.removePage("doc-1", "p0", timeSource)
assertEquals(listOf("p1"), updated!!.pages.map { it.id })
assertEquals(777L, updated.updatedAtMillis)
assertFalse(store.exists(ref0))
assertTrue(store.exists(ref1))
assertTrue(indexFile(root).isFile)
assertEquals(listOf("p1"), repository.getPages("doc-1").map { it.id })
}

// --- crash simulation: stray blob cleaned on next write ---

@Test
fun strayBlob_cleanedOnNextWrite_crashSimulation() = runTest {
val store = FileContentStore(root)
val liveRef = store.put(byteArrayOf(1, 2, 3), "p0")
repository.upsertDocument(
document("doc-1", 100L),
listOf(page("p0", 0, liveRef)),
)
// Crash between index-write and sweep: an unreferenced blob and a
// stale tmp are left on disk.
File(root, "crash-orphan.bin").writeBytes(byteArrayOf(9))
File(root, ".index.json.stale.tmp").writeText("partial")
assertTrue(store.exists("crash-orphan.bin"))

// Any write runs the sweep; the frozen upsert carries doc + pages.
repository.upsertDocument(
document("doc-1", 100L, title = "Renamed"),
listOf(page("p0", 0, liveRef)),
)

assertFalse(File(root, "crash-orphan.bin").exists())
assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
assertTrue(store.exists(liveRef))
assertEquals("Renamed", repository.getDocument("doc-1")!!.title)
}

// --- corrupt index recovery matrix ---

@Test
fun corruptIndex_recoversViaBackupOrEmptyStart_nextWriteRebuildsPrimary() = runTest {
// Scenario A: corrupt primary + good backup → restore, then healthy.
val dirA = tempDir("recovery-a")
val repoA1 = newRepo(dirA)
repoA1.upsertDocument(document("doc-a", 100L), emptyList())
repoA1.upsertDocument(document("doc-b", 200L), emptyList()) // .bak now holds {doc-a}
indexFile(dirA).writeText("{ this is not json")
val repoA2 = newRepo(dirA)
assertEquals(
PersistentDocumentRepository.IndexHealth.RESTORED_FROM_BACKUP,
repoA2.indexHealth,
)
assertEquals(listOf("doc-a"), repoA2.observeDocuments().first().map { it.id })
assertEquals(
listOf("doc-a"),
IndexJsonCodec.deserialize(indexFile(dirA).readText()).documents.map { it.id },
)
repoA2.upsertDocument(document("doc-c", 300L), emptyList())
assertEquals(PersistentDocumentRepository.IndexHealth.HEALTHY, repoA2.indexHealth)
val repoA3 = newRepo(dirA)
assertEquals(
listOf("doc-a", "doc-c"),
repoA3.observeDocuments().first().map { it.id }.sorted(),
)
dirA.deleteRecursively()

// Scenario B: corrupt primary, no backup → honest empty start; the
// next write rebuilds a healthy primary.
val dirB = tempDir("recovery-b")
indexFile(dirB).writeText("garbage{{{")
val repoB1 = newRepo(dirB)
assertEquals(PersistentDocumentRepository.IndexHealth.RECOVERED_EMPTY, repoB1.indexHealth)
assertTrue(repoB1.observeDocuments().first().isEmpty())
repoB1.upsertDocument(document("doc-d", 400L), emptyList())
assertEquals(PersistentDocumentRepository.IndexHealth.HEALTHY, repoB1.indexHealth)
assertEquals(
listOf("doc-d"),
IndexJsonCodec.deserialize(indexFile(dirB).readText()).documents.map { it.id },
)
dirB.deleteRecursively()
}

// --- byte stability + observer consistency ---

@Test
fun indexWrites_areByteStable_andConcurrentObserversStayConsistent() = runTest {
val docA = document("doc-a", 100L, title = "Alpha")
val docB = document("doc-b", 200L, title = "Beta")
repository.upsertDocument(docA, emptyList())
repository.upsertDocument(docB, emptyList())
val primary = indexFile(root)
val before = primary.readBytes()

// (a) Re-serializing the same logical state reproduces identical bytes.
assertTrue(repository.rewriteIndexNow())
assertArrayEquals(before, primary.readBytes())

// (b) Same logical state, different insertion order and directory
// → identical bytes (canonical id-sorted serialization).
val dir2 = tempDir("persrepo-order")
val repo2 = newRepo(dir2)
repo2.upsertDocument(docB, emptyList())
repo2.upsertDocument(docA, emptyList())
assertArrayEquals(before, indexFile(dir2).readBytes())
dir2.deleteRecursively()

// (c) Two concurrent collectors see identical snapshot sequences:
// [] → [doc-a] → [doc-b, doc-a] (updatedAt-desc ordering).
val dispatcher = UnconfinedTestDispatcher(testScheduler)
val seen1 = mutableListOf<List<Document>>()
val seen2 = mutableListOf<List<Document>>()
val collector1 = launch(dispatcher) {
repository.observeDocuments().take(3).toList(seen1)
}
val collector2 = launch(dispatcher) {
repository.observeDocuments().take(3).toList(seen2)
}
runCurrent()
repository.upsertDocument(docA.copy(title = "Renamed A"), emptyList())
repository.upsertDocument(docB.copy(title = "Renamed B"), emptyList())
runCurrent()
collector1.join()
collector2.join()
assertEquals(
seen1.map { snapshot -> snapshot.map { it.id } },
seen2.map { snapshot -> snapshot.map { it.id } },
)
assertEquals(
listOf(listOf<String>(), listOf("doc-a"), listOf("doc-b", "doc-a")),
seen1.map { snapshot -> snapshot.map { it.id } },
)
assertEquals(3, seen1.size)
}

}
