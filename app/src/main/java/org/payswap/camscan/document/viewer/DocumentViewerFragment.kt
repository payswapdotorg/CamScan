package org.payswap.camscan.document.viewer

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.removePage
import org.payswap.camscan.document.persistence.updateTitle
import org.payswap.camscan.document.viewer.PagePagerAdapter
import org.payswap.camscan.document.viewer.ViewerOps
import org.payswap.camscan.document.viewer.ViewerUiState
import org.payswap.camscan.export.ExportArtifact
import org.payswap.camscan.export.ExportEngine
import org.payswap.camscan.export.formatSizeBytes
import org.payswap.camscan.export.pdf.PageJpegEncoder
import org.payswap.camscan.export.share.ShareIntents

/**

Real document viewer (CAMSCAN-PROD-006): paged processed images loaded from

the ContentStore, page indicator, page delete with confirm, reorder mode,

and editable document title. Replaces the PROD-005 stub behavior; the

PROD-005 stable ids document_detail_title / document_detail_delete are

preserved with their meanings.
*/
class DocumentViewerFragment(
private val repository: DocumentRepository,
private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_document_viewer) {

private val documentId: String
get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()
private var state: ViewerUiState? = null
private var poppedForMissingDocument = false

private val pageAdapter by lazy { PagePagerAdapter(contentStore) }

/** CAMSCAN-PROD-007: export/share engine over the same store + repository. */
private val exportEngine by lazy {
    ExportEngine(
        contentStore = contentStore,
        encoder = PageJpegEncoder(),
        timeSource = TimeSource.SYSTEM,
        dispatcher = Dispatchers.IO,
    )
}

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
val toolbar = view.findViewById<Toolbar>(R.id.viewer_toolbar)
toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
toolbar.navigationContentDescription =
context?.getString(R.string.workspace_viewer_back_cd)

val titleView = view.findViewById<TextView>(R.id.document_detail_title)
val indicatorView = view.findViewById<TextView>(R.id.document_page_indicator)
val pager = view.findViewById<RecyclerView>(R.id.document_pages_pager).apply {
layoutManager = LinearLayoutManager(
requireContext(), LinearLayoutManager.HORIZONTAL, false,
)
adapter = pageAdapter
}
PagerSnapHelper().attachToRecyclerView(pager)
pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
if (newState == RecyclerView.SCROLL_STATE_IDLE) {
val snapped = PagerSnapHelper()
.findSnapView(recyclerView.layoutManager as LinearLayoutManager)
?.let { recyclerView.getChildAdapterPosition(it) }
?: RecyclerView.NO_POSITION
updatePosition(snapped)
}
}
})

view.findViewById<TextView>(R.id.document_edit_title).apply {
contentDescription = context.getString(R.string.workspace_edit_title_cd)
setOnClickListener { showRenameDialog() }
}
view.findViewById<MaterialButton>(R.id.document_reorder_pages).apply {
contentDescription = context.getString(R.string.workspace_reorder_pages_cd)
setOnClickListener { openReorderMode() }
}
view.findViewById<MaterialButton>(R.id.document_page_delete).apply {
contentDescription = context.getString(R.string.workspace_page_delete_cd)
setOnClickListener { showDeletePageDialog() }
}
view.findViewById<MaterialButton>(R.id.document_detail_delete).apply {
contentDescription = context.getString(R.string.workspace_document_detail_delete_cd)
setOnClickListener { showDeleteDocumentDialog() }
}
view.findViewById<MaterialButton>(R.id.document_export_pdf_button).apply {
contentDescription = context.getString(R.string.workspace_export_pdf_cd)
setOnClickListener { exportPdfForCurrentDocument() }
}
view.findViewById<MaterialButton>(R.id.document_export_jpg_button).apply {
contentDescription = context.getString(R.string.workspace_export_jpg_cd)
setOnClickListener { exportJpgForCurrentPage() }
}
view.findViewById<MaterialButton>(R.id.document_share_button).apply {
contentDescription = context.getString(R.string.workspace_share_cd)
setOnClickListener { shareCurrentDocument() }
}

viewLifecycleOwner.lifecycleScope.launch {
viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
repository.observeDocuments()
.map { documents -> documents.firstOrNull { it.id == documentId } }
.distinctUntilChanged()
.collect { document ->
if (document == null) {
popOnceForMissingDocument()
} else {
render(view, document.title, ViewerOps.fromDocument(document, repository.getPages(document.id)))
}
}
}
}
}

private fun render(view: View, title: String, next: ViewerUiState) {
state = next
view.findViewById<TextView>(R.id.document_detail_title).text = title
view.findViewById<Toolbar>(R.id.viewer_toolbar).title = title

val indicator = view.findViewById<TextView>(R.id.document_page_indicator)
indicator.text = next.indicatorText

val emptyState = view.findViewById<View>(R.id.document_empty_state)
val pager = view.findViewById<RecyclerView>(R.id.document_pages_pager)
emptyState.visibility = if (next.isEmpty) View.VISIBLE else View.GONE
pager.visibility = if (next.isEmpty) View.GONE else View.VISIBLE
view.findViewById<View>(R.id.document_page_delete).isEnabled = !next.isEmpty
view.findViewById<View>(R.id.document_reorder_pages).isEnabled = !next.isEmpty
view.findViewById<View>(R.id.document_export_pdf_button).isEnabled = !next.isEmpty
view.findViewById<View>(R.id.document_export_jpg_button).isEnabled = !next.isEmpty
view.findViewById<View>(R.id.document_share_button).isEnabled = !next.isEmpty

pageAdapter.submitList(next.pages)
}

