package org.payswap.camscan.library

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.viewer.DocumentViewerFragment
import org.payswap.camscan.export.DocumentExportFragment
import org.payswap.camscan.export.ExportArtifact
import org.payswap.camscan.export.ExportEngine
import org.payswap.camscan.export.pdf.PageJpegEncoder
import org.payswap.camscan.export.share.ShareIntents
import org.payswap.camscan.tools.ui.BatchSelectionUi

// CAMSCAN-VERIFY-002 — the library batch-selection surface. Long-press
// enters selection mode (the row is preselected); taps toggle rows; the
// batch bar offers the repository-backed actions — Delete and
// Share-as-multiple (each selected document exported as a PDF through
// the PROD-007 engine, then one ACTION_SEND_MULTIPLE share sheet) —
// and shows the not-yet-existing actions (move to folder, tag)
// DISABLED with a "pending" label: no dead buttons, no fake actions.
// Tapping a row outside selection mode opens the existing viewer
// (consumed as-is); the per-row Export button routes into this
// order's export picker. Selection state lives in the pure
// [BatchSelectionUi] holder.
class BatchSelectionFragment(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_batch_selection) {

    private val selectionUi = BatchSelectionUi()
    private var latestDocuments: List<Document> = emptyList()

    private val exportEngine by lazy {
        ExportEngine(
            contentStore = contentStore,
            encoder = PageJpegEncoder(),
            timeSource = TimeSource.SYSTEM,
            dispatcher = Dispatchers.IO,
        )
    }

    private val rowAdapter by lazy {
        BatchDocumentAdapter(
            onOpenDocument = ::openDocumentViewer,
            onExportDocument = ::openExportPicker,
            selectionMode = selectionUi::isSelectionMode,
            selectedIds = selectionUi::selectedIds,
            onToggleSelection = ::toggleSelection,
            onEnterSelection = ::enterSelection,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<RecyclerView>(R.id.batch_documents_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = rowAdapter
        }
        view.findViewById<Toolbar>(R.id.batch_toolbar).apply {
            setNavigationOnClickListener { parentFragmentManager.popBackStack() }
            navigationContentDescription = context.getString(R.string.workspace_batch_up_cd)
        }
        view.findViewById<MaterialButton>(R.id.batch_share_button).apply {
            contentDescription = context.getString(R.string.workspace_batch_share_cd)
            setOnClickListener { shareSelectedDocuments() }
        }
        view.findViewById<MaterialButton>(R.id.batch_delete_button).apply {
            contentDescription = context.getString(R.string.workspace_batch_delete_cd)
            setOnClickListener { confirmDeleteSelected() }
        }
        view.findViewById<MaterialButton>(R.id.batch_cancel_button).apply {
            setOnClickListener { exitSelection() }
        }
        // Pending actions: visible, honestly disabled, labeled "pending".
        view.findViewById<MaterialButton>(R.id.batch_move_button).apply {
            isEnabled = false
            contentDescription = context.getString(R.string.workspace_batch_pending_cd)
        }
        view.findViewById<MaterialButton>(R.id.batch_tag_button).apply {
            isEnabled = false
            contentDescription = context.getString(R.string.workspace_batch_pending_cd)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeDocuments().collect { documents -> render(view, documents) }
            }
        }
    }

    private fun render(view: View, documents: List<Document>) {
        latestDocuments = documents
        rowAdapter.submitList(documents)
        val pruned = selectionUi.retainAll(documents.map { it.id }.toSet())
        if (pruned > 0) {
            rowAdapter.notifyDataSetChanged()
        }
        val isEmpty = documents.isEmpty()
        view.findViewById<View>(R.id.batch_empty_state).visibility =
            if (isEmpty) View.VISIBLE else View.GONE
        view.findViewById<RecyclerView>(R.id.batch_documents_list).visibility =
            if (isEmpty) View.GONE else View.VISIBLE
        renderSelectionMode(view)
    }

