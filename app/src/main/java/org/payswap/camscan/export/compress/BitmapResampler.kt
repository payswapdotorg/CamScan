package org.payswap.camscan.export.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Android [ImageResampler] (CAMSCAN-PROD-008 §6.5) — the thin bitmap
 * adapter. All DECISION logic (whether to resample, target dims, quality)
 * is JVM-side; this class only executes: decode with an inSampleSize whose
 * decoded dims never fall below the target, scale to the exact target dims,
 * re-encode JPEG. Null on any decode/encode failure — never throws, and
 * the caller never substitutes the original bytes for the failure.
 */
class BitmapResampler : ImageResampler {

    override fun resample(
        jpegBytes: ByteArray,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: Int,
    ): ResampledJpeg? {
        if (jpegBytes.isEmpty() || targetWidthPx <= 0 || targetHeightPx <= 0) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidthPx, targetHeightPx)
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
            ?: return null
        val scaled = if (decoded.width == targetWidthPx && decoded.height == targetHeightPx) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidthPx, targetHeightPx, true)
        }
        try {
            val out = ByteArrayOutputStream()
            val wrote = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return if (wrote) {
                ResampledJpeg(
                    jpegBytes = out.toByteArray(),
                    widthPx = scaled.width,
                    heightPx = scaled.height,
                )
            } else {
                null
            }
        } finally {
            if (scaled !== decoded) {
                scaled.recycle()
            }
            decoded.recycle()
        }
    }

    /**
     * Largest power-of-two inSampleSize whose decoded dims still cover the
     * target, so the exact-fit scale step only ever SHRINKS and the decode
     * itself stays cheap for huge pages.
     */
    private fun sampleSizeFor(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }
}
