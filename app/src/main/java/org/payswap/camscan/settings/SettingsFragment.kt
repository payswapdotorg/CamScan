package org.payswap.camscan.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.export.formatSizeBytes
import org.payswap.camscan.settings.backup.AndroidBackupDestinations
import org.payswap.camscan.settings.backup.BackupBuildResult
import org.payswap.camscan.settings.backup.BackupCoordinator
import org.payswap.camscan.settings.backup.RestoreCoordinator
import org.payswap.camscan.tools.backup.RestorePlan
import org.payswap.camscan.tools.ui.BackupPhase
import org.payswap.camscan.tools.ui.BackupRestoreUi
import org.payswap.camscan.tools.ui.BackupUiReason
import org.payswap.camscan.tools.ui.RestorePhase
import org.payswap.camscan.tools.ui.RestoreUiReason

// CAMSCAN-VERIFY-002 — the Settings surface: "Back up now" (build the
// archive through the backup engines, write it to a system-picker
// destination; fallback to the app's external-files dir with the path
// SHOWN), and "Restore from backup…" (pick an archive -> verify +
// plan through the restore engines -> apply into the live repository
// -> a visible per-document summary). No scheduling UI exists because
// the engine has none — nothing fakes it. The scope note states
// honestly what a backup contains. State lives in the pure
// [BackupRestoreUi] holder; one operation at a time.
class SettingsFragment(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_settings) {

    private val ui = BackupRestoreUi()

    private val backupCoordinator by lazy {
        BackupCoordinator(
            repository = repository,
            contentStore = contentStore,
            timeSource = TimeSource.SYSTEM,
        )
    }

    private val restoreCoordinator by lazy {
        RestoreCoordinator(
            repository = repository,
            contentStore = contentStore,
            timeSource = TimeSource.SYSTEM,
        )
    }

