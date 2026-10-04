package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-VERIFY-002 — JVM tests of the backup/restore UI state: the
// one-operation-at-a-time guard and the structured terminal phases.

class BackupRestoreUiTest {

    @Test
    fun backup_beginRejectsASecondConcurrentOperation() {
        val ui = BackupRestoreUi()
        assertTrue(ui.beginBackup())
        assertTrue(ui.backupPhase() is BackupPhase.Working)
        assertTrue(ui.isBusy())
        assertFalse("no concurrent backup", ui.beginBackup())
        assertFalse("no concurrent restore either", ui.beginRestore())
    }

    @Test
    fun backup_finishRecordsTheStructuredDonePhase() {
        val ui = BackupRestoreUi()
        ui.beginBackup()
        ui.finishBackup(BackupPhase.Done(documents = 3, pages = 7, skippedPages = 1, totalBytes = 1234L))
        val phase = ui.backupPhase()
        assertTrue(phase is BackupPhase.Done)
        phase as BackupPhase.Done
        assertEquals(3, phase.documents)
        assertEquals(7, phase.pages)
        assertEquals(1, phase.skippedPages)
        assertEquals(1234L, phase.totalBytes)
        assertFalse(ui.isBusy())
    }

    @Test
    fun backup_finishRecordsTheHonestFailureReasons() {
        val ui = BackupRestoreUi()
        ui.beginBackup()
        ui.finishBackup(BackupPhase.Failed(BackupUiReason.NO_DOCUMENTS))
        assertTrue(ui.backupPhase() is BackupPhase.Failed)
        assertEquals(BackupUiReason.NO_DOCUMENTS, (ui.backupPhase() as BackupPhase.Failed).reason)

        ui.beginBackup()
        ui.finishBackup(BackupPhase.Failed(BackupUiReason.WRITE_FAILED))
        assertEquals(BackupUiReason.WRITE_FAILED, (ui.backupPhase() as BackupPhase.Failed).reason)
    }

    @Test
    fun backup_finishIsIgnoredWhileIdle() {
        val ui = BackupRestoreUi()
        ui.finishBackup(BackupPhase.Done(1, 1, 0, 1L))
        assertTrue("idle stays idle", ui.backupPhase() is BackupPhase.Idle)
    }

    @Test
    fun restore_beginRejectsASecondConcurrentOperation() {
        val ui = BackupRestoreUi()
        assertTrue(ui.beginRestore())
        assertTrue(ui.restorePhase() is RestorePhase.Working)
        assertFalse(ui.beginBackup())
        assertFalse(ui.beginRestore())
    }

    @Test
    fun restore_blockedCarriesTheConflictDocumentIds() {
        val ui = BackupRestoreUi()
        ui.beginRestore()
        ui.finishRestore(RestorePhase.Blocked(listOf("doc-a", "doc-c")))
        val phase = ui.restorePhase()
        assertTrue(phase is RestorePhase.Blocked)
        assertEquals(listOf("doc-a", "doc-c"), (phase as RestorePhase.Blocked).conflictDocumentIds)
        assertFalse(ui.isBusy())
    }

    @Test
    fun restore_doneCarriesThePerDocumentTally() {
        val ui = BackupRestoreUi()
        ui.beginRestore()
        ui.finishRestore(
            RestorePhase.Done(added = 2, replaced = 1, kept = 4, failed = 1, pagesRestored = 9),
        )
        val phase = ui.restorePhase()
        assertTrue(phase is RestorePhase.Done)
        phase as RestorePhase.Done
        assertEquals(2, phase.added)
        assertEquals(1, phase.replaced)
        assertEquals(4, phase.kept)
        assertEquals(1, phase.failed)
        assertEquals(9, phase.pagesRestored)
    }

    @Test
    fun restore_finishIsIgnoredWhileIdle() {
        val ui = BackupRestoreUi()
        ui.finishRestore(RestorePhase.Failed(RestoreUiReason.UNREADABLE_ARCHIVE))
        assertTrue(ui.restorePhase() is RestorePhase.Idle)
    }

    @Test
    fun bothLegsStartIdle() {
        val ui = BackupRestoreUi()
        assertTrue(ui.backupPhase() is BackupPhase.Idle)
        assertTrue(ui.restorePhase() is RestorePhase.Idle)
        assertFalse(ui.isBusy())
    }
}
