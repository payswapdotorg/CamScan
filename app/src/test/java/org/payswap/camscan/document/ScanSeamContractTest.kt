package org.payswap.camscan.document

import androidx.fragment.app.FragmentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher

/**

Pure-JVM contract test for the shell ⇄ scan seam types (CAMSCAN-PROD-005).

Fakes prove the protocol: a launcher reports success and receives its host;

the host records finished document ids in order; a failed launch never

triggers the callback.

The androidx.fragment.app.FragmentManager import is signature-forced by the

frozen ScanHost contract. The fake's fragmentManager() is never invoked on

the JVM — it fails loudly if the protocol path ever touches it — so this

test carries no Android runtime dependency and runs under

:app:testDebugUnitTest.
*/
class ScanSeamContractTest {

private class RecordingScanHost : ScanHost {
val finishedDocumentIds = mutableListOf<String>()

override fun fragmentManager(): FragmentManager =
error("FragmentManager must never be touched by the JVM seam-contract path")

override fun containerViewId(): Int = 42

override fun onScanFinished(documentId: String) {
finishedDocumentIds += documentId
}
}

/** Protocol stand-in for PlaceholderScanLauncher's seam behavior. */
private class StubScanLauncher : ScanLauncher {
val launchedHosts = mutableListOf<ScanHost>()
var nextResult: Boolean = true

override fun launchScan(host: ScanHost): Boolean {
launchedHosts += host
return nextResult
}
}

@Test
fun launcher_returnsTrue_andReceivesItsHost() {
val host = RecordingScanHost()
val launcher = StubScanLauncher()

val result = launcher.launchScan(host)

assertTrue(result)
assertSame(host, launcher.launchedHosts.single())
}

@Test
fun fullSeamLoop_launchThenFinish_reachesTheSameHost_inOrder() {
val host = RecordingScanHost()
val launcher = StubScanLauncher()

launcher.launchScan(host)
host.onScanFinished("doc-1")
host.onScanFinished("doc-2")

assertEquals(listOf("doc-1", "doc-2"), host.finishedDocumentIds)
assertSame(host, launcher.launchedHosts.single())
}

@Test
fun failedLaunch_neverInvokesTheHostCallback() {
val host = RecordingScanHost()
val launcher = StubScanLauncher().apply { nextResult = false }

val result = launcher.launchScan(host)

assertFalse(result)
assertTrue(host.finishedDocumentIds.isEmpty())
}

}
