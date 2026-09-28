package org.payswap.camscan.processing


import android.graphics.Bitmap

/*
 * CAMSCAN-PROD-003 §6.5 — the thin Android adapter (the ONLY file in the
 * processing tree with android.* imports; §6.1-§6.4 + §6.7 stay
 * android-free). No UI, no Activity/Fragment imports.
 *
 * Both directions are synchronous CPU copies (getPixels/setPixels,
 * row-major, ARGB_8888); the CALLER owns threading — run on a background
 * executor, never on the main thread.
 *
 * Preconditions (documented): toImageBuffer accepts any Bitmap
 * (getPixels converts to ARGB ints); toBitmap requires a WELL-FORMED
 * buffer — [android.graphics.Bitmap] itself rejects mismatched pixel
 * arrays, so the adapter surfaces that as IllegalArgumentException
 * (glue discipline differs from the pure engine's flag discipline).
 */
object BitmapImageAdapter {

    /** Bitmap -> packed ARGB [ImageBuffer] (one full copy). */
    fun toImageBuffer(bitmap: Bitmap): ImageBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return ImageBuffer(width, height, pixels)
    }

    /** Packed ARGB [ImageBuffer] -> ARGB_8888 [Bitmap] (one full copy). */
    fun toBitmap(buffer: ImageBuffer): Bitmap {
        require(buffer.isWellFormed) {
            "ImageBuffer must be well-formed (width*height == argb.size); was " +
                "${buffer.width}x${buffer.height} with ${buffer.argb.size} pixels"
        }
        val bitmap = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(buffer.argb, 0, buffer.width, 0, 0, buffer.width, buffer.height)
        return bitmap
    }
}
