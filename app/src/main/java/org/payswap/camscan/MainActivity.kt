package org.payswap.camscan

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.fragmentFactory
import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.DocumentDetailStubFragment
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.document.LibraryFragment
import org.payswap.camscan.document.PlaceholderScanFragment
import org.payswap.camscan.document.PlaceholderScanLauncher
import org.payswap.camscan.library.HomeFragment

/**

Single-activity product shell (CAMSCAN-PROD-005).

Hosts the fragment container, implements the lead's [ScanHost] seam, owns

the back-stack policy (Home is the root; nothing sits above it), and hands

the in-memory repository to fragments via a plain constructor-injecting

[WorkspaceFragmentFactory] — no DI framework.
*/
class MainActivity : AppCompatActivity(), ScanHost {

private lateinit var repository: DocumentRepository
private lateinit var scanLauncher: ScanLauncher

override fun onCreate(savedInstanceState: Bundle?) {
// Dependencies must exist before fragment restoration inside
// super.onCreate(); the factory re-attaches them on recreation.
repository = InMemoryDocumentRepository(TimeSource.System)
scanLauncher = PlaceholderScanLauncher()
supportFragmentManager.fragmentFactory = WorkspaceFragmentFactory(repository, scanLauncher)
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_main)
if (savedInstanceState == null) {
showHomeRoot()
}
}

private fun showHomeRoot() {
supportFragmentManager.beginTransaction()
.replace(R.id.app_fragment_container, HomeFragment(repository, scanLauncher), TAG_HOME)
.commit()
}

override fun fragmentManager(): FragmentManager = supportFragmentManager

override fun containerViewId(): Int = R.id.app_fragment_container

override fun onScanFinished(documentId: String) {
// TODO(PROD-006): when documentId is non-empty, open the document detail
// for the freshly scanned document instead of plain Home. Home refreshes
// itself by observing the repository flow.
supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
showHomeRoot()
}

companion object {
private const val TAG_HOME = "workspace_home_root"
}

}

/**

Constructor injection point (no DI). FragmentManager re-attaches saved

arguments onto factory-instantiated fragments, so per-instance state

(e.g. the detail document id) travels via the arguments bundle.
*/
class WorkspaceFragmentFactory(
private val repository: DocumentRepository,
private val scanLauncher: ScanLauncher,
) : FragmentFactory() {

override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
when (className) {
HomeFragment::class.java.name -> HomeFragment(repository, scanLauncher)
LibraryFragment::class.java.name -> LibraryFragment(repository)
DocumentDetailStubFragment::class.java.name -> DocumentDetailStubFragment(repository)
PlaceholderScanFragment::class.java.name -> PlaceholderScanFragment()
else -> super.instantiate(classLoader, className)
}

}
