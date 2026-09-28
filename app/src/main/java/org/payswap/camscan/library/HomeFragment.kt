package org.payswap.camscan.library

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.navigation.ScanHost
import org.payswap.camscan.core.navigation.ScanLauncher
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.document.viewer.DocumentViewerFragment

/**

Home surface: app-name toolbar, primary New Scan entry, recent documents

(top 10 by updatedAtMillis descending), and the Library affordance.

CAMSCAN-PROD-006: rows open the real document viewer.
*/
class HomeFragment(
private val repository: DocumentRepository,
private val scanLauncher: ScanLauncher,
private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_home) {

private val rowAdapter = DocumentRowAdapter(onClick = ::openDocumentViewer)

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
view.findViewById<RecyclerView>(R.id.home_recent_list).apply {
layoutManager = LinearLayoutManager(requireContext())
adapter = rowAdapter
}
view.findViewById<MaterialButton>(R.id.home_new_scan_button).apply {
contentDescription = context.getString(R.string.workspace_home_new_scan_cd)
setOnClickListener { launchScan() }
}
view.findViewById<MaterialButton>(R.id.home_open_library).apply {
contentDescription = context.getString(R.string.workspace_home_open_library_cd)
setOnClickListener { openLibrary() }
}
viewLifecycleOwner.lifecycleScope.launch {
viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
repository.observeDocuments().collect { documents -> render(view, documents) }
}
}
}

private fun render(view: View, documents: List<Document>) {
rowAdapter.submitList(documents.take(RECENT_DOCUMENT_LIMIT))
val isEmpty = documents.isEmpty()
view.findViewById<View>(R.id.home_empty_state).visibility =
if (isEmpty) View.VISIBLE else View.GONE
view.findViewById<RecyclerView>(R.id.home_recent_list).visibility =
if (isEmpty) View.GONE else View.VISIBLE
view.findViewById<View>(R.id.home_recent_header).visibility =
if (isEmpty) View.GONE else View.VISIBLE
}

private fun launchScan() {
val host = activity as? ScanHost ?: return
scanLauncher.launchScan(host)
}

private fun openLibrary() {
parentFragmentManager.beginTransaction()
.replace(R.id.app_fragment_container, LibraryFragment(repository, contentStore))
.addToBackStack(BACK_STACK_LIBRARY)
.commit()
}

private fun openDocumentViewer(document: Document) {
parentFragmentManager.beginTransaction()
.replace(
R.id.app_fragment_container,
DocumentViewerFragment.forDocument(repository, contentStore, document.id),
)
.addToBackStack(BACK_STACK_VIEWER)
.commit()
}

companion object {
private const val RECENT_DOCUMENT_LIMIT = 10
private const val BACK_STACK_LIBRARY = "workspace_library"
private const val BACK_STACK_VIEWER = "workspace_document_viewer"
}

}
