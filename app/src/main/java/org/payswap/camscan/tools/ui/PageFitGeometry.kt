package org.payswap.camscan.tools.ui

import org.payswap.camscan.tools.render.Point

// PageFitGeometry (CAMSCAN-VERIFY-001): pure fit-center math shared by the
// viewer tool fragments. A tool surface displays a page raster inside a
// view box using letterboxed fit-center scaling; touch coordinates must map
// between view space and page raster space. Android-free, JVM-testable.

/** Result of fitting a page raster inside a view box (fit-center). */
class PageFit(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {

    /** Maps a page raster x into view space. */
    fun pageToViewX(x: Int): Float = offsetX + x * scale

    /** Maps a page raster y into view space. */
    fun pageToViewY(y: Int): Float = offsetY + y * scale

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PageFit) return false
        return scale == other.scale && offsetX == other.offsetX && offsetY == other.offsetY
    }

    override fun hashCode(): Int = 31 * (31 * scale.toBits() + offsetX.toBits()) + offsetY.toBits()

    override fun toString(): String =
        "PageFit[scale=" + scale + ";offset=" + offsetX + "," + offsetY + "]"
}

/** Pure fit-center mapping between a view box and the page raster it shows. */
object PageFitGeometry {

    /**
     * Computes the fit-center transform of a pageWidth x pageHeight raster
     * inside a viewWidth x viewHeight box. The scale is the largest value
     * that keeps the whole page inside the box; offsets center it. Degenerate
     * dimensions return a zero-scale fit (the caller shows an honest error).
     */
    fun fit(viewWidth: Int, viewHeight: Int, pageWidth: Int, pageHeight: Int): PageFit {
        if (viewWidth <= 0 || viewHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return PageFit(0f, 0f, 0f)
        }
        val scale = Math.min(
            viewWidth.toFloat() / pageWidth.toFloat(),
            viewHeight.toFloat() / pageHeight.toFloat(),
        )
        val offsetX = (viewWidth - pageWidth * scale) / 2f
        val offsetY = (viewHeight - pageHeight * scale) / 2f
        return PageFit(scale, offsetX, offsetY)
    }

    /**
     * Maps a touch position in view space to page raster coordinates (y down,
     * integer floor). The result is NOT clamped; controllers clamp.
     */
    fun toPage(x: Float, y: Float, fit: PageFit): Point {
        if (fit.scale <= 0f) return Point(0, 0)
        val px = Math.floor((x - fit.offsetX) / fit.scale.toDouble()).toInt()
        val py = Math.floor((y - fit.offsetY) / fit.scale.toDouble()).toInt()
        return Point(px, py)
    }

    /** Maps a page raster point to view-space coordinates (integer rounded). */
    fun toView(x: Int, y: Int, fit: PageFit): Point {
        val vx = Math.round(fit.pageToViewX(x).toDouble()).toInt()
        val vy = Math.round(fit.pageToViewY(y).toDouble()).toInt()
        return Point(vx, vy)
    }
}
