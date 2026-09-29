package org.payswap.camscan.imports

import android.graphics.BitmapFactory
import org.payswap.camscan.export.pdf.JpegDimensionParser

/**
 * Android [BoundsDecoder] (CAMSCAN-PROD-008 §6.1) with inJustDecodeBounds
 * semantics: geometry only, never a decoded bitmap.
 *
 * JPEG bytes take the pure [JpegDimensionParser] fast path (a SOF header
 * scan — no decode at all, reusing PROD-007's delivered parser verbatim);
 * anything else decodes through BitmapFactory's inJustDecodeBounds flag.
 * Null on undecodable input — flag discipline, never throws.
 */
class AndroidBoundsDecoder : BoundsDecoder {

    override fun decodeBounds(bytes: ByteArray): ImageBounds? {
        if (bytes.isEmpty()) return null

        JpegDimensionParser.parse(bytes)?.let { dims ->
            return ImageBounds(widthPx = dims.widthPx, heightPx = dims.heightPx)
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            ImageBounds(widthPx = options.outWidth, heightPx = options.outHeight)
        } else {
            null
        }
    }
}
