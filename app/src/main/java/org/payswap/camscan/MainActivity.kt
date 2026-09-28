package org.payswap.camscan

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.fragmentFactory
import java.util.UUID
import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.DocumentDetailStubFragment
import org.payswap.camscan.document.LibraryFragment
import org.payswap.camscan.document.PlaceholderScanFragment
import org.payswap.camscan.document.PlaceholderScanLauncher
import org.payswap.camscan.document.persistence.FileContentStore
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.document.persistence.PersistentDocumentRepository
import org.payswap.camscan.document.viewer.DocumentReorderFragment
import org.payswap.camscan.document.viewer.DocumentViewerFragment
import org.payswap.camscan.library.HomeFragment

/**

Single-activity product shell.

CAMSCAN-PROD-006 change: the shell now wires the durable

[PersistentDocumentRepository] over [FileContentStore] (replacing the

PROD-005 in-memory construction). No other root-file changes.
*/
class MainActivity : AppCompatActivity(), ScanHost {

private lateinit var repository: DocumentRepository
private lateinit var scanLauncher: ScanLauncher

override fun onCreate(savedInstanceState: Bundle?) {
// Dependencies must exist before fragment restoration inside
// super.onCreate(); the factory re-attaches them on recreation.
val contentStore = FileContentStore.fromContext(this)
repository = PersistentDocumentRepository(
contentDir = contentStore.rootDir,
timeSource = TimeSource.System,
idGenerator = IdGenerator { UUID.randomUUID().toString() },
)
scanLauncher = PlaceholderScanLauncher()
supportFragmentManager.fragmentFactory =
WorkspaceFragmentFactory(repository, contentStore, scanLauncher)
super.onCreate(savedInstanceState)
setContentView(R.layout.activity_main)
if (savedInstanceState == null) {
showHomeRoot()
}
}

private fun showHomeRoot() {
supportFragmentManager.beginTransaction()
.replace(R.id.app_fragment_container, HomeFragment(repository, scanLauncher, contentStoreOf()), TAG_HOME)
.commit()
}

private fun contentStoreOf(): ContentStore = FileContentStore.fromContext(this)

override fun fragmentManager(): FragmentManager = supportFragmentManager

override fun containerViewId(): Int = R.id.app_fragment_container

override fun onScanFinished(documentId: String) {
// TODO(PROD-007): when documentId is non-empty, open the viewer for the
// freshly scanned document instead of plain Home. Home refreshes itself
// by observing the repository flow.
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

(e.g. the viewer document id) travels via the arguments bundle.
*/
class WorkspaceFragmentFactory(
private val repository: DocumentRepository,
private val contentStore: ContentStore,
private val scanLauncher: ScanLauncher,
) : FragmentFactory() {

override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
when (className) {
HomeFragment::class.java.name -> HomeFragment(repository, scanLauncher, contentStore)
LibraryFragment::class.java.name -> LibraryFragment(repository, contentStore)
DocumentDetailStubFragment::class.java.name -> DocumentDetailStubFragment(repository)
PlaceholderScanFragment::class.java.name -> PlaceholderScanFragment()
DocumentViewerFragment::class.java.name -> DocumentViewerFragment(repository, contentStore)
DocumentReorderFragment::class.java.name -> DocumentReorderFragment(repository)
else -> super.instantiate(classLoader, className)
}

}
