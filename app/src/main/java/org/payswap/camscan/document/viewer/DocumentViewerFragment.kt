package org.payswap.camscan.document.viewer

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.LibraryFragment
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.document.persistence.removePage
import org.payswap.camscan.document.persistence.updateTitle
import org.payswap.camscan.document.split.DocumentSplitEngine
import org.payswap.camscan.document.split.SplitExecutor
import org.payswap.camscan.document.viewer.PagePagerAdapter
import org.payswap.camscan.document.viewer.ViewerOps
import org.payswap.camscan.document.viewer.ViewerUiState
import org.payswap.camscan.export.ExportArtifact
import org.payswap.camscan.export.ExportEngine
import org.payswap.camscan.export.compress.BitmapResampler
import org.payswap.camscan.export.compress.CompressExportEngine
import org.payswap.camscan.export.compress.CompressedExportResult
import org.payswap.camscan.export.compress.CompressPdfOptions
import org.payswap.camscan.export.formatSizeBytes
import org.payswap.camscan.export.pdf.PageJpegEncoder
import org.payswap.camscan.export.share.ShareIntents
import org.payswap.camscan.imports.AndroidBoundsDecoder
import org.payswap.camscan.imports.AndroidUriReader
import org.payswap.camscan.imports.ImportEngine
import org.payswap.camscan.imports.ImportFailureMessages
import org.payswap.camscan.imports.ImportIntents
import org.payswap.camscan.imports.ImportResult
import org.payswap.camscan.imports.pdf.PdfImporter
import org.payswap.camscan.imports.pdf.PdfRendererOpener

/**

Real document viewer (CAMSCAN-PROD-006): paged processed images loaded from

the ContentStore, page indicator, page delete with confirm, reorder mode,

and editable document title. Replaces the PROD-005 stub behavior; the

PROD-005 stable ids document_detail_title / document_detail_delete are

preserved with their meanings.

CAMSCAN-PROD-008: document_merge_button routes into the library's merge

selection mode with THIS document preselected; document_append_import_button

imports picked images/PDF pages INTO this document (fragment-registered

launchers); document_split_button toggles page-selection mode (pager taps

select; document_split_confirm_button extracts via DocumentSplitEngine);

export_compress_button exports a compressed PDF with the honest

"original X → compressed Y" snackbar from CompressionReport.
*/
class DocumentViewerFragment(
private val repository: DocumentRepository,
private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_document_viewer) {

private val documentId: String
get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()
private var state: ViewerUiState? = null
private var poppedForMissingDocument = false

/** CAMSCAN-PROD-008: split page selection (ids of pages chosen for extraction). */
private val splitSelection = LinkedHashSet<String>()
private var splitModeActive = false
private var appendImportInFlight = false

private val idGenerator: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
private val splitEngine = DocumentSplitEngine()
private val splitExecutor by lazy { SplitExecutor(repository) }
private var importEngine: ImportEngine? = null

private val pageAdapter by lazy {
    PagePagerAdapter(
        contentStore = contentStore,
        pageTapListener = ::onPageTappedForSplit,
        selectedPageIds = ::splitSelectionSnapshot,
    )
}

/** CAMSCAN-PROD-007: export/share engine over the same store + repository. */
private val exportEngine by lazy {
    ExportEngine(
        contentStore = contentStore,
        encoder = PageJpegEncoder(),
        timeSource = TimeSource.SYSTEM,
        dispatcher = Dispatchers.IO,
    )
}

/** CAMSCAN-PROD-008: compressed PDF export (real PdfWriter under the seam). */
private val compressEngine by lazy {
    CompressExportEngine(
        contentStore = contentStore,
        encoder = PageJpegEncoder(),
        resampler = BitmapResampler(),
        timeSource = TimeSource.SYSTEM,
        dispatcher = Dispatchers.IO,
    )
}

/** CAMSCAN-PROD-008: fragment-registered append-import launchers. */
private val appendImportLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onAppendImportPicked(uri)
    }

