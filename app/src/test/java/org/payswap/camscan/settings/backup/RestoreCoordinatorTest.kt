package org.payswap.camscan.settings.backup

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
import org.payswap.camscan.tools.backup.RestoreDecision
import org.payswap.camscan.tools.backup.RestorePlan

// CAMSCAN-VERIFY-002 — JVM tests of the restore coordinator: the full
// backup -> restore round-trip into a SECOND repository (documents and
// page images re-materialize through the public repository API), the
// NewerWins policy decisions, conflict blocking, and the honest
// unreadable-archive null.

class RestoreCoordinatorTest {

    private class RecordingStore : ContentStore {
        val stored = HashMap<String, ByteArray>()

        override suspend fun put(key: String, bytes: ByteArray): String {
            stored[key] = bytes
            return key
        }

        override suspend fun open(ref: String): ByteArray? = stored[ref]

        override suspend fun delete(ref: String): Boolean = stored.remove(ref) != null

        override suspend fun exists(ref: String): Boolean = stored.containsKey(ref)
    }

    /** Two documents, two readable pages each. */
    private fun seededRepository(stamp: Long): Pair<InMemoryDocumentRepository, RecordingStore> {
        val store = RecordingStore()
        val repository = InMemoryDocumentRepository(FakeTimeSource(stamp))
        store.stored["ref-a"] = PAGE_A_BYTES
        store.stored["ref-b"] = PAGE_B_BYTES
        for ((docIndex, docId) in listOf("doc-1", "doc-2").withIndex()) {
            val pages = listOf("ref-a", "ref-b").mapIndexed { index, ref ->
                Page(
                    id = "page-" + docIndex + "-" + index,
                    documentId = docId,
                    index = index,
                    processedImageRef = ref,
                    createdAtMillis = stamp,
                    updatedAtMillis = stamp,
                )
            }
            runBlocking {
                repository.upsertDocument(
                Document(
                    id = docId,
                    title = "Document " + docIndex,
                    pageIds = pages.map { it.id },
                    createdAtMillis = stamp,
                    updatedAtMillis = stamp,
                ),
                pages,
            )
            }
        }
        return repository to store
    }

    @Test
    fun roundTrip_restoresDocumentsAndPageImagesIntoAFreshRepository() = runTest {
        val (source, sourceStore) = seededRepository(SOURCE_STAMP)
        val backup = BackupCoordinator(source, sourceStore, FakeTimeSource(BACKUP_STAMP))
        val built = backup.build("0.1.0") as BackupBuildResult.Built

        val target = InMemoryDocumentRepository(FakeTimeSource(RESTORE_STAMP))
        val targetStore = RecordingStore()
        val restore = RestoreCoordinator(target, targetStore, FakeTimeSource(RESTORE_STAMP))

        val outcome = restore.readAndPlan(built.bytes)
        assertNotNull(outcome)
        assertTrue(outcome!!.plan is RestorePlan.Planned)
        val summary = restore.apply(outcome)

        assertEquals(2, summary.added)
        assertEquals(0, summary.replaced)
        assertEquals(0, summary.kept)
        assertEquals(0, summary.failed)
        assertEquals(4, summary.pagesRestored)

        val restoredDoc = target.getDocument("doc-1")
        assertNotNull(restoredDoc)
        assertEquals("Document 0", restoredDoc!!.title)
        val restoredPages = target.getPages("doc-1")
        assertEquals(2, restoredPages.size)
        // Page images re-materialize through the target store.
        assertTrue(
            targetStore.stored[restoredPages[0].processedImageRef]!!.contentEquals(PAGE_A_BYTES),
        )
        assertTrue(
            targetStore.stored[restoredPages[1].processedImageRef]!!.contentEquals(PAGE_B_BYTES),
        )
        // Source captures are honestly NOT in the archive.
        assertNull(restoredPages[0].sourceCaptureRef)
    }

