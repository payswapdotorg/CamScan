package org.payswap.camscan.tools.ui

// CAMSCAN-VERIFY-002 — pure UI state of the Settings backup/restore
// surface. The phases are STRUCTURED values (counts and ids, not
// pre-formatted strings) so the fragment maps them onto resources; the
// busy flags enforce one backup or restore at a time. No scheduling
// state exists anywhere here — the backup engine has no scheduler and
// the UI does not fake one. No android imports: JVM-testable.

/** Honest failure reasons of a backup run (mapped to resources by the UI). */
enum class BackupUiReason {
    NO_DOCUMENTS,
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
}

/** Phase of the backup leg of the settings surface. */
sealed class BackupPhase {

    /** Nothing has run yet. */
    object Idle : BackupPhase()

    /** A backup build is in flight (honest busy state, no fake progress). */
    object Working : BackupPhase()

    /** A backup archive was produced and written. */
    class Done(
        val documents: Int,
        val pages: Int,
        val skippedPages: Int,
        val totalBytes: Long,
    ) : BackupPhase()

    /** The backup failed for the given honest reason. */
    class Failed(val reason: BackupUiReason) : BackupPhase()
}

/** Honest failure reasons of a restore run (mapped to resources by the UI). */
enum class RestoreUiReason {
    UNREADABLE_ARCHIVE,
}

/** Phase of the restore leg of the settings surface. */
sealed class RestorePhase {

    /** Nothing has run yet. */
    object Idle : RestorePhase()

    /** An archive is being read/planned/applied. */
    object Working : RestorePhase()

    /** The restore planner blocked on conflicts; nothing was written. */
    class Blocked(val conflictDocumentIds: List<String>) : RestorePhase()

    /** The restore finished with the honest per-document tally. */
    class Done(
        val added: Int,
        val replaced: Int,
        val kept: Int,
        val failed: Int,
        val pagesRestored: Int,
    ) : RestorePhase()

    /** The restore failed for the given honest reason. */
    class Failed(val reason: RestoreUiReason) : RestorePhase()
}

/** Busy/phase state holder of the backup + restore settings surface. */
class BackupRestoreUi {

    private var backupPhaseValue: BackupPhase = BackupPhase.Idle
    private var restorePhaseValue: RestorePhase = RestorePhase.Idle

    /** Current phase of the backup leg. */
    fun backupPhase(): BackupPhase = backupPhaseValue

    /** Current phase of the restore leg. */
    fun restorePhase(): RestorePhase = restorePhaseValue

    /** True while a backup or restore run is in flight. */
    fun isBusy(): Boolean {
        if (backupPhaseValue is BackupPhase.Working) return true
        if (restorePhaseValue is RestorePhase.Working) return true
        return false
    }

    /**
     * Marks the backup leg as working. Returns false (no state change)
     * when any run is already in flight.
     */
    fun beginBackup(): Boolean {
        if (isBusy()) return false
        backupPhaseValue = BackupPhase.Working
        return true
    }

    /** Sets the backup leg's terminal phase (ignored while not working). */
    fun finishBackup(phase: BackupPhase) {
        if (backupPhaseValue !is BackupPhase.Working) return
        backupPhaseValue = phase
    }

    /**
     * Marks the restore leg as working. Returns false (no state change)
     * when any run is already in flight.
     */
    fun beginRestore(): Boolean {
        if (isBusy()) return false
        restorePhaseValue = RestorePhase.Working
        return true
    }

    /** Sets the restore leg's terminal phase (ignored while not working). */
    fun finishRestore(phase: RestorePhase) {
        if (restorePhaseValue !is RestorePhase.Working) return
        restorePhaseValue = phase
    }
}
