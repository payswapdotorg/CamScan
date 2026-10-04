package org.payswap.camscan.document.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.replacePageRaster
import org.payswap.camscan.tools.render.Rect
import org.payswap.camscan.tools.signature.SignatureApplier
import org.payswap.camscan.tools.signature.SignatureEntry
import org.payswap.camscan.tools.signature.SignatureSketch
import org.payswap.camscan.tools.signature.SignatureStore
import org.payswap.camscan.tools.ui.PageFit
import org.payswap.camscan.tools.ui.PageFitGeometry
import org.payswap.camscan.tools.ui.SignatureApplyUi
import org.payswap.camscan.tools.ui.SignaturePadUi

// SignatureApplyFragment (CAMSCAN-VERIFY-001): apply-signature surface for
// one page. The stored signature is picked from SignatureStore (a deep link
// to the pad appears when none is stored); drag-to-place feeds the pure
// SignatureApplyUi, which resolves the drop into the frozen
// SignatureApplier placement vocabulary (anchor corner, width fraction,
// margin); the live preview is rendered BY THE ENGINE on the preview
// buffer, so the preview cannot drift from the applied result; Apply runs
// the engine on the full-resolution raster and persists it through the
// repository (the page survives viewer reopen).

/**
 * Signature placement surface: displays the engine-composited preview
 * bitmap (fit-center) plus the placement frame, and reports drag positions
// as RELATIVE page coordinates (0..1) so the same drop drives both the
// preview render and the full-resolution apply.
 */