    @Test
    fun newerWins_olderBackupKeepsTheNewerLocalDocument() = runTest {
        val (source, sourceStore) = seededRepository(SOURCE_STAMP)
        val backup = BackupCoordinator(source, sourceStore, FakeTimeSource(OLD_BACKUP_STAMP))
        val built = backup.build("0.1.0") as BackupBuildResult.Built

        // The live library holds the SAME document ids but NEWER edits.
        val (live, liveStore) = seededRepository(NEWER_LOCAL_STAMP)
        val restore = RestoreCoordinator(live, liveStore, FakeTimeSource(RESTORE_STAMP))

        val outcome = restore.readAndPlan(built.bytes)!!
        val planned = outcome.plan as RestorePlan.Planned
        assertTrue(planned.decisions.all { it is RestoreDecision.KeepExisting })
        val summary = restore.apply(outcome)
        assertEquals(0, summary.added)
        assertEquals(0, summary.replaced)
        assertEquals(2, summary.kept)
        assertEquals(0, summary.pagesRestored)
    }

    @Test
    fun newerWins_newerBackupReplacesTheOlderLocalDocument() = runTest {
        val (source, sourceStore) = seededRepository(NEWER_LOCAL_STAMP)
        val backup = BackupCoordinator(source, sourceStore, FakeTimeSource(BACKUP_STAMP))
        val built = backup.build("0.1.0") as BackupBuildResult.Built

        val (live, liveStore) = seededRepository(OLD_BACKUP_STAMP)
        val restore = RestoreCoordinator(live, liveStore, FakeTimeSource(RESTORE_STAMP))

        val outcome = restore.readAndPlan(built.bytes)!!
        val planned = outcome.plan as RestorePlan.Planned
        assertTrue(planned.decisions.all { it is RestoreDecision.ReplaceDocument })
        val summary = restore.apply(outcome)
        assertEquals(0, summary.added)
        assertEquals(2, summary.replaced)
        assertEquals(4, summary.pagesRestored)
    }

    @Test
    fun mixedLibrary_addsUnknownAndKeepsNewerExistingDocuments() = runTest {
        val (source, sourceStore) = seededRepository(SOURCE_STAMP)
        val backup = BackupCoordinator(source, sourceStore, FakeTimeSource(BACKUP_STAMP))
        val built = backup.build("0.1.0") as BackupBuildResult.Built

        // The live library knows doc-1 only, with a NEWER stamp.
        val live = InMemoryDocumentRepository(FakeTimeSource(NEWER_LOCAL_STAMP))
        val liveStore = RecordingStore()
        liveStore.stored["ref-live"] = PAGE_A_BYTES
        live.upsertDocument(
            Document(
                id = "doc-1",
                title = "Locally edited",
                pageIds = listOf("live-page"),
                createdAtMillis = NEWER_LOCAL_STAMP,
                updatedAtMillis = NEWER_LOCAL_STAMP,
            ),
            listOf(
                Page(
                    id = "live-page",
                    documentId = "doc-1",
                    index = 0,
                    processedImageRef = "ref-live",
                    createdAtMillis = NEWER_LOCAL_STAMP,
                    updatedAtMillis = NEWER_LOCAL_STAMP,
                ),
            ),
        )
        val restore = RestoreCoordinator(live, liveStore, FakeTimeSource(RESTORE_STAMP))

        val outcome = restore.readAndPlan(built.bytes)!!
        val summary = restore.apply(outcome)
        assertEquals("the unknown doc-2 is added", 1, summary.added)
        assertEquals("the newer local doc-1 is kept", 1, summary.kept)
        assertEquals(2, summary.pagesRestored)
    }

    @Test
    fun unreadableBytes_returnNull() = runTest {
        val (live, liveStore) = seededRepository(SOURCE_STAMP)
        val restore = RestoreCoordinator(live, liveStore, FakeTimeSource(RESTORE_STAMP))

        assertNull(restore.readAndPlan(ByteArray(0)))
        assertNull(restore.readAndPlan("definitely not a zip".toByteArray()))
    }

    @Test
    fun emptyArchiveBytes_returnNull() = runTest {
        val (live, liveStore) = seededRepository(SOURCE_STAMP)
        val restore = RestoreCoordinator(live, liveStore, FakeTimeSource(RESTORE_STAMP))
        assertNull(restore.readAndPlan(ByteArray(0)))
    }

    private companion object {
        const val SOURCE_STAMP = 1_000_000L
        const val OLD_BACKUP_STAMP = 900_000L
        const val BACKUP_STAMP = 1_200_000L
        const val NEWER_LOCAL_STAMP = 2_000_000L
        const val RESTORE_STAMP = 3_000_000L
        val PAGE_A_BYTES = "page-a-bytes".toByteArray()
        val PAGE_B_BYTES = "page-b-bytes".toByteArray()
    }
}
