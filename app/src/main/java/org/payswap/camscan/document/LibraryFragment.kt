package org.payswap.camscan.document

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.repository.DocumentRepository

/**

Full document list surface (CAMSCAN-PROD-005). Up affordance and system

back both pop the back stack, returning to Home.
*/
class LibraryFragment(
private val repository: DocumentRepository,
) : Fragment(R.layout.fragment_library) {

private val rowAdapter = DocumentRowAdapter(onClick = ::openDocumentDetail)

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
view.findViewById<RecyclerView>(R.id.library_documents_list).apply {
layoutManager = LinearLayoutManager(requireContext())
adapter = rowAdapter
}
view.findViewById<Toolbar>(R.id.library_toolbar).apply {
setNavigationOnClickListener { parentFragmentManager.popBackStack() }
navigationContentDescription = context.getString(R.string.workspace_library_up_cd)
}
viewLifecycleOwner.lifecycleScope.launch {
viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
repository.observeDocuments().collect { documents -> render(view, documents) }
}
}
}

private fun render(view: View, documents: List<Document>) {
rowAdapter.submitList(documents)
val isEmpty = documents.isEmpty()
view.findViewById<View>(R.id.library_empty_state).visibility =
if (isEmpty) View.VISIBLE else View.GONE
view.findViewById<RecyclerView>(R.id.library_documents_list).visibility =
if (isEmpty) View.GONE else View.VISIBLE
}

private fun openDocumentDetail(document: Document) {
parentFragmentManager.beginTransaction()
.replace(
R.id.app_fragment_container,
DocumentDetailStubFragment.forDocument(repository, document.id),
)
.addToBackStack(BACK_STACK_DETAIL)
.commit()
}

companion object {
private const val BACK_STACK_DETAIL = "workspace_document_detail"
}

}
