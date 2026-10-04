package org.payswap.camscan.document.viewer

import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.replacePageRaster
import org.payswap.camscan.tools.ui.WatermarkDraftUi
import org.payswap.camscan.tools.watermark.WatermarkApplier

// WatermarkComposerFragment (CAMSCAN-VERIFY-001): watermark compose surface
// for one page. Text, diagonal-vs-tile layout and strength feed the pure
// WatermarkDraftUi; the live preview is rendered BY THE ENGINE
// (WatermarkApplier over the planner) on the full page raster, downscaled
// for display, so the preview is exactly what Apply persists. Apply bakes
// the watermark into the page raster through the repository.

/** The watermark composer fragment (constructor-injected repository access). */
class WatermarkComposerFragment(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
) : Fragment(R.layout.fragment_watermark_composer) {

    private val documentId: String
        get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()

    private val pageId: String
        get() = arguments?.getString(ARG_PAGE_ID).orEmpty()

    private val draft = WatermarkDraftUi()

    private var fullRaster: PageRaster? = null
    private var previewRev: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<Toolbar>(R.id.watermark_toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.navigationContentDescription = context?.getString(R.string.workspace_viewer_back_cd)

        val statusView = view.findViewById<TextView>(R.id.watermark_status)
        statusView.text = getString(R.string.watermark_status_loading)

        val textInput = view.findViewById<EditText>(R.id.watermark_text_input)
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                draft.text = s?.toString().orEmpty()
                refreshControls(view)
                schedulePreviewRender()
            }
        })

        view.findViewById<SwitchCompat>(R.id.watermark_diagonal_switch).setOnCheckedChangeListener { _, checked ->
            draft.diagonal = checked
            schedulePreviewRender()
        }

        val seek = view.findViewById<SeekBar>(R.id.watermark_strength_seek)
        seek.max = WatermarkDraftUi.MAX_STRENGTH_PERCENT
        seek.progress = draft.strengthPercent()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                draft.setStrengthPercent(progress)
                view.findViewById<TextView>(R.id.watermark_strength_value).text =
                    getString(R.string.watermark_strength_value, progress)
                refreshControls(view)
                schedulePreviewRender()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        view.findViewById<MaterialButton>(R.id.watermark_apply).apply {
            contentDescription = context.getString(R.string.watermark_apply_cd)
            isEnabled = false
            setOnClickListener { applyWatermark() }
        }
        view.findViewById<MaterialButton>(R.id.watermark_cancel).apply {
            setOnClickListener { parentFragmentManager.popBackStack() }
        }

        refreshControls(view)
        view.findViewById<TextView>(R.id.watermark_strength_value).text =
            getString(R.string.watermark_strength_value, draft.strengthPercent())

        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch(Dispatchers.IO) {
            loadPage(statusView, owner)
        }
    }

    private fun refreshControls(view: View) {
        val canApply = draft.canApply()
        view.findViewById<MaterialButton>(R.id.watermark_apply).isEnabled = canApply
        // Visible reason while Apply is disabled: blank text or zero strength.
        view.findViewById<TextView>(R.id.watermark_error).visibility =
            if (!canApply && fullRaster != null) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------- loading

    private suspend fun loadPage(statusView: TextView, owner: LifecycleOwner) {
        val raster = loadPageRaster()
        fullRaster = raster
        owner.lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded) return@launch
            if (raster == null) {
                statusView.text = getString(R.string.watermark_status_failed)
                view?.findViewById<MaterialButton>(R.id.watermark_apply)?.isEnabled = false
                return@launch
            }
            statusView.text = getString(R.string.watermark_hint)
            schedulePreviewRender()
        }
    }

    private suspend fun loadPageRaster(): PageRaster? {
        if (pageId.isEmpty()) return null
        val pages = repository.getPages(documentId)
        val page = pages.firstOrNull { it.id == pageId } ?: return null
        val ref = page.processedImageRef ?: return null
        val bytes = contentStore.open(ref) ?: return null
        return PageRasterBridge.decode(bytes, page.rotationDegrees)
    }

    // ------------------------------------------------------------ preview

    private fun schedulePreviewRender() {
        val raster = fullRaster ?: return
        val rev = ++previewRev
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            if (previewRev == rev && isAdded) {
                renderPreview(raster, owner)
            }
        }
    }

    private suspend fun renderPreview(raster: PageRaster, owner: LifecycleOwner) {
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val spec = draft.spec()
            val rendered = if (spec.text.isEmpty() || spec.opacity <= 0) {
                raster.pixels
            } else {
                WatermarkApplier.apply(raster.pixels, raster.width, raster.height, spec)
            }
            val full = PageRasterBridge.toBitmap(PageRaster(raster.width, raster.height, rendered))
            val longEdge = Math.max(full.width, full.height)
            val bitmap = if (longEdge > PageRasterBridge.PREVIEW_MAX_EDGE) {
                val scale = PageRasterBridge.PREVIEW_MAX_EDGE.toFloat() / longEdge
                val w = Math.max(1, Math.round(full.width * scale))
                val h = Math.max(1, Math.round(full.height * scale))
                Bitmap.createScaledBitmap(full, w, h, true)
            } else {
                full
            }
            owner.lifecycleScope.launch(Dispatchers.Main) {
                if (!isAdded) return@launch
                view?.findViewById<ImageView>(R.id.watermark_preview)?.setImageBitmap(bitmap)
            }
        }
    }

    // -------------------------------------------------------------- apply

    private fun applyWatermark() {
        val raster = fullRaster
        val host = view
        if (raster == null || host == null || !draft.canApply()) return
        val button = host.findViewById<MaterialButton>(R.id.watermark_apply)
        button.isEnabled = false
        val spec = draft.spec()
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val rendered = WatermarkApplier.apply(raster.pixels, raster.width, raster.height, spec)
            val bytes = PageRasterBridge.encodePng(PageRaster(raster.width, raster.height, rendered))
            val ref = contentStore.put("edited." + pageId + "." + TimeSource.SYSTEM.nowMillis(), bytes)
            val updated = repository.replacePageRaster(documentId, pageId, ref, TimeSource.SYSTEM)
            owner.lifecycleScope.launch(Dispatchers.Main) {
                if (!isAdded) return@launch
                if (updated != null) {
                    Snackbar.make(host, R.string.watermark_applied, Snackbar.LENGTH_LONG).show()
                    parentFragmentManager.popBackStack()
                } else {
                    button.isEnabled = true
                    Snackbar.make(
                        host,
                        getString(
                            R.string.watermark_apply_failed,
                            getString(R.string.watermark_apply_failed_reason_page),
                        ),
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val ARG_DOCUMENT_ID = "arg_document_id"
        private const val ARG_PAGE_ID = "arg_page_id"
        private const val PREVIEW_DEBOUNCE_MS: Long = 250L

        fun forPage(
            repository: DocumentRepository,
            contentStore: ContentStore,
            documentId: String,
            pageId: String,
        ): WatermarkComposerFragment =
            WatermarkComposerFragment(repository, contentStore).apply {
                arguments = Bundle().apply {
                    putString(ARG_DOCUMENT_ID, documentId)
                    putString(ARG_PAGE_ID, pageId)
                }
            }
    }
}
