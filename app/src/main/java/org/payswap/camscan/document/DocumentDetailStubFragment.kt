package org.payswap.camscan.document

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository

/**

Stub detail surface (CAMSCAN-PROD-005).

The real document viewer arrives in PROD-006; this stub carries the entry

point, honest placeholder copy, and delete-with-undo (offline-first,

non-destructive spirit; deletion stands if the undo is dismissed).

Per-instance state (document id) travels via [arguments]; the shell's

FragmentFactory re-attaches the repository on recreation.
*/
class DocumentDetailStubFragment(
private val repository: DocumentRepository,
) : Fragment(R.layout.fragment_document_stub) {

private val documentId: String
get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
val titleView = view.findViewById<TextView>(R.id.document_detail_title)
val pageCountView = view.findViewById<TextView>(R.id.document_detail_page_count)
val deleteButton = view.findViewById<MaterialButton>(R.id.document_detail_delete)

deleteButton.contentDescription =
getString(R.string.workspace_document_detail_delete_cd)
deleteButton.setOnClickListener { deleteWithUndo() }

viewLifecycleOwner.lifecycleScope.launch {
viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
repository.observeDocument(documentId).collect { document ->
if (document == null) {
titleView.text = getString(R.string.workspace_document_detail_missing)
pageCountView.text = ""
deleteButton.isEnabled = false
} else {
titleView.text = document.title
pageCountView.text = resources.getQuantityString(
R.plurals.workspace_page_count,
document.pages.size,
document.pages.size,
)
deleteButton.isEnabled = true
}
}
}
}
}

private fun deleteWithUndo() {
val id = documentId
viewLifecycleOwner.lifecycleScope.launch {
val snapshot = repository.getDocument(id) ?: return@launch
if (!repository.deleteDocument(id)) return@launch
Snackbar.make(
requireView(),
R.string.workspace_document_deleted_snackbar,
Snackbar.LENGTH_LONG,
)
.setAction(R.string.workspace_document_deleted_undo) {
viewLifecycleOwner.lifecycleScope.launch {
repository.upsertDocument(snapshot)
}
}
.show()
}
}

companion object {
private const val ARG_DOCUMENT_ID = "arg_document_id"

/** Builds the stub for [documentId]; per-instance state travels via [arguments]. */
fun forDocument(
repository: DocumentRepository,
documentId: String,
): DocumentDetailStubFragment = DocumentDetailStubFragment(repository).apply {
arguments = bundleOf(ARG_DOCUMENT_ID to documentId)
}
}

}