class SignaturePlacementView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var preview: android.graphics.Bitmap? = null
    private var frame: Rect? = null
    private var pageWidth: Int = 0
    private var pageHeight: Int = 0
    private var fit: PageFit = PageFit(0f, 0f, 0f)
    private var onDrop: ((relX: Float, relY: Float) -> Unit)? = null

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF1976D2.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    // Preallocated draw transform (DrawAllocation hygiene).
    private val drawMatrix = android.graphics.Matrix()

    /** Installs the drop listener invoked on DOWN and MOVE. */
    fun setDropListener(listener: (relX: Float, relY: Float) -> Unit) {
        onDrop = listener
    }

    /** Sets the engine-rendered preview plus its placement frame (page coords). */
    fun setDisplay(bitmap: android.graphics.Bitmap?, frameRect: Rect?, pageW: Int, pageH: Int) {
        preview = bitmap
        frame = frameRect
        pageWidth = pageW
        pageHeight = pageH
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pageWidth > 0 && pageHeight > 0) {
            fit = PageFitGeometry.fit(w, h, pageWidth, pageHeight)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageWidth <= 0 || pageHeight <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val fitNow = fit
                if (fitNow.scale <= 0f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val relX = (event.x - fitNow.offsetX) / (fitNow.scale * pageWidth)
                val relY = (event.y - fitNow.offsetY) / (fitNow.scale * pageHeight)
                onDrop?.invoke(relX, relY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = preview ?: return
        if (fit.scale <= 0f && width > 0 && height > 0 && pageWidth > 0) {
            fit = PageFitGeometry.fit(width, height, pageWidth, pageHeight)
        }
        if (fit.scale <= 0f) return
        drawMatrix.reset()
        drawMatrix.postScale(fit.scale, fit.scale)
        drawMatrix.postTranslate(fit.offsetX, fit.offsetY)
        canvas.drawBitmap(bitmap, drawMatrix, null)
        frame?.let { rect ->
            val left = fit.pageToViewX(rect.x)
            val top = fit.pageToViewY(rect.y)
            val right = fit.pageToViewX(rect.x + rect.width)
            val bottom = fit.pageToViewY(rect.y + rect.height)
            canvas.drawRect(left, top, right, bottom, framePaint)
        }
    }
}

/** Immutable render request for the throttled preview pipeline. */
private class RenderRequest(
    val sketch: SignatureSketch,
    val relDropX: Float?,
    val relDropY: Float?,
    val fraction: Float,
)

/** The apply-signature fragment (constructor-injected engines). */
class SignatureApplyFragment(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
    private val signatureStore: SignatureStore,
) : Fragment(R.layout.fragment_signature_apply) {

    private val documentId: String
        get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()

    private val pageId: String
        get() = arguments?.getString(ARG_PAGE_ID).orEmpty()

    private val draft = SignatureApplyUi()

    private var fullRaster: PageRaster? = null
    private var previewRaster: PageRaster? = null
    private var selected: SignatureEntry? = null
    private var relDropX: Float? = null
    private var relDropY: Float? = null
    private var previewJob: Job? = null
    private var pendingRequest: RenderRequest? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<Toolbar>(R.id.viewer_apply_toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.navigationContentDescription = context?.getString(R.string.workspace_viewer_back_cd)

        val statusView = view.findViewById<TextView>(R.id.viewer_apply_status)
        statusView.text = getString(R.string.viewer_apply_status_loading)

        view.findViewById<SignaturePlacementView>(R.id.viewer_apply_surface).setDropListener { rx, ry ->
            relDropX = coerceRelative(rx)
            relDropY = coerceRelative(ry)
            schedulePreviewRender()
        }

        view.findViewById<MaterialButton>(R.id.viewer_apply_smaller).apply {
            contentDescription = context.getString(R.string.viewer_apply_smaller_cd)
            setOnClickListener {
                draft.stepSizeDown()
                renderSizeLabel(view)
                schedulePreviewRender()
            }
        }
        view.findViewById<MaterialButton>(R.id.viewer_apply_larger).apply {
            contentDescription = context.getString(R.string.viewer_apply_larger_cd)
            setOnClickListener {
                draft.stepSizeUp()
                renderSizeLabel(view)
                schedulePreviewRender()
            }
        }
        view.findViewById<MaterialButton>(R.id.viewer_apply_pick).apply {
            contentDescription = context.getString(R.string.viewer_apply_pick_cd)
            setOnClickListener { showPickDialog() }
        }
        view.findViewById<MaterialButton>(R.id.viewer_apply_create).apply {
            contentDescription = context.getString(R.string.viewer_apply_create_cd)
            setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.app_fragment_container,
                        SignaturePadFragment.forDocument(signatureStore, documentId),
                    )
                    .addToBackStack(BACK_STACK_SIGNATURE_PAD)
                    .commit()
            }
        }
        view.findViewById<MaterialButton>(R.id.viewer_apply_apply).apply {
            contentDescription = context.getString(R.string.viewer_apply_apply_cd)
            isEnabled = false
            setOnClickListener { applySignature() }
        }
        view.findViewById<MaterialButton>(R.id.viewer_apply_cancel).apply {
            setOnClickListener { parentFragmentManager.popBackStack() }
        }

        renderSizeLabel(view)
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch(Dispatchers.IO) {
            loadPageAndSelection(view, statusView, owner)
        }
    }

    // ------------------------------------------------------------ loading

    private suspend fun loadPageAndSelection(view: View, statusView: TextView, owner: androidx.lifecycle.LifecycleOwner) {
        val raster = loadPageRaster()
        fullRaster = raster
        previewRaster = raster?.let { PageRasterBridge.previewRaster(it) }
        val entries = signatureStore.list()
        selected = entries.lastOrNull()
        owner.lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded) return@launch
            if (raster == null) {
                statusView.text = getString(R.string.viewer_apply_status_failed)
                view.findViewById<MaterialButton>(R.id.viewer_apply_apply).isEnabled = false
                return@launch
            }
            if (entries.isEmpty()) {
                statusView.text = getString(R.string.viewer_apply_no_signature)
                view.findViewById<View>(R.id.viewer_apply_create).visibility = View.VISIBLE
                view.findViewById<MaterialButton>(R.id.viewer_apply_apply).isEnabled = false
                return@launch
            }
            statusView.text = getString(R.string.viewer_apply_drag_hint)
            view.findViewById<MaterialButton>(R.id.viewer_apply_apply).isEnabled = true
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

    private fun renderSizeLabel(view: View) {
        val label = view.findViewById<TextView>(R.id.viewer_apply_size_label)
        val percent = Math.round(draft.fraction() * 100.0)
        label.text = getString(R.string.viewer_apply_size_label, percent)
    }

    private fun schedulePreviewRender() {
        val preview = previewRaster ?: return
        val sketch = selected?.sketch ?: return
        val owner = viewLifecycleOwner
        val request = RenderRequest(sketch, relDropX, relDropY, draft.fraction())
        if (previewJob?.isActive == true) {
            pendingRequest = request
            return
        }
        previewJob = owner.lifecycleScope.launch(Dispatchers.IO) {
            renderPreview(request, preview, owner)
            while (pendingRequest != null) {
                val next = pendingRequest
                pendingRequest = null
                if (next != null) renderPreview(next, preview, owner)
            }
        }
    }

    private suspend fun renderPreview(
        request: RenderRequest,
        preview: PageRaster,
        owner: androidx.lifecycle.LifecycleOwner,
    ) {
        val sketchBox = request.sketch.boundingBox ?: return
        val dropX = request.relDropX
        val dropY = request.relDropY
        if (dropX != null && dropY != null) {
            draft.dragTo(
                Math.round(dropX * preview.width.toDouble()).toInt(),
                Math.round(dropY * preview.height.toDouble()).toInt(),
            )
        }
        val inkBox = SignatureApplyUi.inkBoxSize(
            sketchBox.width, sketchBox.height, preview.width, request.fraction,
        ) ?: return
        val placement = draft.placement(preview.width, preview.height, inkBox.first, inkBox.second)
        val rendered = SignatureApplier.apply(
            preview.pixels, preview.width, preview.height, request.sketch, placement,
        )
        val frame = SignatureApplyUi.previewRect(
            placement, preview.width, preview.height, inkBox.first, inkBox.second,
        )
        val bitmap = PageRasterBridge.toBitmap(PageRaster(preview.width, preview.height, rendered))
        owner.lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded) return@launch
            view?.findViewById<SignaturePlacementView>(R.id.viewer_apply_surface)
                ?.setDisplay(bitmap, frame, preview.width, preview.height)
        }
    }

    private fun showPickDialog() {
        val entries = signatureStore.list()
        if (entries.isEmpty()) return
        val labels = ArrayList<String>(entries.size)
        for (entry in entries) {
            labels.add(entry.name + " (" + entry.sketch.strokes.size + ")")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.viewer_apply_pick_title)
            .setItems(labels.toTypedArray()) { _, which ->
                selected = entries[which]
                schedulePreviewRender()
            }
            .setNegativeButton(R.string.workspace_dialog_cancel, null)
            .show()
    }

    // -------------------------------------------------------------- apply

    private fun applySignature() {
        val raster = fullRaster
        val sketch = selected?.sketch
        val host = view
        if (raster == null || sketch == null || host == null) return
        val button = host.findViewById<MaterialButton>(R.id.viewer_apply_apply)
        button.isEnabled = false
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val sketchBox = sketch.boundingBox
            val outcome: Boolean
            if (sketchBox == null) {
                outcome = false
            } else {
                val dropX = relDropX
                val dropY = relDropY
                if (dropX != null && dropY != null) {
                    draft.dragTo(
                        Math.round(dropX * raster.width.toDouble()).toInt(),
                        Math.round(dropY * raster.height.toDouble()).toInt(),
                    )
                }
                val inkBox = SignatureApplyUi.inkBoxSize(
                    sketchBox.width, sketchBox.height, raster.width, draft.fraction(),
                )
                val placement = if (inkBox == null) {
                    null
                } else {
                    draft.placement(raster.width, raster.height, inkBox.first, inkBox.second)
                }
                val updated = if (placement == null) {
                    null
                } else {
                    val rendered = SignatureApplier.apply(
                        raster.pixels, raster.width, raster.height, sketch, placement,
                    )
                    val bytes = PageRasterBridge.encodePng(
                        PageRaster(raster.width, raster.height, rendered),
                    )
                    val ref = contentStore.put(editedRefKey(), bytes)
                    repository.replacePageRaster(documentId, pageId, ref, TimeSource.SYSTEM)
                }
                outcome = updated != null
            }
            owner.lifecycleScope.launch(Dispatchers.Main) {
                if (!isAdded) return@launch
                if (outcome) {
                    Snackbar.make(host, R.string.viewer_apply_success, Snackbar.LENGTH_LONG).show()
                    parentFragmentManager.popBackStack()
                } else {
                    button.isEnabled = true
                    Snackbar.make(
                        host,
                        getString(
                            R.string.viewer_apply_failed,
                            getString(R.string.viewer_apply_failed_reason_page),
                        ),
                        Snackbar.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun editedRefKey(): String =
        "edited." + pageId + "." + TimeSource.SYSTEM.nowMillis()

    private fun coerceRelative(value: Float): Float = Math.max(0f, Math.min(value, 1f))

    companion object {
        private const val ARG_DOCUMENT_ID = "arg_document_id"
        private const val ARG_PAGE_ID = "arg_page_id"
        private const val BACK_STACK_SIGNATURE_PAD = "workspace_signature_pad"

        fun forPage(
            repository: DocumentRepository,
            contentStore: ContentStore,
            store: SignatureStore,
            documentId: String,
            pageId: String,
        ): SignatureApplyFragment = SignatureApplyFragment(repository, contentStore, store).apply {
            arguments = Bundle().apply {
                putString(ARG_DOCUMENT_ID, documentId)
                putString(ARG_PAGE_ID, pageId)
            }
        }
    }
}
