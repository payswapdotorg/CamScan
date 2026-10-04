package org.payswap.camscan.document.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.payswap.camscan.R
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.replacePageRaster
import org.payswap.camscan.tools.annotation.Annotation
import org.payswap.camscan.tools.annotation.AnnotationApplier
import org.payswap.camscan.tools.annotation.AnnotationStore
import org.payswap.camscan.tools.render.TextGlyphSource
import org.payswap.camscan.tools.ui.AnnotationEditorUi
import org.payswap.camscan.tools.ui.PageFitGeometry

// AnnotationEditorFragment (CAMSCAN-VERIFY-001): annotation overlay for one
// page. The three frozen engine models are drafted live: Ink by finger
// drawing, Highlight by rectangle drag, TextNote by tap plus a text dialog.
// Committed drafts render through AnnotationApplier on the page raster (the
// live preview IS the engine output, downscaled for display), and Apply
// persists the engine-applied raster through the repository. Edits also go
// through the AnnotationStore (one add per committed annotation).

/**
 * Annotation surface: draws the engine-composited preview bitmap
 * (fit-center) plus the in-flight live layer (in-progress ink, highlight
 * draft rectangle, pending text anchor). Touch positions are reported in
 * FULL-resolution page coordinates.
 */
class AnnotationSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var display: Bitmap? = null
    private var pageWidth: Int = 0
    private var pageHeight: Int = 0
    private var pageScale: Float = 0f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f
    private var controller: AnnotationEditorUi? = null
    private var onPen: ((action: Int, pageX: Float, pageY: Float) -> Unit)? = null

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val draftRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (AnnotationEditorUi.DRAFT_HIGHLIGHT_ALPHA shl 24) or 0x00FFFF00
    }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF1976D2.toInt()
    }

    // Preallocated draw transform (DrawAllocation hygiene).
    private val drawMatrix = android.graphics.Matrix()

    /** Binds the controller plus the pen-event listener. */
    fun bind(controller: AnnotationEditorUi, onPen: (action: Int, pageX: Float, pageY: Float) -> Unit) {
        this.controller = controller
        this.onPen = onPen
    }

    /** Sets the engine preview bitmap for a page of pageW x pageH (full res). */
    fun setDisplay(bitmap: Bitmap, pageW: Int, pageH: Int) {
        display = bitmap
        pageWidth = pageW
        pageHeight = pageH
        recomputeFit()
        invalidate()
    }

    private fun recomputeFit() {
        val bitmap = display
        if (width <= 0 || height <= 0 || bitmap == null || pageWidth <= 0 || pageHeight <= 0) {
            pageScale = 0f
            return
        }
        val fit = PageFitGeometry.fit(width, height, bitmap.width, bitmap.height)
        pageScale = fit.scale * (bitmap.width.toFloat() / pageWidth.toFloat())
        offsetX = fit.offsetX
        offsetY = fit.offsetY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageScale <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onPen?.invoke(event.actionMasked, toPageX(event.x), toPageY(event.y))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                onPen?.invoke(event.actionMasked, toPageX(event.x), toPageY(event.y))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                onPen?.invoke(event.actionMasked, toPageX(event.x), toPageY(event.y))
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun toPageX(viewX: Float): Float =
        if (pageScale <= 0f) 0f else (viewX - offsetX) / pageScale

    private fun toPageY(viewY: Float): Float =
        if (pageScale <= 0f) 0f else (viewY - offsetY) / pageScale

    private fun toViewX(pageX: Float): Float = offsetX + pageX * pageScale

    private fun toViewY(pageY: Float): Float = offsetY + pageY * pageScale

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = display ?: return
        if (pageScale <= 0f) recomputeFit()
        if (pageScale <= 0f) return
        drawMatrix.reset()
        val displayScale = pageScale * pageWidth / bitmap.width
        drawMatrix.postScale(displayScale, displayScale)
        drawMatrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(bitmap, drawMatrix, null)
        val editor = controller ?: return
        // In-progress ink polyline.
        editor.inProgressStroke()?.let { stroke ->
            inkPaint.color = stroke.colorArgb.toInt()
            inkPaint.strokeWidth = Math.max(1.5f, stroke.strokeWidthPx * pageScale)
            if (stroke.points.size == 1) {
                canvas.drawPoint(
                    toViewX(stroke.points[0].x.toFloat()),
                    toViewY(stroke.points[0].y.toFloat()),
                    inkPaint,
                )
            } else {
                var previous = stroke.points[0]
                for (index in 1 until stroke.points.size) {
                    val current = stroke.points[index]
                    canvas.drawLine(
                        toViewX(previous.x.toFloat()),
                        toViewY(previous.y.toFloat()),
                        toViewX(current.x.toFloat()),
                        toViewY(current.y.toFloat()),
                        inkPaint,
                    )
                    previous = current
                }
            }
        }
        // Highlight draft rectangle (translucent).
        editor.highlightDraftRect()?.let { rect ->
            val left = toViewX(rect.x.toFloat())
            val top = toViewY(rect.y.toFloat())
            val right = toViewX((rect.x + rect.width).toFloat())
            val bottom = toViewY((rect.y + rect.height).toFloat())
            canvas.drawRect(left, top, right, bottom, draftRectPaint)
        }
        // Pending text anchor (crosshair).
        editor.pendingTextAnchor()?.let { anchor ->
            val cx = toViewX(anchor.x.toFloat())
            val cy = toViewY(anchor.y.toFloat())
            canvas.drawCircle(cx, cy, 12f, anchorPaint)
        }
    }
}

