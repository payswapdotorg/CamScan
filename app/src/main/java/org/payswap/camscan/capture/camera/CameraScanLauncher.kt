package org.payswap.camscan.capture.camera


import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher


/**
 * CAMSCAN-PROD-001 §6.6 — the capture engine's [ScanLauncher] implementation.
 *
 * Opens the real scan surface ([ScanFragment]) into the host shell's content
 * container via the host's FragmentManager (addToBackStack "scan") and returns
 * true when the transaction was committed.
 *
 * Completion wiring for the foundation slice: real document ids arrive with
 * the scan-session/persistence work order (PROD-004); until then a finished
 * scan reports no document id yet, and leaving the surface without captures
 * (back navigation) reports the same. Both paths call
 * [ScanHost.onScanFinished] with null.
 */
class CameraScanLauncher : ScanLauncher {

    override fun startScan(host: ScanHost): Boolean {
        val scanFragment = ScanFragment()
        scanFragment.onCaptureResult = { _ ->
            // The shots are handed to the session/persistence flow in PROD-004;
            // until then a finished scan has no document id to report.
            host.onScanFinished(null)
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
