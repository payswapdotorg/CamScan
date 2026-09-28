package org.payswap.camscan.document

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.payswap.camscan.R
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.time.TimeSource

/**

Shared row renderer for Home recents and the Library list.

Fix pass for the FROZEN model: [Document] carries no pages, so page counts

arrive via the injected [pageCountOf] lookup (Home maintains a per-id map

refreshed from repository.getPages before each submitList; the default

renders a count-less meta line for surfaces that supply no counts). Time

enters only via the [TimeSource] seam — TimeSource.SYSTEM, never

System.currentTimeMillis() directly.
*/
class DocumentRowAdapter(
private val onClick: (Document) -> Unit,
private val nowMillis: () -> Long = { TimeSource.SYSTEM.nowMillis() },
private val pageCountOf: (documentId: String) -> Int = { -1 },
) : ListAdapter<Document, DocumentRowAdapter.DocumentViewHolder>(DIFF_CALLBACK) {

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
val view = LayoutInflater.from(parent.context)
.inflate(R.layout.item_document_row, parent, false)
return DocumentViewHolder(view)
}

override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
holder.bind(getItem(position))
}

inner class DocumentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

private val titleView: TextView = itemView.findViewById(R.id.document_row_title)
private val metaView: TextView = itemView.findViewById(R.id.document_row_meta)

init {
itemView.setOnClickListener {
val position = bindingAdapterPosition
if (position != RecyclerView.NO_POSITION) {
onClick(getItem(position))
}
}
}

fun bind(document: Document) {
val context = itemView.context
titleView.text = document.title
metaView.text = metaLabel(context, document)
itemView.contentDescription =
context.getString(R.string.workspace_document_row_cd, document.title)
}

private fun metaLabel(context: Context, document: Document): String {
val relative = DateUtils.getRelativeTimeSpanString(
document.updatedAtMillis,
nowMillis(),
DateUtils.MINUTE_IN_MILLIS,
).toString()
val pageCount = pageCountOf(document.id)
if (pageCount < 0) return relative
val pages = context.resources.getQuantityString(
R.plurals.workspace_page_count,
pageCount,
pageCount,
)
return context.getString(R.string.workspace_document_row_meta_format, pages, relative)
}

 }

companion object {
private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Document>() {
override fun areItemsTheSame(oldItem: Document, newItem: Document): Boolean =
oldItem.id == newItem.id

override fun areContentsTheSame(oldItem: Document, newItem: Document): Boolean =
oldItem == newItem
}
}

}
