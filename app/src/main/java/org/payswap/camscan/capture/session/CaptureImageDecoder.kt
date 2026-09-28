package org.payswap.camscan.capture.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.payswap.camscan.processing.BitmapImageAdapter
import org.payswap.camscan.processing.ImageBuffer
import java.io.File

/*
 * CAMSCAN-PROD-004 §6.6 (android half) — decodes a captured still file into
 * the processing domain's [ImageBuffer], UPRIGHT.
 *
 * Why EXIF: CameraX ImageCapture writes JPEGs in sensor orientation with
 * the true orientation in the EXIF tag (it does NOT rotate the pixels),
 * while the PROD-002 analyzer works on already-upright frames. Decoding
 * without applying EXIF orientation would mis-align the detection quad
 * with the capture. The framework android.media.ExifInterface is used on
 * purpose (no new dependency; the androidx variant would need one).
 *
 * Memory guard: images larger than [MAX_PIXELS] (16 MP) are power-of-two
 * down-sampled via inSampleSize — extreme-resolution captures stay
 * processable on low-heap devices. Document scans at typical capture
 * resolutions (<= 12 MP) pass through untouched.
 *
 * Flag discipline: any failure (missing/unreadable file, decode error,
 * EXIF error) returns null; the session surfaces a capture-failure toast.
 */
object CaptureImageDecoder {

    /** Documented decode ceiling before power-of-two sampling kicks in. */
    const val MAX_PIXELS = 16_000_000

    fun decode(file: File): ImageBuffer? {
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize.toLong() * (bounds.outHeight / sampleSize) > MAX_PIXELS) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
            val upright = applyExifOrientation(file, decoded)
            BitmapImageAdapter.toImageBuffer(upright)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Rotates/flips [bitmap] per the file's EXIF orientation so the result
     * is upright. The 4 plain rotations cover every CameraX output; the
     * mirrored orientations are handled with the documented matrix mapping
     * (rare in practice — cameras write plain rotations).
     */
    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (t: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // NORMAL / UNDEFINED / unknown: as-decoded
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
