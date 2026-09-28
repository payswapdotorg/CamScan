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
import org.payswap.camscan.core.model.CropQuad
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.time.TimeSource

/**

Pure-JVM behavior tests for the in-memory repository backing the shell.

JUnit 4 + kotlinx-coroutines-test only — no Android imports — so these run

under :app:testDebugUnitTest at the lead's integration station.
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
pages: List<Page> = emptyList(),
createdAtMillis: Long = updatedAtMillis - 1_000L,
): Document = Document(
id = id,
title = title,
createdAtMillis = createdAtMillis,
updatedAtMillis = updatedAtMillis,
pages = pages,
)

private fun page(index: Int, ref: String = "ref-$index"): Page = Page(
id = "page-$index",
index = index,
imageRef = ref,
thumbnailRef = null,
cropQuad = CropQuad(
topLeft = Corner(0f, 0f),
topRight = Corner(100f, 0f),
bottomRight = Corner(100f, 200f),
bottomLeft = Corner(0f, 200f),
),
)

// --- observeDocuments: emissions ---

@Test
fun observeDocuments_emitsEmptyList_forFreshRepository() = runTest {
assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

@Test
fun observeDocuments_emitsAddedDocument() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L))

val observed = repository.observeDocuments().first()

assertEquals(listOf("doc-1"), observed.map { it.id })
}

@Test
fun observeDocuments_reflectsDeletion() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L))
repository.deleteDocument("doc-1")

assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

// --- ordering ---

@Test
fun observeDocuments_ordersByUpdatedAtMillis_descending() = runTest {
repository.upsertDocument(document("a", updatedAtMillis = 100L))
repository.upsertDocument(document("b", updatedAtMillis = 300L))
repository.upsertDocument(document("c", updatedAtMillis = 200L))

val observed = repository.observeDocuments().first()

assertEquals(listOf("b", "c", "a"), observed.map { it.id })
}

// --- upsert semantics ---

@Test
fun upsertDocument_replacesAllFieldsAtomically_noMergeNoDuplicate() = runTest {
repository.upsertDocument(
document("doc-1", updatedAtMillis = 100L, title = "Old title", pages = listOf(page(0))),
)
repository.upsertDocument(
document(
"doc-1",
updatedAtMillis = 200L,
title = "New title",
pages = listOf(page(0), page(1), page(2)),
),
)

val observed = repository.observeDocuments().first()

assertEquals(1, observed.size)
val stored = observed.single()
assertEquals("doc-1", stored.id)
assertEquals("New title", stored.title)
assertEquals(3, stored.pages.size)
assertEquals(200L, stored.updatedAtMillis)
}

@Test
fun upsertDocument_rejectsBlankId_andChangesNothing() = runTest {
val accepted = repository.upsertDocument(document(" ", updatedAtMillis = 100L))

assertFalse(accepted)
assertEquals(emptyList<Document>(), repository.observeDocuments().first())
}

@Test
fun upsertDocument_getPagesReturnsExactlyTheNewPageSet() = runTest {
repository.upsertDocument(
document("doc-1", updatedAtMillis = 100L, pages = listOf(page(0), page(1))),
)
repository.upsertDocument(
document("doc-1", updatedAtMillis = 200L, pages = listOf(page(7), page(8), page(9))),
)

val refs = repository.getPages("doc-1").map { it.imageRef }

assertEquals(listOf("ref-7", "ref-8", "ref-9"), refs)
}

// --- updateTitle semantics ---

@Test
fun updateTitle_stampsTimeFromTimeSource_andPreservesCreatedAt() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L, createdAtMillis = 50L))
timeSource.now = 999L

assertTrue(repository.updateTitle("doc-1", "Renamed"))

val stored = repository.getDocument("doc-1")!!
assertEquals("Renamed", stored.title)
assertEquals(999L, stored.updatedAtMillis)
assertEquals(50L, stored.createdAtMillis)
}

@Test
fun updateTitle_rejectsBlankTitle_andChangesNothing() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L, title = "Keep me"))

assertFalse(repository.updateTitle("doc-1", " "))

assertEquals("Keep me", repository.getDocument("doc-1")!!.title)
}

@Test
fun updateTitle_unknownId_returnsFalse() = runTest {
assertFalse(repository.updateTitle("missing", "Anything"))
}

// --- delete semantics ---

@Test
fun deleteDocument_returnsTrueWhenPresent_falseOnSecondCall() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L))

assertTrue(repository.deleteDocument("doc-1"))
assertFalse(repository.deleteDocument("doc-1"))
}

// --- unknown-id behaviors ---

@Test
fun getDocument_unknownId_returnsNull() = runTest {
assertNull(repository.getDocument("missing"))
}

@Test
fun observeDocument_unknownId_emitsNull() = runTest {
assertNull(repository.observeDocument("missing").first())
}

@Test
fun getPages_unknownId_returnsEmptyList() = runTest {
assertTrue(repository.getPages("missing").isEmpty())
}

// --- observeDocument: updates ---

@Test
fun observeDocument_reflectsUpdates() = runTest {
repository.upsertDocument(document("doc-1", updatedAtMillis = 100L, title = "Old"))

assertEquals("Old", repository.observeDocument("doc-1").first()!!.title)

repository.upsertDocument(document("doc-1", updatedAtMillis = 200L, title = "New"))

assertEquals("New", repository.observeDocument("doc-1").first()!!.title)
}

// --- getPages index ordering ---

@Test
fun getPages_ordersByPageIndexAscending_regardlessOfUpsertOrder() = runTest {
repository.upsertDocument(
document("doc-1", updatedAtMillis = 100L, pages = listOf(page(2), page(0), page(1))),
)

assertEquals(listOf(0, 1, 2), repository.getPages("doc-1").map { it.index })
}

}
