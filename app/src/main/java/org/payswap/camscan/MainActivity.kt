package org.payswap.camscan

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import java.util.UUID
import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.DocumentDetailStubFragment
import org.payswap.camscan.document.LibraryFragment
import org.payswap.camscan.document.PlaceholderScanFragment
import org.payswap.camscan.capture.camera.CameraScanLauncher
import org.payswap.camscan.document.PlaceholderScanLauncher
import org.payswap.camscan.document.persistence.FileContentStore
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.document.persistence.PersistentDocumentRepository
import org.payswap.camscan.document.viewer.DocumentReorderFragment
import org.payswap.camscan.document.viewer.DocumentViewerFragment
import org.payswap.camscan.export.DocumentExportFragment
import org.payswap.camscan.library.BatchSelectionFragment
import org.payswap.camscan.library.HomeFragment
import org.payswap.camscan.settings.SettingsFragment

/**

Single-activity product shell.

CAMSCAN-PROD-006 wiring: durable [PersistentDocumentRepository] over

[FileContentStore]. Contract fix pass: implements the FROZEN ScanHost —

fragmentManager / containerViewId are val properties and

onScanFinished takes a nullable document id — with no fragment-ktx

import (FragmentManager's member fragmentFactory setter is assigned

directly).
*/
class MainActivity : AppCompatActivity(), ScanHost {

private lateinit var repository: DocumentRepository
private lateinit var contentStore: ContentStore
private lateinit var scanLauncher: ScanLauncher

override fun onCreate(savedInstanceState: Bundle?) {
// Dependencies must exist before fragment restoration inside
// super.onCreate(); the factory re-attaches them on recreation.
val fileContentStore = FileContentStore.fromContext(this)
contentStore = fileContentStore
repository = PersistentDocumentRepository(
contentDir = fileContentStore.rootDir,
timeSource = TimeSource.SYSTEM,
idGenerator = IdGenerator { UUID.randomUUID().toString() },
)
scanLauncher = CameraScanLauncher() // PROD-013 integration: W1's real capture launcher (PROD-004 session-capable) replaces the placeholder
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
.replace(
R.id.app_fragment_container,
HomeFragment(repository, scanLauncher, contentStore),
TAG_HOME,
)
.commit()
}

// ScanHost — frozen val properties, not functions.
override val fragmentManager: FragmentManager
get() = supportFragmentManager
override val containerViewId: Int
get() = R.id.app_fragment_container
override fun onScanFinished(documentId: String?) {
// TODO(PROD-007): when documentId is non-null, open the viewer for the
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
// CAMSCAN-VERIFY-002 additive factory cases (constructor injection;
// per-instance state travels via the arguments bundle).
BatchSelectionFragment::class.java.name -> BatchSelectionFragment(repository, contentStore)
DocumentExportFragment::class.java.name -> DocumentExportFragment(repository, contentStore)
SettingsFragment::class.java.name -> SettingsFragment(repository, contentStore)
else -> super.instantiate(classLoader, className)
}

}