    /** Renders the batch bar, hint row and selection chrome. */
    private fun renderSelectionMode(view: View) {
        val selecting = selectionUi.isSelectionMode()
        view.findViewById<View>(R.id.batch_selection_bar).visibility =
            if (selecting) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.batch_hint_row).visibility =
            if (selecting) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.batch_selected_count).text =
            getString(R.string.workspace_batch_selected_count, selectionUi.selectedCount())
        view.findViewById<MaterialButton>(R.id.batch_share_button).isEnabled =
            selectionUi.canShare()
        view.findViewById<MaterialButton>(R.id.batch_delete_button).isEnabled =
            selectionUi.canDelete()
        view.findViewById<Toolbar>(R.id.batch_toolbar).title =
            if (selecting) {
                getString(R.string.workspace_batch_title_selecting)
            } else {
                getString(R.string.workspace_batch_title)
            }
        // Selection state lives outside list items: force a full rebind.
        rowAdapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------ modes

    private fun enterSelection(documentId: String) {
        selectionUi.enterSelection(documentId)
        view?.let { renderSelectionMode(it) }
    }

    private fun exitSelection() {
        selectionUi.exitSelection()
        view?.let { renderSelectionMode(it) }
    }

    private fun toggleSelection(documentId: String) {
        selectionUi.toggle(documentId)
        view?.let { renderSelectionMode(it) }
    }

    // ------------------------------------------------- wired batch ops

    /** Share every selected document as PDFs in one multi-file share sheet. */
    private fun shareSelectedDocuments() {
        val host = view ?: return
        if (!selectionUi.canShare()) return
        val selectedIds = selectionUi.selectedIds()
        val shareButton = host.findViewById<MaterialButton>(R.id.batch_share_button)
        shareButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val artifacts = ArrayList<ExportArtifact>(selectedIds.size)
            for (document in latestDocuments.filter { it.id in selectedIds }) {
                val artifact = exportEngine.exportPdf(repository, document.id)
                if (artifact == null) {
                    shareButton.isEnabled = true
                    showSnackbar(
                        host,
                        getString(
                            R.string.workspace_batch_share_failed,
                            getString(R.string.workspace_batch_share_failed_reason_page),
                        ),
                    )
                    return@launch
                }
                artifacts.add(artifact)
            }
            shareButton.isEnabled = true
            if (artifacts.isEmpty()) {
                // The selection vanished between render and click.
                showSnackbar(
                    host,
                    getString(
                        R.string.workspace_batch_share_failed,
                        getString(R.string.workspace_batch_share_failed_reason_page),
                    ),
                )
                return@launch
            }
            val chooser = ShareIntents.shareArtifacts(requireContext(), artifacts, contentStore)
            if (chooser == null) {
                showSnackbar(host, getString(R.string.workspace_share_unavailable))
            } else {
                startActivity(chooser)
            }
        }
    }

    /** Deletes every selected document after an explicit confirm dialog. */
    private fun confirmDeleteSelected() {
        val host = view ?: return
        if (!selectionUi.canDelete()) return
        val context = context ?: return
        val count = selectionUi.selectedCount()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.workspace_batch_delete_dialog_title)
            .setMessage(
                context.getString(R.string.workspace_batch_delete_dialog_message, count),
            )
            .setPositiveButton(R.string.workspace_dialog_confirm_delete) { _, _ ->
                deleteSelected(host)
            }
            .setNegativeButton(R.string.workspace_dialog_cancel, null)
            .show()
    }

    private fun deleteSelected(host: View) {
        val selectedIds = selectionUi.selectedIds().toList()
        viewLifecycleOwner.lifecycleScope.launch {
            var deleted = 0
            for (documentId in selectedIds) {
                repository.deleteDocument(documentId)
                deleted += 1
            }
            exitSelection()
            showSnackbar(
                host,
                getString(R.string.workspace_batch_delete_success, deleted),
            )
        }
    }

    // ----------------------------------------------------------- rows

    private fun openDocumentViewer(document: Document) {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.app_fragment_container,
                DocumentViewerFragment.forDocument(repository, contentStore, document.id),
            )
            .addToBackStack(BACK_STACK_VIEWER)
            .commit()
    }

    private fun openExportPicker(document: Document) {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.app_fragment_container,
                DocumentExportFragment.forDocument(repository, contentStore, document.id),
            )
            .addToBackStack(BACK_STACK_EXPORT)
            .commit()
    }

    private fun showSnackbar(host: View, message: String) {
        Snackbar.make(host, message, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val BACK_STACK_VIEWER = "batch_to_viewer"
        private const val BACK_STACK_EXPORT = "batch_to_export"
    }
}
