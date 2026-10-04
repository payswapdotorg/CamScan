package org.payswap.camscan.tools.ui

import org.payswap.camscan.tools.annotation.Annotation
import org.payswap.camscan.tools.annotation.toDrawPlan
import org.payswap.camscan.tools.render.DrawOp
import org.payswap.camscan.tools.render.DrawPlan
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect
import org.payswap.camscan.tools.render.TextGlyphSource
import org.payswap.camscan.tools.signature.PadEvent
import org.payswap.camscan.tools.signature.PadState
import org.payswap.camscan.tools.signature.SignaturePadReducer
import org.payswap.camscan.tools.signature.SignatureStroke

// AnnotationEditorUi (CAMSCAN-VERIFY-001): pure controller behind the
// annotation overlay. Touch gestures in full-resolution page coordinates
// become draft annotations (Ink per gesture, Highlight rectangle,
// TextNote tap + text) through the frozen engine models; the plan handed to
// AnnotationApplier is built from the SAME ops the engine defines, so the
// preview and the applied raster cannot drift. Android-free, JVM-testable.

/** Mutable draft state for one annotation editing session on one page. */
class AnnotationEditorUi(
    private val pageWidth: Int,
    private val pageHeight: Int,
    private val newAnnotationId: () -> String,
) {

    /** Active tool mode. */
    enum class Mode { INK, HIGHLIGHT, TEXT }

    var mode: Mode = Mode.INK
        private set

    private val inkReducer = SignaturePadReducer()
    private var inkState: PadState = PadState.CLEAN
    private val annotations = ArrayList<Annotation>()

    private var highlightStart: Point? = null
    private var highlightDraft: Rect? = null

    private var textAnchor: Point? = null

    /** Selects the tool mode; drafts of other modes are kept. */
    fun selectMode(next: Mode) {
        mode = next
    }

    /**
     * Pen down in page coordinates (mode dependent): starts an ink stroke,
     * a highlight rectangle, or positions a text note anchor.
     */
    fun penDown(x: Int, y: Int) {
        val px = clampX(x)
        val py = clampY(y)
        when (mode) {
            Mode.INK -> inkState = inkReducer.reduce(PadEvent.Down(Point(px, py)), inkState)
            Mode.HIGHLIGHT -> {
                highlightStart = Point(px, py)
                highlightDraft = Rect(px, py, 0, 0)
            }
            Mode.TEXT -> textAnchor = Point(px, py)
        }
    }

    /** Pen move in page coordinates (mode dependent). */
    fun penMove(x: Int, y: Int) {
        val px = clampX(x)
        val py = clampY(y)
        when (mode) {
            Mode.INK -> inkState = inkReducer.reduce(PadEvent.Move(Point(px, py)), inkState)
            Mode.HIGHLIGHT -> {
                val start = highlightStart ?: return
                highlightDraft = normalizedRect(start, Point(px, py))
            }
            Mode.TEXT -> Unit
        }
    }

    /**
     * Pen up: in INK mode the finished gesture stroke becomes one Ink
     * annotation; in HIGHLIGHT mode the draft rectangle is finalized (a
     * degenerate rect is discarded — a tap is not a highlight); TEXT mode
     * keeps the anchor until [confirmText].
     */
    fun penUp() {
        when (mode) {
            Mode.INK -> {
                inkState = inkReducer.reduce(PadEvent.Up, inkState)
                val finished = inkState.finishedStrokes
                if (finished.isNotEmpty()) {
                    val stroke = finished[finished.size - 1]
                    annotations.add(
                        Annotation.Ink(listOf(stroke), newAnnotationId(), annotations.size),
                    )
                }
            }
            Mode.HIGHLIGHT -> {
                val draft = highlightDraft
                if (draft != null && draft.width > 0 && draft.height > 0) {
                    annotations.add(
                        Annotation.Highlight(draft, HIGHLIGHT_COLOR, newAnnotationId(), annotations.size),
                    )
                }
                highlightStart = null
                highlightDraft = null
            }
            Mode.TEXT -> Unit
        }
    }

    /**
     * Confirms the pending text note at the last tap anchor; null (and no
     * annotation) when no anchor exists or the trimmed text is empty.
     */
    fun confirmText(text: String): Annotation.TextNote? {
        val anchor = textAnchor
        val trimmed = text.trim()
        if (anchor == null || trimmed.isEmpty()) return null
        val note = Annotation.TextNote(trimmed, anchor, TEXT_COLOR, newAnnotationId(), annotations.size)
        annotations.add(note)
        textAnchor = null
        return note
    }

    /** Removes the most recent annotation; false when none exist. */
    fun undoLast(): Boolean {
        if (annotations.isEmpty()) return false
        annotations.removeAt(annotations.size - 1)
        return true
    }

    /** Session annotations in commit order (copy). */
    fun sessionAnnotations(): List<Annotation> = annotations.toList()

    /** Number of annotations drafted this session. */
    fun annotationCount(): Int = annotations.size

    /** In-progress ink polyline (for the live layer), or null. */
    fun inProgressStroke(): SignatureStroke? = inkState.inProgress

    /** Current highlight draft rectangle (live layer), or null. */
    fun highlightDraftRect(): Rect? = highlightDraft

    /** Pending text anchor (live layer marker), or null. */
    fun pendingTextAnchor(): Point? = textAnchor

    /** Clears a pending text anchor (dialog dismissed without text). */
    fun clearTextAnchor() {
        textAnchor = null
    }

    /** True when the session has anything to apply. */
    fun hasEdits(): Boolean = annotations.isNotEmpty()

    /**
     * The DrawPlan to render for the live preview: committed session ops
     * plus the in-progress ink stroke plus the highlight draft shown as a
     * translucent rect (the final op is the engine's multiply form).
     */
    fun draftPreviewPlan(glyphSource: TextGlyphSource): DrawPlan {
        val ops = ArrayList<DrawOp>()
        ops.addAll(sessionAnnotations().toDrawPlan(glyphSource).ops)
        inProgressStroke()?.let { stroke ->
            ops.add(DrawOp.Stroke(stroke.points, stroke.strokeWidthPx, stroke.colorArgb))
        }
        highlightDraftRect()?.let { rect ->
            ops.add(DrawOp.BlendRect(rect, HIGHLIGHT_COLOR, DRAFT_HIGHLIGHT_ALPHA))
        }
        return DrawPlan(ops)
    }

    /**
     * The plan handed to AnnotationApplier for the final page raster: only
     * the session's committed annotations, as the engine extension orders
     * them.
     */
    fun commitPlan(glyphSource: TextGlyphSource): DrawPlan = sessionAnnotations().toDrawPlan(glyphSource)

    private fun clampX(x: Int): Int = Math.max(0, Math.min(x, pageWidth - 1))

    private fun clampY(y: Int): Int = Math.max(0, Math.min(y, pageHeight - 1))

    private fun normalizedRect(start: Point, end: Point): Rect {
        val left = Math.min(start.x, end.x)
        val top = Math.min(start.y, end.y)
        val right = Math.max(start.x, end.x) + 1
        val bottom = Math.max(start.y, end.y) + 1
        val clampedRight = Math.min(right, pageWidth)
        val clampedBottom = Math.min(bottom, pageHeight)
        return Rect(left, top, Math.max(0, clampedRight - left), Math.max(0, clampedBottom - top))
    }

    companion object {
        /** Highlighter color: yellow multiply (keeps R+G, zeroes B on white). */
        const val HIGHLIGHT_COLOR: Long = 0xFFFFFF00L

        /** Text note color: opaque black on the engine's white background. */
        const val TEXT_COLOR: Long = 0xFF000000L

        /** Translucent preview alpha for the highlight draft rectangle. */
        const val DRAFT_HIGHLIGHT_ALPHA: Int = 96
    }
}
