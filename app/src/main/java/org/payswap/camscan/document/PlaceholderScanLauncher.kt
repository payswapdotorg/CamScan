package org.payswap.camscan.document

import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher

/**

Placeholder [ScanLauncher]: opens [PlaceholderScanFragment] into the host

container so the shell ⇄ scan seam is exercised end to end. Returns true

on successful dispatch. The Tech Lead swaps this for Worker 1's real

launcher at integration — a one-line wiring change in MainActivity.

Aligned to the FROZEN seam: the entry point is [ScanLauncher.startScan],

and fragmentManager / containerViewId are ScanHost val PROPERTIES (read,

never invoked as functions).
*/
class PlaceholderScanLauncher : ScanLauncher {

override fun startScan(host: ScanHost): Boolean {
host.fragmentManager
.beginTransaction()
.replace(host.containerViewId, PlaceholderScanFragment())
.addToBackStack(BACK_STACK_PLACEHOLDER_SCAN)
.commit()
return true
}

companion object {
private const val BACK_STACK_PLACEHOLDER_SCAN = "workspace_placeholder_scan"
}

}