private val appendImportFallbackLauncher =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onAppendImportPicked(uri)
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
// CAMSCAN-PROD-008 affordances.
view.findViewById<MaterialButton>(R.id.document_merge_button).apply {
contentDescription = context.getString(R.string.workspace_document_merge_cd)
setOnClickListener { openLibraryMergeSelection() }
}
view.findViewById<MaterialButton>(R.id.document_append_import_button).apply {
contentDescription = context.getString(R.string.workspace_document_append_import_cd)
setOnClickListener { launchAppendImportPicker() }
}
view.findViewById<MaterialButton>(R.id.document_split_button).apply {
contentDescription = context.getString(R.string.workspace_document_split_cd)
setOnClickListener { enterSplitMode() }
}
view.findViewById<MaterialButton>(R.id.document_split_confirm_button).apply {
contentDescription = context.getString(R.string.workspace_document_split_confirm_cd)
setOnClickListener { confirmSplit() }
}
view.findViewById<MaterialButton>(R.id.document_split_cancel_button).apply {
contentDescription = context.getString(R.string.workspace_document_split_cancel_cd)
setOnClickListener { exitSplitMode() }
}
view.findViewById<MaterialButton>(R.id.export_compress_button).apply {
contentDescription = context.getString(R.string.workspace_export_compress_cd)
setOnClickListener { compressCurrentDocument() }
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
// CAMSCAN-PROD-008: split + compress need pages; merge/append stay
// available (appending to an honest empty document is legal).
view.findViewById<View>(R.id.document_split_button).isEnabled = !next.isEmpty
view.findViewById<View>(R.id.export_compress_button).isEnabled = !next.isEmpty
// A page-set change (e.g. an extract) makes any stale selection invalid.
splitSelection.retainAll(next.pages.map { it.pageId }.toSet())
renderSplitBar()

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

// -------------------------------------------------- CAMSCAN-PROD-008

/** Merge THIS document with others: library selection mode, preselected. */
private fun openLibraryMergeSelection() {
parentFragmentManager.beginTransaction()
.replace(
R.id.app_fragment_container,
LibraryFragment.forMergeSelection(repository, contentStore, documentId),
)
.addToBackStack(BACK_STACK_LIBRARY_MERGE)
.commit()
}

private fun launchAppendImportPicker() {
if (appendImportInFlight || state == null) return
val spec = ImportIntents.openDocumentPick()
try {
appendImportLauncher.launch(spec.mimeTypes.toTypedArray())
} catch (expected: ActivityNotFoundException) {
appendImportFallbackLauncher.launch(ImportIntents.GET_CONTENT_FALLBACK_MIME)
}
}

/** A null uri is the user backing out of the picker — no failure snackbar. */
private fun onAppendImportPicked(uri: Uri?) {
if (uri == null) return
val host = view ?: return
setAppendImportInFlight(true)
showSnackbar(host, getString(R.string.workspace_importing))
viewLifecycleOwner.lifecycleScope.launch {
val result = ensureImportEngine().appendToDocument(documentId, listOf(uri.toString()))
setAppendImportInFlight(false)
when (result) {
null -> showSnackbar(
host,
getString(
R.string.workspace_import_failed,
getString(R.string.workspace_append_import_failed_reason_missing),
),
)

is ImportResult.Success -> showSnackbar(
host,
getString(R.string.workspace_append_import_success, result.document.title),
)

is ImportResult.Failure -> showSnackbar(
host,
getString(
R.string.workspace_import_failed,
ImportFailureMessages.describe(requireContext(), result.failure),
),
)
}
}
}

private fun ensureImportEngine(): ImportEngine {
val existing = importEngine
if (existing != null) return existing
val created = ImportEngine(
repository = repository,
contentStore = contentStore,
idGenerator = idGenerator,
timeSource = TimeSource.SYSTEM,
uriReader = AndroidUriReader(requireContext()),
boundsDecoder = AndroidBoundsDecoder(),
pdfImporter = PdfImporter(
repository = repository,
contentStore = contentStore,
idGenerator = idGenerator,
timeSource = TimeSource.SYSTEM,
opener = PdfRendererOpener(requireContext()),
),
)
importEngine = created
return created
}

private fun setAppendImportInFlight(inFlight: Boolean) {
appendImportInFlight = inFlight
view?.findViewById<MaterialButton>(R.id.document_append_import_button)?.isEnabled = !inFlight
}

// ------------------------------------------------------------- split

private fun splitSelectionSnapshot(): Set<String> = splitSelection.toSet()

private fun enterSplitMode() {
if (state == null || state?.isEmpty == true) return
splitModeActive = true
splitSelection.clear()
renderSplitBar()
rebindPagerForSelection()
}

private fun exitSplitMode() {
splitModeActive = false
splitSelection.clear()
renderSplitBar()
rebindPagerForSelection()
}

private fun onPageTappedForSplit(pageId: String) {
if (!splitModeActive) return
if (!splitSelection.remove(pageId)) {
splitSelection += pageId
}
renderSplitBar()
rebindPagerForSelection()
}

private fun renderSplitBar() {
val host = view ?: return
host.findViewById<View>(R.id.document_split_bar).visibility =
if (splitModeActive) View.VISIBLE else View.GONE
host.findViewById<MaterialButton>(R.id.document_split_confirm_button).isEnabled =
splitSelection.isNotEmpty()
host.findViewById<TextView>(R.id.document_split_selected_count).text =
getString(R.string.workspace_split_selected_count, splitSelection.size)
}

/** Selection state lives outside list items: force a full rebind. */
private fun rebindPagerForSelection() {
val pages = state?.pages ?: return
pageAdapter.submitList(null)
pageAdapter.submitList(pages)
}

private fun confirmSplit() {
val host = view ?: return
if (splitSelection.isEmpty()) return
viewLifecycleOwner.lifecycleScope.launch {
try {
val document = repository.getDocument(documentId)
?: throw IllegalStateException("document vanished")
val pages = repository.getPages(documentId)
if (pages.none { it.id in splitSelection }) {
throw IllegalStateException("selected pages vanished")
}
val plan = splitEngine.extract(
document = document,
pages = pages,
selectedPageIds = splitSelection.toList(),
idGenerator = idGenerator,
timeSource = TimeSource.SYSTEM,
)
splitExecutor.apply(plan)
exitSplitMode()
showSnackbar(host, getString(R.string.workspace_split_success, plan.extractedPages.size))
} catch (expected: Exception) {
exitSplitMode()
showSnackbar(
host,
getString(
R.string.workspace_split_failed,
getString(R.string.workspace_split_failed_reason_missing),
),
)
}
}
}

// ---------------------------------------------------------- compress

/** CAMSCAN-PROD-008: honest "original X → compressed Y" from the report. */
private fun compressCurrentDocument() {
val host = view ?: return
if (!hasPagesForExport(host, R.string.workspace_export_empty)) return
val button = host.findViewById<MaterialButton>(R.id.export_compress_button)
button.isEnabled = false
viewLifecycleOwner.lifecycleScope.launch {
val result: CompressedExportResult? =
compressEngine.compressToPdf(repository, documentId, CompressPdfOptions())
button.isEnabled = true
if (result == null) {
showSnackbar(
host,
getString(
R.string.workspace_compress_failed,
getString(R.string.workspace_compress_failed_reason_page),
),
)
} else {
showSnackbar(
host,
getString(
R.string.workspace_compress_success,
formatSizeBytes(result.report.originalBytes),
formatSizeBytes(result.report.compressedBytes),
),
)
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
private const val BACK_STACK_LIBRARY_MERGE = "workspace_library_merge"

fun forDocument(
repository: DocumentRepository,
contentStore: ContentStore,
documentId: String,
): DocumentViewerFragment = DocumentViewerFragment(repository, contentStore).apply {
arguments = Bundle().apply { putString(ARG_DOCUMENT_ID, documentId) }
}
}

}
