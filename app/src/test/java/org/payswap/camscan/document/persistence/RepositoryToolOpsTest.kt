package org.payswap.camscan.document.persistence

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.InMemoryDocumentRepository

// RepositoryToolOpsTest (CAMSCAN-VERIFY-001): the replacePageRaster
// extension over the frozen contract (the single write every viewer tool
// persists through), exercised against the in-memory implementation like
// RepositoryPageOpsTest — the op is an implementation-agnostic
// get+upsert composite, so passing here means identical behavior over the
// persistent implementation.

class RepositoryToolOpsTest {

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
                pageIds = listOf("p0", "p1"),
                createdAtMillis = 0L,
                updatedAtMillis = 100L,
            ),
            listOf(
                Page(
                    id = "p0",
                    documentId = "doc-1",
                    index = 0,
                    processedImageRef = "ref-p0",
                    rotationDegrees = 90,
                    createdAtMillis = 0L,
                    updatedAtMillis = 0L,
                ),
                Page(
                    id = "p1",
                    documentId = "doc-1",
                    index = 1,
                    processedImageRef = "ref-p1",
                    rotationDegrees = 0,
                    createdAtMillis = 0L,
                    updatedAtMillis = 0L,
                ),
            ),
        )
    }

    @Test
    fun replacePageRaster_swapsRefZeroesRotationAndStamps() = runTest {
        seed()
        timeSource.now = 500L
        val updated = repository.replacePageRaster("doc-1", "p0", "ref-p0-edited", timeSource)
        assertNotNull(updated)
        assertEquals("ref-p0-edited", updated!!.processedImageRef)
        assertEquals(0, updated.rotationDegrees)
        assertEquals(500L, updated.updatedAtMillis)
        val document = repository.getDocument("doc-1")!!
        assertEquals(500L, document.updatedAtMillis)
        assertEquals(listOf("p0", "p1"), document.pageIds)
    }

    @Test
    fun replacePageRaster_leavesOtherPagesUntouched() = runTest {
        seed()
        timeSource.now = 500L
        repository.replacePageRaster("doc-1", "p0", "ref-p0-edited", timeSource)
        val pages = repository.getPages("doc-1")
        assertEquals(2, pages.size)
        val untouched = pages.first { it.id == "p1" }
        assertEquals("ref-p1", untouched.processedImageRef)
        assertEquals(0L, untouched.updatedAtMillis)
        assertEquals(1, untouched.index)
    }

    @Test
    fun replacePageRaster_unknownPageOrDocument_returnsNull() = runTest {
        seed()
        assertNull(repository.replacePageRaster("doc-1", "nope", "ref", timeSource))
        assertNull(repository.replacePageRaster("no-doc", "p0", "ref", timeSource))
    }

    @Test
    fun replacePageRaster_emptyRefIsRejected() = runTest {
        seed()
        assertNull(repository.replacePageRaster("doc-1", "p0", "", timeSource))
        // Nothing was written: the page keeps its original ref.
        assertEquals("ref-p0", repository.getPages("doc-1").first { it.id == "p0" }.processedImageRef)
    }

    @Test
    fun replacePageRaster_sourceCaptureRefStaysNonDestructive() = runTest {
        seed()
        repository.replacePageRaster("doc-1", "p0", "ref-p0-edited", timeSource)
        val page = repository.getPages("doc-1").first { it.id == "p0" }
        // The original capture reference is untouched (non-destructive edit).
        assertEquals(null, page.sourceCaptureRef)
        assertEquals("ref-p0-edited", page.processedImageRef)
    }
}
