package org.payswap.camscan.export

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.export.conversion.MlKitPageTextSource
import org.payswap.camscan.export.conversion.OfficeExportEngine
import org.payswap.camscan.export.longimage.AndroidStripRasterizer
import org.payswap.camscan.export.longimage.LongImageExportEngine
import org.payswap.camscan.export.print.PrintLauncher
import org.payswap.camscan.export.print.PrintPageInput
import org.payswap.camscan.export.print.PrintRenderResult
import org.payswap.camscan.export.print.PrintSheetRenderer
import org.payswap.camscan.export.share.ShareIntents
import org.payswap.camscan.imports.AndroidBoundsDecoder
import org.payswap.camscan.tools.ui.ExportFormatCatalog
import org.payswap.camscan.tools.ui.ExportPickerUi
import org.payswap.camscan.tools.ui.PrintPickerUi

// CAMSCAN-VERIFY-002 — the per-document export surface: the Office
// conversion entries (PPTX flagship + the text-level DOCX/XLSX pair
// whose layout-unpreserved declaration is VISIBLE in the picker, not
// a post-hoc toast), the long-image PNG export, and the wireless print
// entry. The FLOW wiring matches the viewer's PROD-007 export pattern:
// engine -> ExportArtifact in the ContentStore export location ->
// snackbar + share chooser. State lives in the pure [ExportPickerUi]
// holder (tools/ui convention); per-instance state travels via the
// arguments bundle (document id). One run at a time; while a run is
// in flight the format buttons disable and an honest status line says
// what is happening — no fake progress bars.
class DocumentExportFragment(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_document_export) {

    private val documentId: String
        get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()

    /** Latest observed page count of the loaded document (empty-state gate). */
    private var state: ViewerLikeState? = null

    private val pickerUi = ExportPickerUi()
    private val printUi = PrintPickerUi()