/** The annotation editor fragment (constructor-injected engines). */
class AnnotationEditorFragment(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
    private val annotationStore: AnnotationStore,
) : Fragment(R.layout.fragment_annotation_editor) {

    private val documentId: String
        get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()

    private val pageId: String
        get() = arguments?.getString(ARG_PAGE_ID).orEmpty()

    private val glyphSource = TextGlyphSource()
    private var editor: AnnotationEditorUi? = null
    private var fullRaster: PageRaster? = null
    private var previewJob: kotlinx.coroutines.Job? = null
    private var previewPending: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<Toolbar>(R.id.annotation_toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.navigationContentDescription = context?.getString(R.string.workspace_viewer_back_cd)

        val statusView = view.findViewById<TextView>(R.id.annotation_status)
        statusView.text = getString(R.string.annotation_status_loading)

        view.findViewById<MaterialButtonToggleGroup>(R.id.annotation_mode_group)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val next = when (checkedId) {
                    R.id.annotation_mode_ink -> AnnotationEditorUi.Mode.INK
                    R.id.annotation_mode_highlight -> AnnotationEditorUi.Mode.HIGHLIGHT
                    R.id.annotation_mode_text -> AnnotationEditorUi.Mode.TEXT
                    else -> return@addOnButtonCheckedListener
                }
                editor?.selectMode(next)
                statusView.text = modeHint(next)
            }

        view.findViewById<MaterialButton>(R.id.annotation_undo).apply {
            contentDescription = context.getString(R.string.annotation_undo_cd)
            setOnClickListener {
                if (editor?.undoLast() == true) {
                    refreshEnginePreview()
                }
            }
        }
        view.findViewById<MaterialButton>(R.id.annotation_apply).apply {
            contentDescription = context.getString(R.string.annotation_apply_cd)
            isEnabled = false
            setOnClickListener { applyAnnotations() }
        }
        view.findViewById<MaterialButton>(R.id.annotation_cancel).apply {
            setOnClickListener { parentFragmentManager.popBackStack() }
        }

        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch(Dispatchers.IO) {
            loadPage(view, statusView, owner)
        }
    }

    private fun modeHint(mode: AnnotationEditorUi.Mode): String = when (mode) {
        AnnotationEditorUi.Mode.INK -> getString(R.string.annotation_hint_ink)
        AnnotationEditorUi.Mode.HIGHLIGHT -> getString(R.string.annotation_hint_highlight)
        AnnotationEditorUi.Mode.TEXT -> getString(R.string.annotation_hint_text)
    }

    // ------------------------------------------------------------- loading

    private suspend fun loadPage(view: View, statusView: TextView, owner: androidx.lifecycle.LifecycleOwner) {
        val raster = loadPageRaster()
        fullRaster = raster
        owner.lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded) return@launch
            if (raster == null) {
                statusView.text = getString(R.string.annotation_status_failed)
                view.findViewById<MaterialButton>(R.id.annotation_apply).isEnabled = false
                view.findViewById<MaterialButtonToggleGroup>(R.id.annotation_mode_group).isEnabled = false
                return@launch
            }
            val controller = AnnotationEditorUi(
                raster.width,
                raster.height,
            ) { java.util.UUID.randomUUID().toString() }
            editor = controller
            statusView.text = modeHint(controller.mode)
            view.findViewById<MaterialButton>(R.id.annotation_apply).isEnabled = true
            view.findViewById<AnnotationSurfaceView>(R.id.annotation_surface).apply {
                bind(controller, ::onPenEvent)
                setDisplay(
                    PageRasterBridge.toBitmap(PageRaster(raster.width, raster.height, raster.pixels)),
                    raster.width,
                    raster.height,
                )
            }
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

    // -------------------------------------------------------- pen routing

    private fun onPenEvent(action: Int, pageX: Float, pageY: Float) {
        val controller = editor ?: return
        val x = Math.round(pageX.toDouble()).toInt()
        val y = Math.round(pageY.toDouble()).toInt()
        when (action) {
            MotionEvent.ACTION_DOWN -> controller.penDown(x, y)
            MotionEvent.ACTION_MOVE -> controller.penMove(x, y)
            MotionEvent.ACTION_UP -> {
                controller.penUp()
                if (controller.mode == AnnotationEditorUi.Mode.TEXT) {
                    showTextDialog()
                } else {
                    refreshEnginePreview()
                }
            }
            MotionEvent.ACTION_CANCEL -> controller.penUp()
        }
        view?.findViewById<AnnotationSurfaceView>(R.id.annotation_surface)?.invalidate()
    }

    private fun showTextDialog() {
        val controller = editor ?: return
        val context = requireContext()
        val input = EditText(context)
        input.hint = context.getString(R.string.annotation_text_hint)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.annotation_text_title)
            .setView(input)
            .setPositiveButton(R.string.annotation_text_confirm) { _, _ ->
                if (controller.confirmText(input.text.toString()) == null) {
                    controller.clearTextAnchor()
                }
                refreshEnginePreview()
            }
            .setNegativeButton(R.string.workspace_dialog_cancel) { _, _ ->
                controller.clearTextAnchor()
                refreshEnginePreview()
            }
            .show()
    }

    // ------------------------------------------------------------ preview

    private fun refreshEnginePreview() {
        val controller = editor
        val raster = fullRaster
        if (controller == null || raster == null) return
        if (previewJob?.isActive == true) {
            previewPending = true
            return
        }
        val owner = viewLifecycleOwner
        previewJob = owner.lifecycleScope.launch(Dispatchers.IO) {
            renderEnginePreview(controller, raster, owner)
            while (previewPending) {
                previewPending = false
                renderEnginePreview(controller, raster, owner)
            }
        }
    }

    private suspend fun renderEnginePreview(
        controller: AnnotationEditorUi,
        raster: PageRaster,
        owner: androidx.lifecycle.LifecycleOwner,
    ) {
        val plan = controller.draftPreviewPlan(glyphSource)
        val rendered = AnnotationApplier.applyPlan(raster.pixels, raster.width, raster.height, plan)
        val full = PageRasterBridge.toBitmap(PageRaster(raster.width, raster.height, rendered))
        val longEdge = Math.max(full.width, full.height)
        val bitmap = if (longEdge > PageRasterBridge.PREVIEW_MAX_EDGE) {
            val scale = PageRasterBridge.PREVIEW_MAX_EDGE.toFloat() / longEdge
            val w = Math.max(1, Math.round(full.width * scale).toInt())
            val h = Math.max(1, Math.round(full.height * scale).toInt())
            Bitmap.createScaledBitmap(full, w, h, true)
        } else {
            full
        }
        owner.lifecycleScope.launch(Dispatchers.Main) {
            if (!isAdded) return@launch
            view?.findViewById<AnnotationSurfaceView>(R.id.annotation_surface)
                ?.setDisplay(bitmap, raster.width, raster.height)
        }
    }

    // -------------------------------------------------------------- apply

    private fun applyAnnotations() {
        val controller = editor
        val raster = fullRaster
        val host = view
        if (controller == null || raster == null || host == null) return
        if (!controller.hasEdits()) {
            Snackbar.make(host, R.string.annotation_nothing_to_apply, Snackbar.LENGTH_LONG).show()
            return
        }
        val button = host.findViewById<MaterialButton>(R.id.annotation_apply)
        button.isEnabled = false
        val committed = controller.sessionAnnotations()
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch(Dispatchers.IO) {
            var outcome = false
            try {
                // Edits go through the store: one add per committed annotation.
                for (annotation in committed) {
                    annotationStore.add(documentId, annotation)
                }
                val plan = controller.commitPlan(glyphSource)
                val rendered = AnnotationApplier.applyPlan(
                    raster.pixels, raster.width, raster.height, plan,
                )
                val bytes = PageRasterBridge.encodePng(
                    PageRaster(raster.width, raster.height, rendered),
                )
                val ref = contentStore.put("edited." + pageId + "." + TimeSource.SYSTEM.nowMillis(), bytes)
                val updated = repository.replacePageRaster(documentId, pageId, ref, TimeSource.SYSTEM)
                outcome = updated != null
            } catch (expected: Exception) {
                outcome = false
            }
            owner.lifecycleScope.launch(Dispatchers.Main) {
                if (!isAdded) return@launch
                if (outcome) {
                    Snackbar.make(
                        host,
                        getString(R.string.annotation_applied, committed.size),
                        Snackbar.LENGTH_LONG,
                    ).show()
                    parentFragmentManager.popBackStack()
                } else {
                    button.isEnabled = true
                    Snackbar.make(
                        host,
                        getString(
                            R.string.annotation_apply_failed,
                            getString(R.string.annotation_apply_failed_reason_page),
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

        fun forPage(
            repository: DocumentRepository,
            contentStore: ContentStore,
            annotationStore: AnnotationStore,
            documentId: String,
            pageId: String,
        ): AnnotationEditorFragment =
            AnnotationEditorFragment(repository, contentStore, annotationStore).apply {
                arguments = Bundle().apply {
                    putString(ARG_DOCUMENT_ID, documentId)
                    putString(ARG_PAGE_ID, pageId)
                }
            }
    }
}
