package org.payswap.camscan.document.persistence

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.InMemoryDocumentRepository

/**

JVM tests for the page-operation extensions over the frozen contract

(CAMSCAN-PROD-006), exercised against the in-memory implementation — the

extensions are implementation-agnostic get+upsert composites, so passing

here means they behave identically over the persistent implementation.
*/
class RepositoryPageOpsTest {

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

private suspend fun seed() {
repository.upsertDocument(
Document(
id = "doc-1",
title = "Doc",
pageIds = listOf("p0", "p1", "p2"),
createdAtMillis = 0L,
updatedAtMillis = 100L,
),
listOf(
Page(id = "p0", documentId = "doc-1", index = 0, createdAtMillis = 0L, updatedAtMillis = 0L),
Page(id = "p1", documentId = "doc-1", index = 1, createdAtMillis = 0L, updatedAtMillis = 0L),
Page(id = "p2", documentId = "doc-1", index = 2, createdAtMillis = 0L, updatedAtMillis = 0L),
),
)
}

@Test
fun removePage_reIndexesRemainingPages_andStampsUpdatedAt() = runTest {
seed()
timeSource.now = 777L
val updated = repository.removePage("doc-1", "p0", timeSource)
assertEquals(listOf("p1", "p2"), updated!!.pageIds)
assertEquals(listOf(0, 1), repository.getPages("doc-1").map { it.index })
assertEquals(777L, updated.updatedAtMillis)
assertEquals(listOf("p1", "p2"), repository.getPages("doc-1").map { it.id })
}

@Test
fun removePage_unknownPageOrDocument_returnsNull() = runTest {
seed()
assertNull(repository.removePage("doc-1", "nope", timeSource))
assertNull(repository.removePage("missing", "p0", timeSource))
}

@Test
fun reorderPages_appliesNewOrder_withFreshIndices() = runTest {
seed()
timeSource.now = 500L
val updated = repository.reorderPages("doc-1", listOf("p2", "p0", "p1"), timeSource)
assertEquals(listOf("p2", "p0", "p1"), updated!!.pageIds)
assertEquals(listOf(0, 1, 2), repository.getPages("doc-1").map { it.index })
assertEquals(500L, updated.updatedAtMillis)
}

@Test
fun reorderPages_mismatchedPageSet_returnsNull() = runTest {
seed()
assertNull(repository.reorderPages("doc-1", listOf("p0", "p1"), timeSource))
assertNull(repository.reorderPages("doc-1", listOf("p0", "p1", "zz"), timeSource))
assertNull(repository.reorderPages("missing", listOf("p0", "p1", "p2"), timeSource))
}

@Test
fun reorderPages_duplicateIds_returnsNull_andLeavesOrderUnchanged() = runTest {
seed()
assertNull(repository.reorderPages("doc-1", listOf("p0", "p0", "p1"), timeSource))
assertEquals(listOf("p0", "p1", "p2"), repository.getPages("doc-1").map { it.id })
}

@Test
fun movePage_movesAndReindexes() = runTest {
seed()
timeSource.now = 300L
val afterUp = repository.movePage("doc-1", "p2", -1, timeSource)
assertEquals(listOf("p0", "p2", "p1"), afterUp!!.pageIds)
assertEquals(listOf(0, 1, 2), repository.getPages("doc-1").map { it.index })
val afterDown = repository.movePage("doc-1", "p0", 1, timeSource)
assertEquals(listOf("p2", "p0", "p1"), afterDown!!.pageIds)
}

@Test
fun movePage_boundaryOffsets_returnNull() = runTest {
seed()
assertNull(repository.movePage("doc-1", "p0", -1, timeSource))
assertNull(repository.movePage("doc-1", "p2", 1, timeSource))
assertNull(repository.movePage("doc-1", "p1", 0, timeSource))
assertNull(repository.movePage("doc-1", "zz", 1, timeSource))
assertNull(repository.movePage("missing", "p0", 1, timeSource))
}

}
