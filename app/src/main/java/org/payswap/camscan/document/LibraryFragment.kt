package org.payswap.camscan.document

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.util.UUID
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.merge.DocumentMergeEngine
import org.payswap.camscan.document.merge.MergeExecutor
import org.payswap.camscan.document.merge.MergeMode
import org.payswap.camscan.document.persistence.IdGenerator
import org.payswap.camscan.document.viewer.DocumentViewerFragment
import org.payswap.camscan.imports.AndroidBoundsDecoder
import org.payswap.camscan.imports.AndroidUriReader
import org.payswap.camscan.imports.ImportEngine
import org.payswap.camscan.imports.ImportFailureMessages
import org.payswap.camscan.imports.ImportIntents
import org.payswap.camscan.imports.ImportResult
import org.payswap.camscan.imports.pdf.PdfImporter
import org.payswap.camscan.imports.pdf.PdfRendererOpener

/**

Full document list surface. PROD-006: rows open the real viewer.

CAMSCAN-PROD-008: import (system picker through ImportIntents — the image

and PDF entry point) + merge selection mode (rows become checkboxes;

library_merge_confirm_button stays disabled below two selections). Every

import/merge failure surfaces as an honest taxonomy-driven snackbar.
*/
class LibraryFragment(
private val repository: DocumentRepository,
private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_library) {

private val rowAdapter = DocumentRowAdapter(
onClick = ::openDocumentViewer,
selectionMode = ::isMergeSelection,
selectedIds = ::mergeSelectionSnapshot,
onToggleSelection = ::toggleMergeSelection,
)

/** Fragment-registered launchers keep this wiring inside PROD-008's files. */
private val importLauncher =
registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
onImportPicked(uri)
}

private val importFallbackLauncher =
registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
onImportPicked(uri)
}

private val mergeEngine = DocumentMergeEngine()
private val idGenerator: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
private var importEngine: ImportEngine? = null
private var latestDocuments: List<Document> = emptyList()
private val mergeSelection = LinkedHashSet<String>()
private var mergeSelectionActive = false
private var importInFlight = false

override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
view.findViewById<RecyclerView>(R.id.library_documents_list).apply {
layoutManager = LinearLayoutManager(requireContext())
adapter = rowAdapter
}
view.findViewById<Toolbar>(R.id.library_toolbar).apply {
setNavigationOnClickListener { parentFragmentManager.popBackStack() }
navigationContentDescription = context.getString(R.string.workspace_library_up_cd)
}
view.findViewById<MaterialButton>(R.id.library_import_button).apply {
contentDescription = context.getString(R.string.workspace_library_import_cd)
setOnClickListener { launchImportPicker() }
}
view.findViewById<MaterialButton>(R.id.library_merge_button).apply {
contentDescription = context.getString(R.string.workspace_library_merge_cd)
setOnClickListener { enterMergeSelection() }
}
view.findViewById<MaterialButton>(R.id.library_merge_confirm_button).apply {
contentDescription = context.getString(R.string.workspace_library_merge_confirm_cd)
setOnClickListener { confirmMerge() }
}
view.findViewById<MaterialButton>(R.id.library_merge_cancel_button).apply {
contentDescription = context.getString(R.string.workspace_library_merge_cancel_cd)
setOnClickListener { exitMergeSelection() }
}

// The viewer's document_merge_button routes here with the document
// preselected for merging.
arguments?.getString(ARG_PRESELECTED_MERGE_DOCUMENT_ID)?.let { preselected ->
if (preselected.isNotEmpty()) {
mergeSelectionActive = true
mergeSelection += preselected
}
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
val isEmpty = documents.isEmpty()
view.findViewById<View>(R.id.library_empty_state).visibility =
if (isEmpty) View.VISIBLE else View.GONE
view.findViewById<RecyclerView>(R.id.library_documents_list).visibility =
if (isEmpty) View.GONE else View.VISIBLE
view.findViewById<View>(R.id.library_merge_button).isEnabled = !isEmpty
renderMergeBar(view)
}

private fun renderMergeBar(view: View) {
val bar = view.findViewById<View>(R.id.library_merge_bar)
val entryRow = view.findViewById<View>(R.id.library_action_row)
bar.visibility = if (mergeSelectionActive) View.VISIBLE else View.GONE
entryRow.visibility = if (mergeSelectionActive) View.GONE else View.VISIBLE
view.findViewById<MaterialButton>(R.id.library_merge_confirm_button).isEnabled =
mergeSelection.size >= DocumentMergeEngine.MIN_SOURCES
// Selection state lives outside the list items; force a full rebind so
// checkboxes and confirm enablement track it.
rowAdapter.notifyDataSetChanged()
}

