package org.payswap.camscan.tools.signature

import org.payswap.camscan.tools.render.DrawOp
import org.payswap.camscan.tools.render.DrawPlan
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect

// Signature model (CAMSCAN-PROD-011 section 6.2): strokes and sketches as
// pure data with value semantics, convertible to a DrawPlan for rendering.

/** One signature stroke: an ordered polyline with a pen width and color. */
class SignatureStroke(
    val points: List<Point>,
    val strokeWidthPx: Int,
    val colorArgb: Long,
) {

    init {
        require(strokeWidthPx >= 0) { "strokeWidthPx must be non-negative" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignatureStroke) return false
        return points == other.points && strokeWidthPx == other.strokeWidthPx &&
            colorArgb == other.colorArgb
    }

    override fun hashCode(): Int = 31 * (31 * points.hashCode() + strokeWidthPx) + colorArgb.hashCode()

    override fun toString(): String =
        "SignatureStroke[points=" + points.size + ";width=" + strokeWidthPx +
            ";color=" + org.payswap.camscan.tools.render.Argb.toHex(colorArgb) + "]"
}

 // An ordered collection of strokes forming one signature. The bounding box
 // is computed from the stroke POINTS only (stroke width may paint outside
 // it) and is null for an empty sketch (documented).
 // /
class SignatureSketch(val strokes: List<SignatureStroke>) {

    val isEmpty: Boolean get() = strokes.isEmpty()

    /** Stable bounding box over all stroke points, or null when empty. */
    val boundingBox: Rect? = computeBoundingBox(strokes)

    /** Converts this sketch into a DrawPlan of Stroke ops in stroke order. */
    fun toDrawPlan(): DrawPlan {
        val ops = ArrayList<DrawOp.Stroke>(strokes.size)
        for (stroke in strokes) {
            ops.add(DrawOp.Stroke(stroke.points, stroke.strokeWidthPx, stroke.colorArgb))
        }
        return DrawPlan(ops)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignatureSketch) return false
        return strokes == other.strokes
    }

    override fun hashCode(): Int = strokes.hashCode()

    override fun toString(): String = "SignatureSketch[strokes=" + strokes.size + "]"

    companion object {
        /** Computes the tight box over all points, or null when no points exist. */
        fun computeBoundingBox(strokes: List<SignatureStroke>): Rect? {
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            var any = false
            for (stroke in strokes) {
                for (point in stroke.points) {
                    any = true
                    if (point.x < minX) minX = point.x
                    if (point.y < minY) minY = point.y
                    if (point.x > maxX) maxX = point.x
                    if (point.y > maxY) maxY = point.y
                }
            }
            if (!any) return null
            return Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
        }
    }
}
