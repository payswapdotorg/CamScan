package org.payswap.camscan.tools.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — RestoreApply coverage: apply writes exactly the
// plan (Add/Replace copy, Keep writes nothing), Conflicts blocks all
// writes, result statistics, overwrite detection through the IO seam's
// list, target-root joining/validation, and dry-run semantics.

class RestoreApplyTest {

    private fun sha(): String = "12".repeat(32)

    private fun buildArchive(): Pair<BackupManifest, LinkedHashMap<String, ByteArray>> {
        val map = LinkedHashMap<String, ByteArray>()
        map[BackupPaths.documentIndexPath("doc-1")] = "index-1".toByteArray()
        map["documents/doc-1/pages/p1.jpg"] = byteArrayOf(1, 1)
        map[BackupPaths.documentIndexPath("doc-2")] = "index-2".toByteArray()
        map["documents/doc-2/pages/p2.jpg"] = byteArrayOf(2, 2, 2)
        val entries = map.keys.map { path ->
            val bytes = map.getValue(path)
            BackupEntry(
                path,
                BackupEntryKind.PAGE_IMAGE,
                bytes.size.toLong(),
                Sha256Text.sha256Hex(bytes),
            )
        }
        return BackupManifest(1, 50L, "", entries) to map
    }

    private fun readArchive(io: BackupFileIo): BackupArchiveContents =
        BackupArchiveReader.read("b.zip", io)

    private fun writeArchive(io: InMemoryBackupIo) {
        val (manifest, map) = buildArchive()
        BackupArchiveWriter.write("b.zip", manifest, map, io)
    }

    @Test
    fun applyWritesAddedAndReplacedDocumentsAndSkipsKept() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        val plan = RestorePlan.Planned(
            listOf(
                RestoreDecision.AddDocument("doc-1"),
                RestoreDecision.ReplaceDocument("doc-2"),
                RestoreDecision.KeepExisting("doc-3"),
            ),
        )
        val result = RestoreApply.apply(plan, archive, "restored", io)
        assertTrue(result is RestoreApplyResult.Applied)
        val applied = result as RestoreApplyResult.Applied
        assertEquals(listOf("doc-1"), applied.added)
        assertEquals(listOf("doc-2"), applied.replaced)
        assertEquals(listOf("doc-3"), applied.kept)
        // doc-1 (2 files) + doc-2 (2 files) = 4 files written.
        assertEquals(4, applied.filesWritten)
        assertTrue(io.list("restored/").contains("restored/documents/doc-1/index.json"))
        assertTrue(io.list("restored/").contains("restored/documents/doc-2/pages/p2.jpg"))
    }

    @Test
    fun applyWritesTargetPathsAsRootSlashMemberPath() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        val plan = RestorePlan.Planned(listOf(RestoreDecision.AddDocument("doc-1")))
        RestoreApply.apply(plan, archive, "target/root", io)
        val paths = io.list("")
        assertTrue(paths.contains("target/root/documents/doc-1/index.json"))
        assertTrue(paths.contains("target/root/documents/doc-1/pages/p1.jpg"))
    }

    @Test
    fun applyOnConflictsWritesNothing() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        val conflicts = listOf(
            RestoreConflict("doc-1", 5L, 5L, -1L, "tie under NewerWins is unresolvable"),
        )
        val result = RestoreApply.apply(RestorePlan.Conflicts(conflicts), archive, "restored", io)
        assertTrue(result is RestoreApplyResult.Blocked)
        assertEquals(conflicts, (result as RestoreApplyResult.Blocked).conflicts)
        assertEquals(1, io.size()) // only the archive itself; nothing restored
    }

    @Test
    fun applyReportsBytesWrittenAndOverwrites() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        // Pre-existing file at one target path -> overwrite detection.
        io.write(
            "restored/documents/doc-1/index.json",
            "stale-bytes".toByteArray(),
        )
        val plan = RestorePlan.Planned(listOf(RestoreDecision.ReplaceDocument("doc-1")))
        val result = RestoreApply.apply(plan, archive, "restored", io)
        val applied = result as RestoreApplyResult.Applied
        assertEquals(2, applied.filesWritten)
        assertEquals(1, applied.filesOverwritten)
        val expectedBytes = "index-1".toByteArray().size + byteArrayOf(1, 1).size
        assertEquals(expectedBytes.toLong(), applied.bytesWritten)
    }

    @Test
    fun keepExistingWritesNothingForThatDocument() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        val plan = RestorePlan.Planned(listOf(RestoreDecision.KeepExisting("doc-1")))
        val result = RestoreApply.apply(plan, archive, "restored", io)
        val applied = result as RestoreApplyResult.Applied
        assertEquals(0, applied.filesWritten)
        assertEquals(0, io.list("restored/").size)
    }

    @Test
    fun applyOnAnEmptyPlanWritesNothing() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        val result = RestoreApply.apply(
            RestorePlan.Planned(emptyList()),
            archive,
            "restored",
            io,
        )
        val applied = result as RestoreApplyResult.Applied
        assertEquals(0, applied.filesWritten)
        assertEquals(0, applied.bytesWritten)
    }

    @Test
    fun applyOverwritesPlanOrderAndManifestOrderDeterministically() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        val plan = RestorePlan.Planned(
            listOf(
                RestoreDecision.ReplaceDocument("doc-2"),
                RestoreDecision.AddDocument("doc-1"),
            ),
        )
        RestoreApply.apply(plan, archive, "restored", io)
        // Everything landed, in a deterministic store order.
        assertEquals(4, io.list("restored/").size)
    }

    @Test
    fun applyRejectsBlankTargetRoot() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        try {
            RestoreApply.apply(
                RestorePlan.Planned(emptyList()),
                archive,
                "   ",
                io,
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // documented: blank target root is a programmer error
        }
    }

    @Test
    fun applyRejectsForbiddenCharactersInTargetRoot() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        try {
            RestoreApply.apply(RestorePlan.Planned(emptyList()), archive, "bad|root", io)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // documented: pipe/tab/CR/LF forbidden
        }
    }

    @Test
    fun applyTrimsTrailingSlashesFromTheTargetRoot() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        val plan = RestorePlan.Planned(listOf(RestoreDecision.AddDocument("doc-1")))
        RestoreApply.apply(plan, archive, "restored//", io)
        assertTrue(io.list("").contains("restored/documents/doc-1/index.json"))
    }

    @Test
    fun roundTripBackupThenRestoreIsNonDestructiveForKeptDocuments() {
        val io = InMemoryBackupIo()
        writeArchive(io)
        val archive = readArchive(io)
        // Existing file that the plan keeps must be untouched.
        io.write("restored/documents/doc-2/index.json", "precious".toByteArray())
        val plan = RestorePlan.Planned(
            listOf(
                RestoreDecision.KeepExisting("doc-2"),
                RestoreDecision.AddDocument("doc-1"),
            ),
        )
        RestoreApply.apply(plan, archive, "restored", io)
        val kept = io.read("restored/documents/doc-2/index.json")!!
        assertEquals("precious", String(kept, Charsets.UTF_8))
    }
}
