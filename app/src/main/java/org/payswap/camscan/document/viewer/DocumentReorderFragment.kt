package org.payswap.camscan.document

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.movePage
import org.payswap.camscan.document.persistence.reorderPages
import org.payswap.camscan.document.viewer.ViewerOps
import org.payswap.camscan.document.viewer.ViewerPageItem

/**

Order-editing mode (CAMSCAN-PROD-006): deterministic up/down moves per

page — no drag-and-drop dependency. The working list stays local until

Done commits one atomic reorderPages write; Cancel pops without writing.
*/
class DocumentReorderFragment(
private val repository: DocumentRepository,
) : Fragment(R.layout.fragment_document_reorder) {

private val documentId: String
get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()
private var workingPages: List<ViewerPageItem> = emptyList()
private var listAdapter: ReorderAdapter? = null

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
view.findViewById<Toolbar>(R.id.reorder_toolbar).apply {
setNavigationOnClickListener { parentFragmentManager.popBackStack() }
navigationContentDescription =
context.getString(R.string.workspace_reorder_cancel_cd)
}
val list = view.findViewById<RecyclerView>(R.id.document_reorder_list).apply {
layoutManager = LinearLayoutManager(requireContext())
}
val adapter = ReorderAdapter(
onMove = { pageId, offset ->
ViewerOps.moved(workingPages, pageId, offset)?.let { moved ->
workingPages = moved
adapter.submitList(workingPages.toList())
}
},
)
listAdapter = adapter
list.adapter = adapter

view.findViewById<MaterialButton>(R.id.document_reorder_done).apply {
contentDescription = context.getString(R.string.workspace_reorder_done_cd)
setOnClickListener { commitReorder() }
}
view.findViewById<MaterialButton>(R.id.document_reorder_cancel).apply {
contentDescription = context.getString(R.string.workspace_reorder_cancel_cd)
setOnClickListener { parentFragmentManager.popBackStack() }
}

viewLifecycleOwner.lifecycleScope.launch {
viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
repository.observeDocument(documentId).collect { document ->
if (document == null) {
parentFragmentManager.popBackStack()
} else if (workingPages.isEmpty()) {
workingPages = ViewerOps.fromDocument(document).pages
adapter.submitList(workingPages.toList())
}
}
}
}
}

private fun commitReorder() {
val orderedIds = workingPages.map { it.pageId }
viewLifecycleOwner.lifecycleScope.launch {
repository.reorderPages(documentId, orderedIds, TimeSource.System)
parentFragmentManager.popBackStack()
}
}

/** Row: page label + up/down; boundary rows disable their move button. */
private inner class ReorderAdapter(
private val onMove: (pageId: String, offset: Int) -> Unit,
) : RecyclerView.Adapter<ReorderAdapter.RowViewHolder>() {

private val items = ArrayList<ViewerPageItem>()

fun submitList(newItems: List<ViewerPageItem>) {
items.clear()
items.addAll(newItems)
notifyDataSetChanged()
}

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
val row = LayoutInflater.from(parent.context)
.inflate(R.layout.item_reorder_page_row, parent, false)
return RowViewHolder(row)
}

override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
holder.bind(items[position], position)
}

override fun getItemCount(): Int = items.size

inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
private val label: TextView = itemView.findViewById(R.id.reorder_row_label)
private val up: MaterialButton = itemView.findViewById(R.id.document_row_move_up)
private val down: MaterialButton = itemView.findViewById(R.id.document_row_move_down)

fun bind(item: ViewerPageItem, position: Int) {
val context = itemView.context
label.text = context.getString(
R.string.workspace_reorder_row_label,
position + 1,
items.size,
)
up.isEnabled = position > 0
down.isEnabled = position < items.size - 1
up.setOnClickListener { onMove(item.pageId, -1) }
down.setOnClickListener { onMove(item.pageId, +1) }
}
}

 }

companion object {
private const val ARG_DOCUMENT_ID = "arg_document_id"

fun forDocument(repository: DocumentRepository, documentId: String): DocumentReorderFragment =
DocumentReorderFragment(repository).apply {
arguments = Bundle().apply { putString(ARG_DOCUMENT_ID, documentId) }
}
}

}
