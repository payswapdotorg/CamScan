package org.payswap.camscan.tools.render

// DrawPlan: the single pure-raster drawing seam every visual tool renders
// through (CAMSCAN-PROD-011 section 6.1). A plan is an immutable ordered
// list of raster draw ops; DrawSurface executes it deterministically.
//
// Serialized form: every op has a stable toString and the plan joins the op
// forms with newlines inside brackets, so equal plans always produce equal
// serialized text (used by hash/equality tests and future persistence).
//
// Op set: Stroke, FillRect, BlitGlyphs, BlendRect are the ops named by the
// work order. MultiplyRect is an additional op added because the
// annotation Highlight semantics (section 6.3) require an integer multiply
// blend that alpha compositing cannot express; this is an internal
// extension of this NEW tree (no shared prior contract is involved) and is
// reported in the delivery notes.

/** Sealed raster draw op vocabulary executed by [DrawSurface]. */
sealed class DrawOp {

    /** Polyline stroke rasterized with Bresenham segments and a square brush. */
    class Stroke(
        val points: List<Point>,
        val widthPx: Int,
        val colorArgb: Long,
    ) : DrawOp() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stroke) return false
            return points == other.points && widthPx == other.widthPx && colorArgb == other.colorArgb
        }

        override fun hashCode(): Int = 31 * (31 * points.hashCode() + widthPx) + colorArgb.hashCode()

        override fun toString(): String {
            val sb = StringBuilder("Stroke[points=")
            for (index in points.indices) {
                if (index > 0) sb.append(" ")
                sb.append(points[index].toString())
            }
            sb.append(";width=").append(widthPx)
            sb.append(";color=").append(Argb.toHex(colorArgb)).append("]")
            return sb.toString()
        }
    }

    /** Opaque (source-over) axis-aligned rectangle fill. */
    class FillRect(
        val rect: Rect,
        val colorArgb: Long,
    ) : DrawOp() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FillRect) return false
            return rect == other.rect && colorArgb == other.colorArgb
        }

        override fun hashCode(): Int = 31 * rect.hashCode() + colorArgb.hashCode()

        override fun toString(): String =
            "FillRect[rect=" + rect + ";color=" + Argb.toHex(colorArgb) + "]"
    }

    /** Text rendered through the glyph source identified by [glyphSourceId]. */
    class BlitGlyphs(
        val text: String,
        val origin: Point,
        val glyphSourceId: String,
        val colorArgb: Long,
    ) : DrawOp() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BlitGlyphs) return false
            return text == other.text && origin == other.origin &&
                glyphSourceId == other.glyphSourceId && colorArgb == other.colorArgb
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = 31 * result + origin.hashCode()
            result = 31 * result + glyphSourceId.hashCode()
            result = 31 * result + colorArgb.hashCode()
            return result
        }

        override fun toString(): String =
            "BlitGlyphs[text=" + text + ";origin=" + origin +
                ";source=" + glyphSourceId + ";color=" + Argb.toHex(colorArgb) + "]"
    }

    /** Alpha-composited overlay rectangle; [alpha] (0..255) is the source alpha. */
    class BlendRect(
        val rect: Rect,
        val colorArgb: Long,
        val alpha: Int,
    ) : DrawOp() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BlendRect) return false
            return rect == other.rect && colorArgb == other.colorArgb && alpha == other.alpha
        }

        override fun hashCode(): Int = 31 * (31 * rect.hashCode() + colorArgb.hashCode()) + alpha

        override fun toString(): String =
            "BlendRect[rect=" + rect + ";color=" + Argb.toHex(colorArgb) +
                ";alpha=" + alpha + "]"
    }

    /** Per-channel integer multiply blend (highlighter semantics). */
    class MultiplyRect(
        val rect: Rect,
        val colorArgb: Long,
    ) : DrawOp() {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MultiplyRect) return false
            return rect == other.rect && colorArgb == other.colorArgb
        }

        override fun hashCode(): Int = 31 * rect.hashCode() + colorArgb.hashCode()

        override fun toString(): String =
            "MultiplyRect[rect=" + rect + ";color=" + Argb.toHex(colorArgb) + "]"
    }
}

 // Immutable ordered list of draw ops. Value semantics over the op list;
 // the serialized form is stable for equal plans.
 // /
class DrawPlan(ops: List<DrawOp>) {

    /** Defensive copy of the ops, never null, never externally mutable. */
    val ops: List<DrawOp> = ops.toList()

    val isEmpty: Boolean get() = ops.isEmpty()

    /** A new plan with [op] appended (returns a new instance; this is immutable). */
    fun plus(op: DrawOp): DrawPlan = DrawPlan(ops + op)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DrawPlan) return false
        return ops == other.ops
    }

    override fun hashCode(): Int = ops.hashCode()

    override fun toString(): String {
        val sb = StringBuilder("DrawPlan[")
        sb.append(ops.size).append(" ops]")
        return sb.toString() + serialized()
    }

    /** Newline-joined op forms (the stable serialized representation). */
    fun serialized(): String {
        val sb = StringBuilder()
        for (index in ops.indices) {
            if (index > 0) sb.append("\n")
            sb.append(ops[index].toString())
        }
        return sb.toString()
    }

    companion object {
        val EMPTY: DrawPlan = DrawPlan(emptyList())
    }
}
