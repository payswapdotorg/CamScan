package org.payswap.camscan.export.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Android-side [PageImageEncoder] (CAMSCAN-PROD-007 §6.2) — the thin adapter
 * that turns stored page bytes into export JPEGs. The only android-importing
 * file in the export tree's pdf package; all decision logic (quality clamp,
 * JPEG dimension parsing) stays JVM-side.
 *
 * Two paths, both flag-disciplined (null on failure, never throws):
 * 1. **Fast path** — the stored bytes already parse as JPEG
 *    ([JpegDimensionParser]): they pass through verbatim with their SOF
 *    geometry. No lossy re-encode; byte-identical passthrough.
 * 2. **General path** — anything else (the scan pipeline stores processed
 *    pages as PNG): [BitmapFactory.decodeByteArray] decodes it
 *    (ARGB_8888 by default), then [Bitmap.compress] re-encodes it as JPEG
 *    at the constructor-configurable quality (default 85).
 *
 * Config handling: `Bitmap.compress` accepts any Bitmap config — ARGB_8888
 * and RGB_565 both feed the same 3-component YCbCr JPEG output (JPEG has no
 * alpha; it is dropped). Decoding assumes no EXIF-rotation intervention,
 * matching the viewer's display path ([BitmapFactory.decodeByteArray] there
 * applies rotation only at render time).
 */
class PageJpegEncoder(
    private val quality: Int = DEFAULT_QUALITY,
) : PageImageEncoder {

    override fun encode(storedBytes: ByteArray): EncodedJpeg? {
        if (storedBytes.isEmpty()) return null

        JpegDimensionParser.parse(storedBytes)?.let { dims ->
            return EncodedJpeg(
                bytes = storedBytes,
                widthPx = dims.widthPx,
                heightPx = dims.heightPx,
                colorComponents = dims.componentCount,
            )
        }

        val bitmap = BitmapFactory.decodeByteArray(storedBytes, 0, storedBytes.size) ?: return null
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, clampJpegQuality(quality), out)) {
            return null
        }
        return EncodedJpeg(
            bytes = out.toByteArray(),
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            colorComponents = EncodedJpeg.COLOR_COMPONENTS_RGB,
        )
    }

    companion object {
        /** Packet-specified default JPEG quality for exports. */
        const val DEFAULT_QUALITY = 85
    }
}
