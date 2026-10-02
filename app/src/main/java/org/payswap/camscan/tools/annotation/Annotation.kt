package org.payswap.camscan.tools.annotation

import org.payswap.camscan.tools.render.DrawOp
import org.payswap.camscan.tools.render.DrawPlan
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect
import org.payswap.camscan.tools.render.TextGlyphSource
import org.payswap.camscan.tools.signature.SignatureStroke

// Annotation model (CAMSCAN-PROD-011 section 6.3): per-document annotations
// with a total, deterministic z order. Rendering semantics (documented):
//   - Ink: freehand strokes rendered exactly like signature ink;
//   - Highlight: an integer per-channel MULTIPLY blend (darkening) over the
//     rect - a yellow highlight over white keeps R and G and zeroes B;
//   - TextNote: a BlitGlyphs op with an opaque white background FillRect
//     sized measure(text) + 1 px padding on every side (legibility over
//     any page content), drawn BEFORE the glyphs.

/** Base type: stable id plus the z position inside its document. */
sealed class Annotation {

    abstract val annotationId: String

    abstract val zIndex: Int

    /** Freehand ink annotation. */
    class Ink(
        val strokes: List<SignatureStroke>,
        override val annotationId: String,
        override val zIndex: Int,
    ) : Annotation() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ink) return false
            return strokes == other.strokes && annotationId == other.annotationId &&
                zIndex == other.zIndex
        }

        override fun hashCode(): Int = 31 * (31 * strokes.hashCode() + annotationId.hashCode()) + zIndex

        override fun toString(): String =
            "Annotation.Ink[id=" + annotationId + ";z=" + zIndex + ";strokes=" + strokes.size + "]"
    }

    /** Highlighter rectangle (multiply blend). */
    class Highlight(
        val rect: Rect,
        val colorArgb: Long,
        override val annotationId: String,
        override val zIndex: Int,
    ) : Annotation() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Highlight) return false
            return rect == other.rect && colorArgb == other.colorArgb &&
                annotationId == other.annotationId && zIndex == other.zIndex
        }

        override fun hashCode(): Int = 31 * (31 * rect.hashCode() + colorArgb.hashCode()) + zIndex

        override fun toString(): String =
            "Annotation.Highlight[id=" + annotationId + ";z=" + zIndex + ";rect=" + rect + "]"
    }

    /** Short text note anchored at a point, rendered with the 5x7 font. */
    class TextNote(
        val text: String,
        val anchor: Point,
        val colorArgb: Long,
        override val annotationId: String,
        override val zIndex: Int,
    ) : Annotation() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TextNote) return false
            return text == other.text && anchor == other.anchor && colorArgb == other.colorArgb &&
                annotationId == other.annotationId && zIndex == other.zIndex
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = 31 * result + anchor.hashCode()
            result = 31 * result + colorArgb.hashCode()
            result = 31 * result + annotationId.hashCode()
            result = 31 * result + zIndex
            return result
        }

        override fun toString(): String =
            "Annotation.TextNote[id=" + annotationId + ";z=" + zIndex + ";text=" + text + "]"
    }

    /** Renders this annotation's ops in draw order (no z logic here). */
    fun toOps(glyphSource: TextGlyphSource): List<DrawOp> = when (this) {
        is Ink -> {
            val ops = ArrayList<DrawOp.Stroke>(strokes.size)
            for (stroke in strokes) {
                ops.add(DrawOp.Stroke(stroke.points, stroke.strokeWidthPx, stroke.colorArgb))
            }
            ops
        }
        is Highlight -> listOf(DrawOp.MultiplyRect(rect, colorArgb))
        is TextNote -> {
            val ops = ArrayList<DrawOp>(2)
            val measured = glyphSource.measure(text)
            ops.add(
                DrawOp.FillRect(
                    Rect(
                        anchor.x - PADDING_PX,
                        anchor.y - PADDING_PX,
                        measured.width + 2 * PADDING_PX,
                        measured.height + 2 * PADDING_PX,
                    ),
                    BACKGROUND_COLOR,
                ),
            )
            ops.add(DrawOp.BlitGlyphs(text, anchor, glyphSource.id, colorArgb))
            ops
        }
    }

    companion object {
        /** TextNote background padding on every side (documented). */
        const val PADDING_PX: Int = 1

        /** TextNote background color: opaque white for legibility. */
        const val BACKGROUND_COLOR: Long = 0xFFFFFFFFL
    }
}

/** Merges annotations (already z ordered) into one DrawPlan. */
fun List<Annotation>.toDrawPlan(glyphSource: TextGlyphSource): DrawPlan {
    val ops = ArrayList<DrawOp>()
    for (annotation in this) {
        ops.addAll(annotation.toOps(glyphSource))
    }
    return DrawPlan(ops)
}
