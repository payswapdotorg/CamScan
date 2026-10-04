package org.payswap.camscan.document.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import org.payswap.camscan.R
import org.payswap.camscan.tools.signature.SignatureStore
import org.payswap.camscan.tools.signature.SignatureStroke
import org.payswap.camscan.tools.ui.SignaturePadUi

// SignaturePadFragment (CAMSCAN-VERIFY-001): full-screen signature pad
// overlay. Touch strokes stream through SignaturePadUi into the frozen
// SignaturePadReducer; the pad renders collected ink live; saving an empty
// pad is rejected with a visible message; a valid sketch is stored through
// SignatureStore. The UI layer stays thin: all capture/validation logic is
// the pure controller.

/**
 * Live stroke canvas: routes touch events into [SignaturePadUi] and paints
 * finished plus in-progress strokes as they are collected.
 */
class SignaturePadCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var controller: SignaturePadUi? = null
    private var onInkChanged: (() -> Unit)? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Binds the pad controller plus the ink-changed callback. */
    fun bind(controller: SignaturePadUi, onInkChanged: () -> Unit) {
        this.controller = controller
        this.onInkChanged = onInkChanged
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pad = controller ?: return false
        val x = Math.round(event.x.toDouble()).toInt()
        val y = Math.round(event.y.toDouble()).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pad.penDown(x, y)
            }
            MotionEvent.ACTION_MOVE -> pad.penMove(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pad.penUp()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
            }
            else -> return true
        }
        onInkChanged?.invoke()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = controller ?: return
        val state = pad.state
        val strokes = ArrayList<SignatureStroke>(state.finishedStrokes.size + 1)
        strokes.addAll(state.finishedStrokes)
        state.inProgress?.let { strokes.add(it) }
        for (stroke in strokes) {
            strokePaint.color = stroke.colorArgb.toInt()
            strokePaint.strokeWidth = Math.max(1f, stroke.strokeWidthPx.toFloat())
            if (stroke.points.size == 1) {
                canvas.drawPoint(
                    stroke.points[0].x.toFloat(),
                    stroke.points[0].y.toFloat(),
                    strokePaint,
                )
            } else {
                var previous = stroke.points[0]
                for (index in 1 until stroke.points.size) {
                    val current = stroke.points[index]
                    canvas.drawLine(
                        previous.x.toFloat(),
                        previous.y.toFloat(),
                        current.x.toFloat(),
                        current.y.toFloat(),
                        strokePaint,
                    )
                    previous = current
                }
            }
        }
    }
}

/** The signature pad overlay fragment (constructor-injected store). */
class SignaturePadFragment(
    private val signatureStore: SignatureStore,
) : Fragment(R.layout.fragment_signature_pad) {

    private val documentId: String
        get() = arguments?.getString(ARG_DOCUMENT_ID).orEmpty()

    private val padUi = SignaturePadUi()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toolbar = view.findViewById<Toolbar>(R.id.viewer_pad_toolbar)
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        toolbar.navigationContentDescription = context?.getString(R.string.workspace_viewer_back_cd)

        val nameView = view.findViewById<EditText>(R.id.viewer_pad_name)
        nameView.setText(getString(R.string.viewer_pad_default_name, signatureStore.size + 1))

        val warningView = view.findViewById<TextView>(R.id.viewer_pad_warning)

        view.findViewById<SignaturePadCanvasView>(R.id.viewer_pad_surface).apply {
            bind(padUi) { warningView.visibility = View.GONE }
        }

        view.findViewById<MaterialButton>(R.id.viewer_pad_save).apply {
            contentDescription = context.getString(R.string.viewer_pad_save_cd)
            setOnClickListener { saveSketch(warningView, nameView) }
        }
        view.findViewById<MaterialButton>(R.id.viewer_pad_clear).apply {
            contentDescription = context.getString(R.string.viewer_pad_clear_cd)
            setOnClickListener {
                padUi.clear()
                view.findViewById<SignaturePadCanvasView>(R.id.viewer_pad_surface).invalidate()
                warningView.visibility = View.GONE
            }
        }
        view.findViewById<MaterialButton>(R.id.viewer_pad_cancel).apply {
            setOnClickListener { parentFragmentManager.popBackStack() }
        }
    }

    private fun saveSketch(warningView: TextView, nameView: EditText) {
        if (!padUi.hasInk()) {
            warningView.text = getString(R.string.viewer_pad_empty_reject)
            warningView.visibility = View.VISIBLE
            return
        }
        val name = nameView.text.toString()
        if (!SignaturePadUi.validName(name)) {
            warningView.text = getString(R.string.viewer_pad_name_invalid)
            warningView.visibility = View.VISIBLE
            return
        }
        signatureStore.add(name.trim(), padUi.sketch())
        Toast.makeText(requireContext(), R.string.viewer_pad_saved, Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ARG_DOCUMENT_ID = "arg_document_id"

        fun forDocument(store: SignatureStore, documentId: String): SignaturePadFragment =
            SignaturePadFragment(store).apply {
                arguments = Bundle().apply { putString(ARG_DOCUMENT_ID, documentId) }
            }
    }
}