    /** Picker for the backup destination (a .zip document the user names). */
    private val backupDestinationLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            onBackupDestinationPicked(uri)
        }

    /** Picker for the archive to restore. */
    private val restoreSourceLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onRestoreSourcePicked(uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Toolbar>(R.id.settings_toolbar).apply {
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
            navigationContentDescription = context.getString(R.string.workspace_settings_up_cd)
        }
        view.findViewById<MaterialButton>(R.id.settings_backup_button).apply {
            contentDescription = context.getString(R.string.workspace_settings_backup_cd)
            setOnClickListener { launchBackupDestinationPicker() }
        }
        view.findViewById<MaterialButton>(R.id.settings_restore_button).apply {
            contentDescription = context.getString(R.string.workspace_settings_restore_cd)
            setOnClickListener { launchRestoreSourcePicker() }
        }
    }

    // ------------------------------------------------------------ backup

    /** Opens the system picker for the backup destination. */
    private fun launchBackupDestinationPicker() {
        if (ui.isBusy()) return
        val host = view ?: return
        val suggestedName = backupCoordinator.archiveFileName(TimeSource.SYSTEM.nowMillis())
        try {
            backupDestinationLauncher.launch(suggestedName)
        } catch (expected: ActivityNotFoundException) {
            // Documented fallback route: external-files dir, path shown.
            runBackupToFallbackDir(host)
        }
    }

    /** Runs the backup build and writes it to the picked destination. */
    private fun onBackupDestinationPicked(uri: Uri?) {
        val host = view ?: return
        if (uri == null) return
        if (!ui.beginBackup()) return
        renderBackupBusy(host, true)
        viewLifecycleOwner.lifecycleScope.launch {
            val build = withContext(Dispatchers.IO) {
                backupCoordinator.build(APP_VERSION_HINT)
            }
            when (build) {
                is BackupBuildResult.NoDocuments -> {
                    ui.finishBackup(BackupPhase.Failed(BackupUiReason.NO_DOCUMENTS))
                    renderBackupBusy(host, false)
                    renderBackupPhase(host, ui.backupPhase())
                }
                is BackupBuildResult.Built -> {
                    val written = withContext(Dispatchers.IO) {
                        AndroidBackupDestinations.writeToPickedUri(
                            requireContext(),
                            uri,
                            build.bytes,
                        )
                    }
                    if (written) {
                        ui.finishBackup(
                            BackupPhase.Done(
                                documents = build.report.documents,
                                pages = build.report.pages,
                                skippedPages = build.report.skippedPages,
                                totalBytes = build.report.totalBytes,
                            ),
                        )
                    } else {
                        ui.finishBackup(BackupPhase.Failed(BackupUiReason.WRITE_FAILED))
                    }
                    renderBackupBusy(host, false)
                    renderBackupPhase(host, ui.backupPhase())
                }
            }
        }
    }

    /** Fallback backup: app external-files dir, path shown to the user. */
    private fun runBackupToFallbackDir(host: View) {
        if (!ui.beginBackup()) return
        renderBackupBusy(host, true)
        viewLifecycleOwner.lifecycleScope.launch {
            val build = withContext(Dispatchers.IO) {
                backupCoordinator.build(APP_VERSION_HINT)
            }
            when (build) {
                is BackupBuildResult.NoDocuments -> {
                    ui.finishBackup(BackupPhase.Failed(BackupUiReason.NO_DOCUMENTS))
                    renderBackupBusy(host, false)
                    renderBackupPhase(host, ui.backupPhase())
                }
                is BackupBuildResult.Built -> {
                    val fileName = backupCoordinator.archiveFileName(TimeSource.SYSTEM.nowMillis())
                    val target = withContext(Dispatchers.IO) {
                        AndroidBackupDestinations.writeToFallback(
                            requireContext(),
                            fileName,
                            build.bytes,
                        )
                    }
                    if (target == null) {
                        ui.finishBackup(BackupPhase.Failed(BackupUiReason.WRITE_FAILED))
                    } else {
                        backupPathNote = getString(
                            R.string.workspace_settings_backup_location,
                            target.absolutePath,
                        )
                        ui.finishBackup(
                            BackupPhase.Done(
                                documents = build.report.documents,
                                pages = build.report.pages,
                                skippedPages = build.report.skippedPages,
                                totalBytes = build.report.totalBytes,
                            ),
                        )
                    }
                    renderBackupBusy(host, false)
                    renderBackupPhase(host, ui.backupPhase())
                }
            }
        }
    }

    // ----------------------------------------------------------- restore

    /** Opens the system picker for the archive to restore. */
    private fun launchRestoreSourcePicker() {
        if (ui.isBusy()) return
        try {
            restoreSourceLauncher.launch(arrayOf(BACKUP_MIME))
        } catch (expected: ActivityNotFoundException) {
            val host = view ?: return
            showSnackbar(
                host,
                getString(R.string.workspace_settings_restore_failed_reason_picker),
            )
        }
    }

    /** Reads, verifies, plans and applies the picked archive. */
    private fun onRestoreSourcePicked(uri: Uri?) {
        val host = view ?: return
        if (uri == null) return
        if (!ui.beginRestore()) return
        renderRestoreBusy(host, true)
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                AndroidBackupDestinations.readFromPickedUri(requireContext(), uri)
            }
            if (bytes == null) {
                ui.finishRestore(RestorePhase.Failed(RestoreUiReason.UNREADABLE_ARCHIVE))
                renderRestoreBusy(host, false)
                renderRestorePhase(host, ui.restorePhase())
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                restoreCoordinator.readAndPlan(bytes)
            }
            if (outcome == null) {
                ui.finishRestore(RestorePhase.Failed(RestoreUiReason.UNREADABLE_ARCHIVE))
                renderRestoreBusy(host, false)
                renderRestorePhase(host, ui.restorePhase())
                return@launch
            }
            when (val plan = outcome.plan) {
                is RestorePlan.Conflicts -> {
                    ui.finishRestore(
                        RestorePhase.Blocked(plan.conflicts.map { it.documentId }),
                    )
                }
                is RestorePlan.Planned -> {
                    val summary = withContext(Dispatchers.IO) {
                        restoreCoordinator.apply(outcome)
                    }
                    ui.finishRestore(
                        RestorePhase.Done(
                            added = summary.added,
                            replaced = summary.replaced,
                            kept = summary.kept,
                            failed = summary.failed,
                            pagesRestored = summary.pagesRestored,
                        ),
                    )
                }
            }
            renderRestoreBusy(host, false)
            renderRestorePhase(host, ui.restorePhase())
        }
    }

    // ------------------------------------------------------------ render

    /** Toggles the buttons + status lines while a run is in flight. */
    private fun renderBackupBusy(host: View, busy: Boolean) {
        host.findViewById<MaterialButton>(R.id.settings_backup_button)?.isEnabled = !busy
        host.findViewById<MaterialButton>(R.id.settings_restore_button)?.isEnabled = !busy
        host.findViewById<TextView>(R.id.settings_backup_status)?.text =
            if (busy) getString(R.string.workspace_settings_working) else ""
        host.findViewById<TextView>(R.id.settings_restore_status)?.text =
            if (busy) getString(R.string.workspace_settings_working) else ""
    }

    /** Toggles the buttons + status lines while a restore is in flight. */
    private fun renderRestoreBusy(host: View, busy: Boolean) {
        renderBackupBusy(host, busy)
    }

    /** Maps the backup phase onto the visible status line. */
    private fun renderBackupPhase(host: View, phase: BackupPhase) {
        val status = host.findViewById<TextView>(R.id.settings_backup_status) ?: return
        val text = when (phase) {
            is BackupPhase.Idle -> ""
            is BackupPhase.Working -> getString(R.string.workspace_settings_working)
            is BackupPhase.Done -> {
                val base = getString(
                    R.string.workspace_settings_backup_success,
                    phase.documents,
                    phase.pages,
                    formatSizeBytes(phase.totalBytes),
                )
                if (phase.skippedPages > 0) {
                    base + getString(
                        R.string.workspace_settings_backup_skipped,
                        phase.skippedPages,
                    )
                } else {
                    base
                }
            }
            is BackupPhase.Failed -> when (phase.reason) {
                BackupUiReason.NO_DOCUMENTS ->
                    getString(R.string.workspace_settings_backup_none)
                BackupUiReason.DESTINATION_UNAVAILABLE ->
                    getString(
                        R.string.workspace_settings_backup_failed,
                        getString(R.string.workspace_settings_backup_failed_reason_destination),
                    )
                BackupUiReason.WRITE_FAILED ->
                    getString(
                        R.string.workspace_settings_backup_failed,
                        getString(R.string.workspace_settings_backup_failed_reason_write),
                    )
            }
        }
        status.text = text
        val pathLine = host.findViewById<TextView>(R.id.settings_backup_path) ?: return
        pathLine.text = backupPathNote
    }

    /** Maps the restore phase onto the visible summary line. */
    private fun renderRestorePhase(host: View, phase: RestorePhase) {
        val status = host.findViewById<TextView>(R.id.settings_restore_status) ?: return
        status.text = when (phase) {
            is RestorePhase.Idle -> ""
            is RestorePhase.Working -> getString(R.string.workspace_settings_working)
            is RestorePhase.Blocked -> getString(
                R.string.workspace_settings_restore_conflict,
                phase.conflictDocumentIds.size,
                phase.conflictDocumentIds.joinToString(", "),
            )
            is RestorePhase.Done -> getString(
                R.string.workspace_settings_restore_success,
                phase.added,
                phase.replaced,
                phase.kept,
                phase.failed,
                phase.pagesRestored,
            )
            is RestorePhase.Failed -> getString(
                R.string.workspace_settings_restore_failed,
                getString(R.string.workspace_settings_restore_failed_reason_archive),
            )
        }
    }

    // The fallback route's visible path note (kept out of the phase so the
    // structured phase stays pure; the UI owns presentation strings).
    private var backupPathNote: String = ""

    private fun showSnackbar(host: View, message: String) {
        Snackbar.make(host, message, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val BACKUP_MIME = "application/zip"
        private const val APP_VERSION_HINT = "0.1.0"
    }
}