// ------------------------------------------------------------- import

private fun launchImportPicker() {
if (importInFlight) return
val spec = ImportIntents.openDocumentPick()
try {
importLauncher.launch(spec.mimeTypes.toTypedArray())
} catch (expected: ActivityNotFoundException) {
// Documented fallback: ACTION_GET_CONTENT with the wildcard type.
importFallbackLauncher.launch(ImportIntents.GET_CONTENT_FALLBACK_MIME)
}
}

/** A null uri is the user backing out of the picker — no failure snackbar. */
private fun onImportPicked(uri: Uri?) {
if (uri == null) return
val host = view ?: return
val engine = ensureImportEngine()
setImportInFlight(true)
showSnackbar(host, getString(R.string.workspace_importing))
viewLifecycleOwner.lifecycleScope.launch {
val result = engine.importAsNewDocument(listOf(uri.toString()))
setImportInFlight(false)
when (result) {
is ImportResult.Success ->
showSnackbar(host, getString(R.string.workspace_import_success, result.document.title))

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

private fun setImportInFlight(inFlight: Boolean) {
importInFlight = inFlight
view?.findViewById<MaterialButton>(R.id.library_import_button)?.isEnabled = !inFlight
}

// --------------------------------------------------------------- merge

private fun isMergeSelection(): Boolean = mergeSelectionActive

private fun mergeSelectionSnapshot(): Set<String> = mergeSelection.toSet()

private fun toggleMergeSelection(documentId: String) {
if (!mergeSelection.remove(documentId)) {
mergeSelection += documentId
}
view?.let { renderMergeBar(it) }
}

private fun enterMergeSelection() {
mergeSelectionActive = true
view?.let { renderMergeBar(it) }
}

private fun exitMergeSelection() {
mergeSelectionActive = false
mergeSelection.clear()
view?.let { renderMergeBar(it) }
}

private fun confirmMerge() {
val host = view ?: return
if (mergeSelection.size < DocumentMergeEngine.MIN_SOURCES) return
val selectedIds = mergeSelection.toList()
viewLifecycleOwner.lifecycleScope.launch {
try {
// Source-document order = the visible list order (updatedAtMillis
// descending), the order law DocumentMergeEngine documents.
val documents = latestDocuments
.filter { it.id in mergeSelection }
.mapNotNull { repository.getDocument(it.id) }
if (documents.size < DocumentMergeEngine.MIN_SOURCES) {
throw IllegalStateException("selected documents vanished")
}
val pagesByDocument = documents.associate { document ->
document.id to repository.getPages(document.id)
}
val plan = mergeEngine.merge(
documents = documents,
pagesByDocument = pagesByDocument,
mode = MergeMode.CONSUME,
idGenerator = idGenerator,
timeSource = TimeSource.SYSTEM,
)
MergeExecutor(repository, contentStore).apply(plan)
exitMergeSelection()
showSnackbar(host, getString(R.string.workspace_merge_success, plan.mergedDocument.title))
} catch (expected: Exception) {
exitMergeSelection()
showSnackbar(
host,
getString(
R.string.workspace_merge_failed,
getString(R.string.workspace_merge_failed_reason_missing),
),
)
}
}
}

// -------------------------------------------------------------- shared

private fun openDocumentViewer(document: Document) {
parentFragmentManager.beginTransaction()
.replace(
R.id.app_fragment_container,
DocumentViewerFragment.forDocument(repository, contentStore, document.id),
)
.addToBackStack(BACK_STACK_VIEWER)
.commit()
}

private fun showSnackbar(host: View, message: String) {
Snackbar.make(host, message, Snackbar.LENGTH_LONG).show()
}

companion object {
private const val BACK_STACK_VIEWER = "workspace_document_viewer"
private const val ARG_PRESELECTED_MERGE_DOCUMENT_ID = "arg_preselected_merge_document_id"

/** Opens the library in merge selection mode with [preselectedDocumentId] checked. */
fun forMergeSelection(
repository: DocumentRepository,
contentStore: ContentStore,
preselectedDocumentId: String,
): LibraryFragment = LibraryFragment(repository, contentStore).apply {
arguments = Bundle().apply {
putString(ARG_PRESELECTED_MERGE_DOCUMENT_ID, preselectedDocumentId)
}
}
}

}
