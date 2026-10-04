package org.payswap.camscan.document.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

// PageRasterBridge (CAMSCAN-VERIFY-001): the thin Android seam between page
// image bytes in the ContentStore and the pure-raster tool engines. Decoding
// bakes the page's display rotation into the raster (exactly what the
// viewer displays), so tool coordinates are WYSIWYG and the persisted raster
// is stored with rotation zeroed. Encoding is lossless PNG.

/** One decoded page raster: ARGB pixels plus its dimensions. */
class PageRaster(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    val pixelCount: Int get() = pixels.size
}

/** Pure decode/encode bridge over the deterministic tool seam. */
object PageRasterBridge {

    /** Maximum long edge of the small live-preview buffers. */
    const val PREVIEW_MAX_EDGE: Int = 720

    /**
     * Decodes page image [bytes] with [rotationDegrees] baked in (right
     * angles; the viewer displays the same transform). Returns null when the
     * bytes cannot be decoded (the caller shows an honest error).
     */
    fun decode(bytes: ByteArray, rotationDegrees: Int): PageRaster? {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val oriented = if (rotationDegrees % 360 == 0) {
            decoded
        } else {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        }
        val argb = if (oriented.config == Bitmap.Config.ARGB_8888) {
            oriented
        } else {
            val converted = oriented.copy(Bitmap.Config.ARGB_8888, false)
            if (converted !== oriented) oriented.recycle()
            converted
        }
        val pixels = IntArray(argb.width * argb.height)
        argb.getPixels(pixels, 0, argb.width, 0, 0, argb.width, argb.height)
        return PageRaster(argb.width, argb.height, pixels)
    }

    /**
     * Downscales a raster (area averaging) to a preview buffer whose long
     * edge is at most [maxEdge]; returns the same raster when it is already
     * small enough. The preview is proportional, so fraction-based engine
     * placements render identically relative to the page.
     */
    fun previewRaster(raster: PageRaster, maxEdge: Int = PREVIEW_MAX_EDGE): PageRaster {
        val longEdge = Math.max(raster.width, raster.height)
        if (longEdge <= maxEdge) return raster
        val scale = maxEdge.toFloat() / longEdge
        val width = Math.max(1, Math.round(raster.width * scale))
        val height = Math.max(1, Math.round(raster.height * scale))
        val source = Bitmap.createBitmap(
            raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888,
        )
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        return PageRaster(width, height, pixels)
    }

    /** Encodes a raster as lossless PNG bytes (the persisted edit format). */
    fun encodePng(raster: PageRaster): ByteArray {
        val bitmap = Bitmap.createBitmap(
            raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888,
        )
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** A display bitmap from raster pixels (for preview ImageViews). */
    fun toBitmap(raster: PageRaster): Bitmap =
        Bitmap.createBitmap(
            raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888,
        )
}
