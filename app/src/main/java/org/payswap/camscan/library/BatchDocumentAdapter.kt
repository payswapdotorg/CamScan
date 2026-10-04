package org.payswap.camscan.library

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.payswap.camscan.R
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.time.TimeSource

// CAMSCAN-VERIFY-002 — the batch surface's own row renderer. It mirrors
// the shared DocumentRowAdapter's row contract (title, meta line,
// selection checkbox driven from OUTSIDE the item) and adds the
// per-row Export affordance that routes into the export picker; the
// shared adapter is not modified (document/ is outside this order's
// ADD/MODIFY territory). Selection state is read live at bind time —
// the host forces a rebind on every selection change (the differ
// cannot see it). The export button hides while selecting.

/** Row renderer of [BatchSelectionFragment]'s document list. */
class BatchDocumentAdapter(
    private val onOpenDocument: (Document) -> Unit,
    private val onExportDocument: (Document) -> Unit,
    private val nowMillis: () -> Long = { TimeSource.SYSTEM.nowMillis() },
    private val selectionMode: () -> Boolean = { false },
    private val selectedIds: () -> Set<String> = { emptySet() },
    private val onToggleSelection: (documentId: String) -> Unit = {},
    private val onEnterSelection: (documentId: String) -> Unit = {},
) : ListAdapter<Document, BatchDocumentAdapter.BatchViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BatchViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_batch_document_row, parent, false)
        return BatchViewHolder(view)
    }

    override fun onBindViewHolder(holder: BatchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BatchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val titleView: TextView = itemView.findViewById(R.id.batch_row_title)
        private val metaView: TextView = itemView.findViewById(R.id.batch_row_meta)
        private val selectBox: CheckBox = itemView.findViewById(R.id.batch_row_select)
        private val exportButton: TextView = itemView.findViewById(R.id.batch_row_export)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val document = getItem(position)
                    if (selectionMode()) {
                        onToggleSelection(document.id)
                    } else {
                        onOpenDocument(document)
                    }
                }
            }
            // Long-press enters selection mode and pre-selects this row.
            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onEnterSelection(getItem(position).id)
                    true
                } else {
                    false
                }
            }
            exportButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && !selectionMode()) {
                    onExportDocument(getItem(position))
                }
            }
        }

        fun bind(document: Document) {
            val context = itemView.context
            titleView.text = document.title
            metaView.text = metaLabel(context, document)
            val selecting = selectionMode()
            val selected = selecting && document.id in selectedIds()
            selectBox.visibility = if (selecting) View.VISIBLE else View.GONE
            selectBox.isChecked = selected
            exportButton.visibility = if (selecting) View.GONE else View.VISIBLE
            itemView.contentDescription = if (selecting) {
                context.getString(R.string.workspace_batch_row_select_cd, document.title)
            } else {
                context.getString(R.string.workspace_batch_row_cd, document.title)
            }
            exportButton.contentDescription =
                context.getString(R.string.workspace_batch_row_export_cd, document.title)
        }

        private fun metaLabel(context: Context, document: Document): String {
            val relative = DateUtils.getRelativeTimeSpanString(
                document.updatedAtMillis,
                nowMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            return context.getString(
                R.string.workspace_document_row_meta_format,
                document.sourceType.name,
                relative,
            )
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
