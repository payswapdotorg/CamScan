package org.payswap.camscan.tools.signature

import org.payswap.camscan.tools.render.DrawPlan
import org.payswap.camscan.tools.render.DrawOp
import org.payswap.camscan.tools.render.DrawSurface
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect

// SignatureApplier (CAMSCAN-PROD-011 section 6.2): renders a sketch onto a
// page buffer at a chosen placement. Non-mutating discipline: the input
// buffer is NEVER modified; a new buffer is returned.
//
// Placement math (documented, binding):
//   - targetWidthPx = round(pageWidth * widthFraction) (Math.round,
//     deterministic half-up);
//   - scale = targetWidthPx / max(1, sketchBoundingBox.width) (uniform,
//     IEEE double division - deterministic);
//   - every point is scaled with Math.round(point * scale) and the stroke
//     width with max(1, Math.round(width * scale));
//   - the scaled ink box is recomputed from the scaled points and anchored
//     at the chosen corner with the given margin, clamped so the box stays
//     inside the page;
//   - an empty sketch returns a plain copy of the page.

/** Page corner the signature is anchored to. */
enum class AnchorCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

 // Placement of a signature on a page: the anchor corner, the signature's
 // target width as a fraction of the page width (0 < fraction <= 1), and
 // the margin in pixels between the anchor corner and the scaled ink box.
 // /
class SignaturePlacement(
    val anchor: AnchorCorner,
    val widthFraction: Float,
    val marginPx: Int,
) {

    init {
        require(widthFraction > 0.0f && widthFraction <= 1.0f) {
            "widthFraction must be in (0, 1]"
        }
        require(marginPx >= 0) { "marginPx must be non-negative" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignaturePlacement) return false
        return anchor == other.anchor && widthFraction == other.widthFraction &&
            marginPx == other.marginPx
    }

    override fun hashCode(): Int = 31 * (31 * anchor.hashCode() + widthFraction.toBits()) + marginPx

    override fun toString(): String =
        "SignaturePlacement[anchor=" + anchor + ";fraction=" + widthFraction +
            ";margin=" + marginPx + "]"
}

/** Pure sketch-on-page renderer. */
object SignatureApplier {

    /** Applies [sketch] to a copy of the page buffer at [placement]. */
    fun apply(
        page: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        sketch: SignatureSketch,
        placement: SignaturePlacement,
    ): IntArray {
        require(page.size == pageWidth * pageHeight) { "page buffer size mismatch" }
        val originalBox = sketch.boundingBox ?: return page.copyOf()
        val targetWidthPx = Math.round(pageWidth * placement.widthFraction)
        val scale = targetWidthPx.toDouble() / Math.max(1, originalBox.width)

        val scaledStrokes = ArrayList<SignatureStroke>(sketch.strokes.size)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (stroke in sketch.strokes) {
            val scaledWidth = Math.max(1, Math.round(stroke.strokeWidthPx * scale).toInt())
            val scaledPoints = ArrayList<Point>(stroke.points.size)
            for (point in stroke.points) {
                val sx = Math.round(point.x * scale).toInt()
                val sy = Math.round(point.y * scale).toInt()
                if (sx < minX) minX = sx
                if (sy < minY) minY = sy
                if (sx > maxX) maxX = sx
                if (sy > maxY) maxY = sy
                scaledPoints.add(Point(sx, sy))
            }
            scaledStrokes.add(SignatureStroke(scaledPoints, scaledWidth, stroke.colorArgb))
        }
        val scaledBox = Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)

        val destX: Int
        val destY: Int
        val clampedWidth = Math.min(scaledBox.width, pageWidth)
        val clampedHeight = Math.min(scaledBox.height, pageHeight)
        when (placement.anchor) {
            AnchorCorner.TOP_LEFT -> {
                destX = Math.min(placement.marginPx, pageWidth - clampedWidth)
                destY = Math.min(placement.marginPx, pageHeight - clampedHeight)
            }
            AnchorCorner.TOP_RIGHT -> {
                destX = Math.max(0, pageWidth - placement.marginPx - clampedWidth)
                destY = Math.min(placement.marginPx, pageHeight - clampedHeight)
            }
            AnchorCorner.BOTTOM_LEFT -> {
                destX = Math.min(placement.marginPx, pageWidth - clampedWidth)
                destY = Math.max(0, pageHeight - placement.marginPx - clampedHeight)
            }
            AnchorCorner.BOTTOM_RIGHT -> {
                destX = Math.max(0, pageWidth - placement.marginPx - clampedWidth)
                destY = Math.max(0, pageHeight - placement.marginPx - clampedHeight)
            }
        }

        val ops = ArrayList<DrawOp.Stroke>(scaledStrokes.size)
        for (stroke in scaledStrokes) {
            val translated = ArrayList<Point>(stroke.points.size)
            for (point in stroke.points) {
                translated.add(
                    Point(
                        point.x - scaledBox.x + destX,
                        point.y - scaledBox.y + destY,
                    ),
                )
            }
            ops.add(DrawOp.Stroke(translated, stroke.strokeWidthPx, stroke.colorArgb))
        }

        val surface = DrawSurface(pageWidth, pageHeight)
        return surface.render(page, DrawPlan(ops))
    }
}