    private var documentTitle: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<Toolbar>(R.id.export_toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.navigationContentDescription =
            context?.getString(R.string.workspace_export_up_cd)

        view.findViewById<MaterialButton>(R.id.export_pptx_button).apply {
            contentDescription = context.getString(R.string.workspace_export_pptx_cd)
            setOnClickListener { runOfficeExport(ExportFormatCatalog.PPTX.formatId) }
        }
        view.findViewById<MaterialButton>(R.id.export_docx_button).apply {
            contentDescription = context.getString(R.string.workspace_export_docx_cd)
            setOnClickListener { runOfficeExport(ExportFormatCatalog.DOCX.formatId) }
        }
        view.findViewById<MaterialButton>(R.id.export_xlsx_button).apply {
            contentDescription = context.getString(R.string.workspace_export_xlsx_cd)
            setOnClickListener { runOfficeExport(ExportFormatCatalog.XLSX.formatId) }
        }
        view.findViewById<MaterialButton>(R.id.export_long_image_button).apply {
            contentDescription = context.getString(R.string.workspace_export_long_image_cd)
            setOnClickListener { runLongImageExport() }
        }
        view.findViewById<MaterialButton>(R.id.export_print_button).apply {
            contentDescription = context.getString(R.string.workspace_export_print_cd)
            setOnClickListener { runPrint() }
        }
        view.findViewById<MaterialButton>(R.id.export_print_paper_a4).setOnClickListener {
            selectPaper(PaperIdA4)
        }
        view.findViewById<MaterialButton>(R.id.export_print_paper_letter).setOnClickListener {
            selectPaper(PaperIdLetter)
        }
        view.findViewById<MaterialButton>(R.id.export_print_scale_fit).setOnClickListener {
            selectScale(PrintScaleFit)
        }
        view.findViewById<MaterialButton>(R.id.export_print_scale_fill).setOnClickListener {
            selectScale(PrintScaleFill)
        }
        view.findViewById<MaterialButton>(R.id.export_print_scale_actual).setOnClickListener {
            selectScale(PrintScaleActual)
        }
        renderPrintToggles(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeDocuments()
                    .map { documents -> documents.firstOrNull { it.id == documentId } }
                    .distinctUntilChanged()
                    .collect { document ->
                        if (document == null) {
                            parentFragmentManager.popBackStack()
                        } else {
                            documentTitle = document.title
                            state = ViewerLikeState(
                                pageCount = repository.getPages(document.id).size,
                            )
                            view.findViewById<Toolbar>(R.id.export_toolbar).title =
                                document.title
                            view.findViewById<TextView>(R.id.export_document_title).text =
                                document.title
                        }
                    }
            }
        }
    }

    // ---------------------------------------------------- office formats

    /** Runs one Office conversion (PPTX/DOCX/XLSX) through the FLOW engine. */
    private fun runOfficeExport(formatId: String) {
        val host = view ?: return
        if (!hasPagesForExport(host)) return
        if (!pickerUi.begin(formatId)) return
        setStatus(host, getString(R.string.workspace_export_ocr_working))
        renderBusyState(host)
        val textSource = MlKitPageTextSource(TimeSource.SYSTEM)
        val engine = OfficeExportEngine(
            contentStore = contentStore,
            textSource = textSource,
            timeSource = TimeSource.SYSTEM,
            dispatcher = Dispatchers.IO,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val result = engine.export(repository, documentId, formatId)
            textSource.close()
            pickerUi.finish(formatId, result != null)
            renderBusyState(host)
            if (result == null) {
                showSnackbar(
                    host,
                    getString(
                        R.string.workspace_export_failed,
                        getString(R.string.workspace_export_failed_reason_ocr),
                    ),
                )
            } else {
                showExportResult(host, result.artifact)
                shareArtifact(result.artifact)
            }
        }
    }

    // -------------------------------------------------------- long image

    /** Runs the long-image strip export (planner + raster applier). */
    private fun runLongImageExport() {
        val host = view ?: return
        if (!hasPagesForExport(host)) return
        val formatId = ExportFormatCatalog.LONG_IMAGE.formatId
        if (!pickerUi.begin(formatId)) return
        setStatus(host, getString(R.string.workspace_export_working))
        renderBusyState(host)
        val engine = LongImageExportEngine(
            contentStore = contentStore,
            boundsDecoder = AndroidBoundsDecoder(),
            rasterizer = AndroidStripRasterizer(),
            timeSource = TimeSource.SYSTEM,
            dispatcher = Dispatchers.IO,
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val artifact = engine.export(repository, documentId)
            pickerUi.finish(formatId, artifact != null)
            renderBusyState(host)
            if (artifact == null) {
                showSnackbar(
                    host,
                    getString(
                        R.string.workspace_export_failed,
                        getString(R.string.workspace_export_failed_reason_image),
                    ),
                )
            } else {
                showExportResult(host, artifact)
                shareArtifact(artifact)
            }
        }
    }

    // ------------------------------------------------------------ print

    /** Pre-renders the planned job, then hands it to the print framework. */
    private fun runPrint() {
        val host = view ?: return
        val formatId = PrintFormatId
        if (!pickerUi.begin(formatId)) return
        setStatus(host, getString(R.string.workspace_export_working))
        renderBusyState(host)
        val renderer = PrintSheetRenderer()
        viewLifecycleOwner.lifecycleScope.launch {
            val pages = repository.getPages(documentId).sortedBy { it.index }
            if (pages.isEmpty()) {
                pickerUi.finish(formatId, false)
                renderBusyState(host)
                showSnackbar(host, getString(R.string.workspace_export_empty))
                return@launch
            }
            val inputs = ArrayList<PrintPageInput>(pages.size)
            for (page in pages) {
                val ref = page.processedImageRef
                val bytes = if (ref == null) null else contentStore.open(ref)
                if (bytes == null) {
                    pickerUi.finish(formatId, false)
                    renderBusyState(host)
                    showSnackbar(
                        host,
                        getString(
                            R.string.workspace_export_failed,
                            getString(R.string.workspace_export_failed_reason_page),
                        ),
                    )
                    return@launch
                }
                inputs.add(
                    PrintPageInput(
                        pageId = page.id,
                        bytes = bytes,
                        rotationDegrees = page.rotationDegrees,
                    ),
                )
            }
            val renderOutcome = withContext(Dispatchers.IO) {
                renderer.render(inputs, printUi.buildRequest(), TimeSource.SYSTEM.nowMillis())
            }
            pickerUi.finish(formatId, renderOutcome is PrintRenderResult.Ok)
            renderBusyState(host)
            when (renderOutcome) {
                is PrintRenderResult.Ok -> {
                    val activity = activity
                    if (activity == null) {
                        showSnackbar(host, getString(R.string.workspace_print_unavailable))
                    } else {
                        val launched = PrintLauncher.print(
                            context = activity,
                            jobName = documentTitle.ifEmpty {
                                getString(R.string.workspace_document_fallback_name)
                            },
                            pdfBytes = renderOutcome.pdfBytes,
                            sheetCount = renderOutcome.plan.totalSheetCount,
                        )
                        if (!launched) {
                            showSnackbar(host, getString(R.string.workspace_print_unavailable))
                        }
                    }
                }
                is PrintRenderResult.Rejected -> showSnackbar(
                    host,
                    getString(
                        R.string.workspace_export_failed,
                        renderOutcome.reason.description,
                    ),
                )
                is PrintRenderResult.RenderFailed -> showSnackbar(
                    host,
                    getString(
                        R.string.workspace_export_failed,
                        getString(R.string.workspace_export_failed_reason_print),
                    ),
                )
            }
        }
    }

    /** Toggles the paper selection buttons to the picked paper. */
    private fun selectPaper(paperId: String) {
        if (printUi.selectPaper(paperId)) {
            renderPrintToggles(view ?: return)
        }
    }

    /** Toggles the scale-mode selection buttons to the picked mode. */
    private fun selectScale(scaleModeId: String) {
        if (printUi.selectScaleMode(scaleModeId)) {
            renderPrintToggles(view ?: return)
        }
    }

    // ---------------------------------------------------------- render

    /** True when the loaded document has pages; otherwise the honest empty snackbar. */
    private fun hasPagesForExport(host: View): Boolean {
        val current = state
        if (current == null || current.pageCount < 1) {
            showSnackbar(host, getString(R.string.workspace_export_empty))
            return false
        }
        return true
    }

    /** Disables every format button while a run is in flight. */
    private fun renderBusyState(host: View) {
        val busy = pickerUi.isBusy()
        for (format in ExportFormatCatalog.ALL) {
            val button = host.findViewById<MaterialButton>(buttonIdOf(format.formatId))
            button?.isEnabled = !busy
        }
        host.findViewById<MaterialButton>(R.id.export_print_button)?.isEnabled = !busy
        if (!busy) {
            setStatus(host, "")
        }
    }

    /** Reflects the print paper + scale selection on the toggle buttons. */
    private fun renderPrintToggles(host: View) {
        val paperId = printUi.paperId()
        host.findViewById<MaterialButton>(R.id.export_print_paper_a4)
            ?.isChecked = paperId == PaperIdA4
        host.findViewById<MaterialButton>(R.id.export_print_paper_letter)
            ?.isChecked = paperId == PaperIdLetter
        val scaleModeId = printUi.scaleModeId()
        host.findViewById<MaterialButton>(R.id.export_print_scale_fit)
            ?.isChecked = scaleModeId == PrintScaleFit
        host.findViewById<MaterialButton>(R.id.export_print_scale_fill)
            ?.isChecked = scaleModeId == PrintScaleFill
        host.findViewById<MaterialButton>(R.id.export_print_scale_actual)
            ?.isChecked = scaleModeId == PrintScaleActual
    }

    /** Maps a format id onto its layout button id. */
    private fun buttonIdOf(formatId: String): Int {
        return when (formatId) {
            ExportFormatCatalog.PPTX.formatId -> R.id.export_pptx_button
            ExportFormatCatalog.DOCX.formatId -> R.id.export_docx_button
            ExportFormatCatalog.XLSX.formatId -> R.id.export_xlsx_button
            ExportFormatCatalog.LONG_IMAGE.formatId -> R.id.export_long_image_button
            else -> R.id.export_pptx_button
        }
    }

    private fun setStatus(host: View, message: String) {
        host.findViewById<TextView>(R.id.export_status).text = message
    }

    /** The honest success snackbar: name + human-readable size. */
    private fun showExportResult(host: View, artifact: ExportArtifact) {
        showSnackbar(
            host,
            getString(
                R.string.workspace_export_success,
                artifact.displayName,
                formatSizeBytes(artifact.sizeBytes.toLong()),
            ),
        )
    }

    /** Hands the artifact to the share sheet (the PROD-007 FLOW pattern). */
    private fun shareArtifact(artifact: ExportArtifact) {
        val host = view ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val chooser = ShareIntents.shareArtifact(requireContext(), artifact, contentStore)
            if (chooser == null) {
                showSnackbar(host, getString(R.string.workspace_share_unavailable))
            } else {
                startActivity(chooser)
            }
        }
    }

    private fun showSnackbar(host: View, message: String) {
        Snackbar.make(host, message, Snackbar.LENGTH_LONG).show()
    }

    /** Minimal page-count state (the viewer's ViewerUiState is not this order's). */
    private data class ViewerLikeState(val pageCount: Int)

    companion object {
        private const val ARG_DOCUMENT_ID = "arg_document_id"
        private const val PaperIdA4 = "paper-a4"
        private const val PaperIdLetter = "paper-letter"
        private const val PrintScaleFit = "fit"
        private const val PrintScaleFill = "fill"
        private const val PrintScaleActual = "actual"
        private const val PrintFormatId = "print"

        /** Factory (per-instance state travels via the arguments bundle). */
        fun forDocument(
            repository: DocumentRepository,
            contentStore: ContentStore,
            documentId: String,
        ): DocumentExportFragment = DocumentExportFragment(repository, contentStore).apply {
            arguments = Bundle().apply { putString(ARG_DOCUMENT_ID, documentId) }
        }
    }
}