private fun updatePosition(position: Int) {
val view = view ?: return
val current = state ?: return
val next = current.withPosition(position)
if (next.currentPosition != current.currentPosition) {
state = next
view.findViewById<TextView>(R.id.document_page_indicator).text = next.indicatorText
}
}

private fun showRenameDialog() {
val context = context ?: return
val input = EditText(context)
input.hint = context.getString(R.string.workspace_edit_title_hint)
state?.title?.let { input.setText(it) }
MaterialAlertDialogBuilder(context)
.setTitle(R.string.workspace_edit_title_dialog_title)
.setView(input)
.setPositiveButton(R.string.workspace_dialog_save) { _, _ ->
val newTitle = input.text.toString().trim()
if (newTitle.isNotEmpty()) {
viewLifecycleOwner.lifecycleScope.launch {
repository.updateTitle(documentId, newTitle, TimeSource.SYSTEM)
}
}
}
.setNegativeButton(R.string.workspace_dialog_cancel, null)
.show()
}

private fun showDeletePageDialog() {
val context = context ?: return
val page = state?.currentPage ?: return
MaterialAlertDialogBuilder(context)
.setTitle(R.string.workspace_delete_page_dialog_title)
.setMessage(
context.getString(
R.string.workspace_delete_page_dialog_message,
(state?.currentPosition ?: 0) + 1,
),
)
.setPositiveButton(R.string.workspace_dialog_confirm_delete) { _, _ ->
viewLifecycleOwner.lifecycleScope.launch {
val updated = repository.removePage(documentId, page.pageId, TimeSource.SYSTEM)
if (updated != null && updated.pageIds.isEmpty()) {
Toast.makeText(
context,
R.string.workspace_document_now_empty,
Toast.LENGTH_SHORT,
).show()
}
}
}
.setNegativeButton(R.string.workspace_dialog_cancel, null)
.show()
}

private fun showDeleteDocumentDialog() {
val context = context ?: return
MaterialAlertDialogBuilder(context)
.setTitle(R.string.workspace_delete_document_dialog_title)
.setMessage(R.string.workspace_delete_document_dialog_message)
.setPositiveButton(R.string.workspace_dialog_confirm_delete) { _, _ ->
viewLifecycleOwner.lifecycleScope.launch {
repository.deleteDocument(documentId)
parentFragmentManager.popBackStack()
}
}
.setNegativeButton(R.string.workspace_dialog_cancel, null)
.show()
}

private fun openReorderMode() {
parentFragmentManager.beginTransaction()
.replace(
R.id.app_fragment_container,
DocumentReorderFragment.forDocument(repository, documentId),
)
.addToBackStack(BACK_STACK_REORDER)
.commit()
}

/** CAMSCAN-PROD-007: export the whole document as one PDF artifact. */
private fun exportPdfForCurrentDocument() {
val host = view ?: return
if (!hasPagesForExport(host, R.string.workspace_export_empty)) return
viewLifecycleOwner.lifecycleScope.launch {
val artifact = exportEngine.exportPdf(repository, documentId)
showExportResult(host, artifact)
}
}

/** CAMSCAN-PROD-007: export the page the pager is showing as a JPG artifact. */
private fun exportJpgForCurrentPage() {
val host = view ?: return
if (!hasPagesForExport(host, R.string.workspace_export_empty)) return
val pageIndex = state?.currentPosition ?: 0
viewLifecycleOwner.lifecycleScope.launch {
val artifact = exportEngine.exportJpg(repository, documentId, pageIndex)
showExportResult(host, artifact)
}
}

/**
CAMSCAN-PROD-007: share the document — export a fresh PDF artifact, resolve
it through ShareIntents, then hand the chooser to the platform. Every
failure state gets an honest snackbar.
*/
private fun shareCurrentDocument() {
val host = view ?: return
if (!hasPagesForExport(host, R.string.workspace_share_empty)) return
viewLifecycleOwner.lifecycleScope.launch {
val artifact = exportEngine.exportPdf(repository, documentId)
if (artifact == null) {
showSnackbar(
host,
getString(
R.string.workspace_share_failed,
getString(R.string.workspace_export_failed_reason_page),
),
)
return@launch
}
val chooser = ShareIntents.shareArtifact(requireContext(), artifact, contentStore)
if (chooser == null) {
showSnackbar(host, getString(R.string.workspace_share_unavailable))
} else {
startActivity(chooser)
}
}
}

/** True when a document with pages is loaded; otherwise shows the honest
empty-state snackbar and returns false. */
private fun hasPagesForExport(host: View, emptyMessageRes: Int): Boolean {
val current = state
if (current == null || current.isEmpty) {
showSnackbar(host, getString(emptyMessageRes))
return false
}
return true
}

private fun showExportResult(host: View, artifact: ExportArtifact?) {
if (artifact == null) {
showSnackbar(
host,
getString(
R.string.workspace_export_failed,
getString(R.string.workspace_export_failed_reason_page),
),
)
} else {
showSnackbar(
host,
getString(
R.string.workspace_export_success,
artifact.displayName,
formatSizeBytes(artifact.sizeBytes.toLong()),
),
)
}
}

private fun showSnackbar(host: View, message: String) {
Snackbar.make(host, message, Snackbar.LENGTH_LONG).show()
}

private fun popOnceForMissingDocument() {
if (poppedForMissingDocument) return
poppedForMissingDocument = true
parentFragmentManager.popBackStack()
}

companion object {
private const val ARG_DOCUMENT_ID = "arg_document_id"
private const val BACK_STACK_REORDER = "workspace_reorder"

fun forDocument(
repository: DocumentRepository,
contentStore: ContentStore,
documentId: String,
): DocumentViewerFragment = DocumentViewerFragment(repository, contentStore).apply {
arguments = Bundle().apply { putString(ARG_DOCUMENT_ID, documentId) }
}
}

}
