package org.payswap.camscan.core.navigation


import androidx.fragment.app.FragmentManager


/**
 * Shell ⇄ scan-engine seam — lead-owned (PRODUCT-ARCHITECTURE-LOCK §12).
 *
 * Worker 2's shell implements [ScanHost] and triggers scans through
 * [ScanLauncher]; Worker 1's capture engine provides the launcher
 * implementation that opens the real scan surface. Until the capture engine
 * is integrated the shell uses its own placeholder launcher.
 */
interface ScanHost {
    val fragmentManager: FragmentManager
    val containerViewId: Int


    /** Called when a finished scan produced a document (id), or null when aborted. */
    fun onScanFinished(documentId: String?)
}


interface ScanLauncher {
    /** Opens the scan surface attached to [host]. Returns true when a scan surface opened. */
    fun startScan(host: ScanHost): Boolean
}
