package org.payswap.camscan.settings.backup

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.ocr.FakeTimeSource
import org.payswap.camscan.tools.backup.BackupArchiveReader
import org.payswap.camscan.tools.backup.BackupEntryKind
import org.payswap.camscan.tools.backup.BackupFileIo
import org.payswap.camscan.tools.backup.BackupPaths

// CAMSCAN-VERIFY-002 — JVM tests of the backup coordinator: the
// archive layout it writes (verified by reading it back through the
// delivered BackupArchiveReader with the sha256 checks that implies),
// the honest tallies, and determinism under a fixed clock.

class BackupCoordinatorTest {

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

    /** In-memory io the delivered reader can verify through. */
    private class MemoryIo(var bytes: ByteArray? = null) : BackupFileIo {
        override fun write(path: String, bytes: ByteArray) {
            this.bytes = bytes
        }

        override fun read(path: String): ByteArray? = bytes

        override fun list(prefix: String): List<String> = emptyList()
    }

    private inner class Fixture(pageRefs: List<String?> = listOf("ref-a", "ref-b")) {
        val store = RecordingStore()
        val repository = InMemoryDocumentRepository(FakeTimeSource(FIXED_MILLIS))

        init {
            store.stored["ref-a"] = PAGE_A_BYTES
            store.stored["ref-b"] = PAGE_B_BYTES
            val pages = pageRefs.mapIndexed { index, ref ->
                Page(
                    id = "page-" + index,
                    documentId = DOC_ID,
                    index = index,
                    processedImageRef = ref,
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

        val coordinator = BackupCoordinator(
            repository = repository,
            contentStore = store,
            timeSource = FakeTimeSource(FIXED_MILLIS),
        )
    }

    @Test
    fun build_emptyLibrary_reportsNoDocuments() = runTest {
        val store = RecordingStore()
        val repository = InMemoryDocumentRepository(FakeTimeSource(FIXED_MILLIS))
        val coordinator = BackupCoordinator(repository, store, FakeTimeSource(FIXED_MILLIS))

        val result = coordinator.build("0.1.0")
        assertTrue(result is BackupBuildResult.NoDocuments)
    }

    @Test
    fun build_writesTheDocumentedArchiveLayout() = runTest {
        val fixture = Fixture()
        val result = fixture.coordinator.build("0.1.0")

        assertTrue(result is BackupBuildResult.Built)
        val built = result as BackupBuildResult.Built
        assertEquals(1, built.report.documents)
        assertEquals(2, built.report.pages)
        assertEquals(0, built.report.skippedPages)
        assertEquals(3, built.report.entries)

        // Read it back through the DELIVERED reader (sha256 verification
        // of every member is the reader's own contract).
        val io = MemoryIo(built.bytes)
        val contents = BackupArchiveReader.read("archive", io)
        assertEquals(3, contents.manifest.entryCount)
        assertNotNull(contents.member(BackupPaths.documentIndexPath(DOC_ID)))
        assertEquals(
            PAGE_A_BYTES.size.toLong(),
            contents.member("documents/" + DOC_ID + "/pages/page-0.png")!!.size.toLong(),
        )
        assertEquals(
            PAGE_B_BYTES.size.toLong(),
            contents.member("documents/" + DOC_ID + "/pages/page-1.png")!!.size.toLong(),
        )
        val kinds = contents.manifest.entries.map { it.kind }
        assertEquals(
            listOf(BackupEntryKind.DOCUMENT_INDEX, BackupEntryKind.PAGE_IMAGE, BackupEntryKind.PAGE_IMAGE),
            kinds,
        )
    }

    @Test
    fun build_missingPageImages_areSkippedAndExcludedFromTheIndex() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", null, "unopened-ref"))
        val result = fixture.coordinator.build("0.1.0")

        assertTrue(result is BackupBuildResult.Built)
        val built = result as BackupBuildResult.Built
        assertEquals(2, built.report.skippedPages)
        assertEquals(1, built.report.pages)
        assertEquals(2, built.report.entries)

        val io = MemoryIo(built.bytes)
        val contents = BackupArchiveReader.read("archive", io)
        // Only the readable page has a member; the index still parses and
        // lists exactly that page (round-trips through the codec).
        assertNotNull(contents.member("documents/" + DOC_ID + "/pages/page-0.png"))
        assertEquals(null, contents.member("documents/" + DOC_ID + "/pages/page-1.png"))
        assertEquals(null, contents.member("documents/" + DOC_ID + "/pages/page-2.png"))
    }

    @Test
    fun build_isDeterministic_forFixedStateAndClock() = runTest {
        val first = Fixture()
        val second = Fixture()
        val firstBuild = first.coordinator.build("0.1.0") as BackupBuildResult.Built
        val secondBuild = second.coordinator.build("0.1.0") as BackupBuildResult.Built

        assertTrue(firstBuild.bytes.contentEquals(secondBuild.bytes))
    }

    @Test
    fun build_carriesTheVersionHintAndManifestStamp() = runTest {
        val fixture = Fixture()
        val built = fixture.coordinator.build("0.1.0") as BackupBuildResult.Built

        val io = MemoryIo(built.bytes)
        val contents = BackupArchiveReader.read("archive", io)
        assertEquals("0.1.0", contents.manifest.appVersionHint)
        assertEquals(FIXED_MILLIS, contents.manifest.createdAtMillis)
    }

    @Test
    fun archiveFileName_isDeterministicFromTheTimestamp() {
        val fixture = Fixture()
        assertEquals("camscan-backup-1000000.zip", fixture.coordinator.archiveFileName(FIXED_MILLIS))
    }

    private companion object {
        const val DOC_ID = "doc-1"
        const val FIXED_MILLIS = 1_000_000L
        val PAGE_A_BYTES = "page-a-bytes".toByteArray()
        val PAGE_B_BYTES = "page-b-bytes".toByteArray()
    }
}
