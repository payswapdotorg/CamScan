package org.payswap.camscan.capture.camera

import org.payswap.camscan.capture.session.SessionPersistence
import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher

/**
 * CAMSCAN-PROD-001 §6.6 / CAMSCAN-PROD-004 §6.6 — the capture engine's
 * [ScanLauncher] implementation.
 *
 * Opens the real scan surface ([ScanFragment]) into the host shell's content
 * container via the host's FragmentManager (addToBackStack "scan") and returns
 * true when the transaction was committed.
 *
 * CAMSCAN-PROD-004 adds the OPTIONAL [sessionPersistence] constructor
 * parameter — default null keeps today's behavior exactly (no-arg
 * construction compiles and behaves identically: ids are UUIDs and a
 * finished scan reports no document id). The integration station swaps in
 * the wired construction (repository + content store + TimeSource +
 * idGenerator) with a ONE-LINE change in the SHELL's file — the scan
 * session, review surface, and persistence adapter all live in Worker-1's
 * trees behind this seam.
 *
 * Completion wiring:
 *  - scan_done_button => ScanSession.finish() => adapter persist (when
 *    wired) => [ScanHost.onScanFinished] with the durable document id, or
 *    null for an empty session / unwired-or-failed persistence;
 *  - leaving the surface without finishing (back navigation below the scan
 *    surface) => [ScanHost.onScanFinished] with null.
 */
class CameraScanLauncher(
    private val sessionPersistence: SessionPersistence? = null,
) : ScanLauncher {

    override fun startScan(host: ScanHost): Boolean {
        val scanFragment = ScanFragment()
        scanFragment.sessionPersistence = sessionPersistence
        scanFragment.onSessionFinished = { documentId ->
            // A durable document id when persistence is wired; null when the
            // session was empty or persistence was unwired/failed — the same
            // observable outcome the shell saw before PROD-004.
            host.onScanFinished(documentId)
        }
        scanFragment.onScanAbandoned = {
            // User left the scan surface without finishing.
            host.onScanFinished(null)
        }
        return try {
            host.fragmentManager.beginTransaction()
                .replace(host.containerViewId, scanFragment, SCAN_FRAGMENT_TAG)
                .addToBackStack(SCAN_BACK_STACK_NAME)
                .commit()
            true
        } catch (error: IllegalStateException) {
            // e.g. committing after the host's state was saved — report the
            // failure honestly instead of crashing the shell.
            false
        }
    }

    private companion object {
        const val SCAN_FRAGMENT_TAG = "scan"
        const val SCAN_BACK_STACK_NAME = "scan"
    }
}
