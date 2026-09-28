package org.payswap.camscan.document

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.time.TimeSource

/**

Pure-JVM behavior tests for the in-memory repository backing the shell.

JUnit 4 + kotlinx-coroutines-test only — no Android imports — so these run

under :app:testDebugUnitTest at the lead's integration station.

Aligned to the FROZEN contracts: upsertDocument(document, pages) is a

two-arg Unit form, deleteDocument returns Unit, per-document observation

is a client filter over observeDocuments(), title mutation goes through

the implementation-level renameDocument helper, and page fixtures use the

frozen Page.cropQuad type List<Corner>.
*/
class InMemoryDocumentRepositoryTest {

/** Deterministic clock: never advances on its own; tests move it explicitly. */
private class FixedTimeSource(var now: Long = 0L) : TimeSource {
override fun nowMillis(): Long = now
}

private lateinit var timeSource: FixedTimeSource
private lateinit var repository: InMemoryDocumentRepository

@Before
fun setUp() {
timeSource = FixedTimeSource()
repository = InMemoryDocumentRepository(timeSource)
}

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

private fun page(index: Int, ref: String = "ref-$index"): Page = Page(
id = "page-$index",
index = index,
processedImageRef = ref,
thumbnailRef = null,
cropQuad = listOf(
Corner(0f, 0f),
Corner(100f, 0f),
Corner(100f, 200f),
Corner(0f, 200f),
),
)

// --- observeDocuments: emissions ---

@Test
fun observeDocuments_emitsEmptyList_forFreshRepository() = runTest {
assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

@Test
fun observeDocuments_emitsAddedDocument() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L), emptyList())

val observed = repository.observeDocuments().first()

assertEquals(listOf("doc-1"), observed.map { it.id })
}

@Test
fun observeDocuments_reflectsDeletion() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L), emptyList())
repository.deleteDocument("doc-1")

assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

// --- ordering ---

@Test
fun observeDocuments_ordersByUpdatedAtMillis_descending() = runTest {
repository.upsertDocument(document("a", updatedAtMillis = 100L), emptyList())
repository.upsertDocument(document("b", updatedAtMillis = 300L), emptyList())
repository.upsertDocument(document("c", updatedAtMillis = 200L), emptyList())

val observed = repository.observeDocuments().first()

assertEquals(listOf("b", "c", "a"), observed.map { it.id })
}

// --- upsert semantics ---

@Test
fun upsertDocument_replacesAllFieldsAtomically_noMergeNoDuplicate() = runTest {
repository.upsertDocument(
document("doc-1", updatedAtMillis = 100L, title = "Old title"),
listOf(page(0)),
)
repository.upsertDocument(
document("doc-1", updatedAtMillis = 200L, title = "New title"),
listOf(page(0), page(1), page(2)),
)

val observed = repository.observeDocuments().first()

assertEquals(1, observed.size)
val stored = observed.single()
assertEquals("doc-1", stored.id)
assertEquals("New title", stored.title)
assertEquals(200L, stored.updatedAtMillis)
assertEquals(3, repository.getPages("doc-1").size)
}

@Test
fun upsertDocument_ignoresBlankId_andChangesNothing() = runTest {
repository.upsertDocument(document(" ", updatedAtMillis = 100L), emptyList())

assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

@Test
fun upsertDocument_getPagesReturnsExactlyTheNewPageSet() = runTest {
repository.upsertDocument(
document("doc-1", updatedAtMillis = 100L),
listOf(page(0), page(1)),
)
repository.upsertDocument(
document("doc-1", updatedAtMillis = 200L),
listOf(page(7), page(8), page(9)),
)

val refs = repository.getPages("doc-1").map { it.processedImageRef }

assertEquals(listOf("ref-7", "ref-8", "ref-9"), refs)
}

// --- renameDocument (implementation-level title mutation) ---

@Test
fun renameDocument_stampsTimeFromTimeSource_andPreservesCreatedAt() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L, createdAtMillis = 50L), emptyList())
timeSource.now = 999L

assertTrue(repository.renameDocument("doc-1", "Renamed"))

val stored = repository.getDocument("doc-1")!!
assertEquals("Renamed", stored.title)
assertEquals(999L, stored.updatedAtMillis)
assertEquals(50L, stored.createdAtMillis)
}

@Test
fun renameDocument_rejectsBlankTitle_andChangesNothing() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L, title = "Keep me"), emptyList())

assertFalse(repository.renameDocument("doc-1", " "))

assertEquals("Keep me", repository.getDocument("doc-1")!!.title)
}

@Test
fun renameDocument_unknownId_returnsFalse() = runTest {
assertFalse(repository.renameDocument("missing", "Anything"))
}

// --- delete semantics ---

@Test
fun deleteDocument_removesThenIsBenignOnRepeat() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L), emptyList())

repository.deleteDocument("doc-1")
assertEquals(emptyList<Document>(), repository.observeDocuments().first())

repository.deleteDocument("doc-1") // Unit contract: benign on repeat
assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

// --- unknown-id behaviors ---

@Test
fun unknownIds_behaveBenignly() = runTest {
assertNull(repository.getDocument("missing"))
assertTrue(repository.getPages("missing").isEmpty())
assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

// --- observeDocuments: updates ---

@Test
fun observeDocuments_reflectsTitleUpdate() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L, title = "Old"), emptyList())

assertEquals("Old", repository.observeDocuments().first().single().title)

repository.upsertDocument(document("doc-1", updatedAtMillis = 200L, title = "New"), emptyList())

assertEquals("New", repository.observeDocuments().first().single().title)
}

// --- getPages index ordering ---

@Test
fun getPages_ordersByPageIndexAscending_regardlessOfUpsertOrder() = runTest {
repository.upsertDocument(
document("doc-1", updatedAtMillis = 100L),
listOf(page(2), page(0), page(1)),
)

assertEquals(listOf(0, 1, 2), repository.getPages("doc-1").map { it.index })
}

}
