package org.payswap.camscan.export.conversion

import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.imports.AndroidBoundsDecoder
import org.payswap.camscan.ocr.engine.OcrEngine
import org.payswap.camscan.ocr.engine.OcrImage
import org.payswap.camscan.ocr.engine.OcrImageFormat
import org.payswap.camscan.ocr.engine.OcrOutcome
import org.payswap.camscan.ocr.engine.OcrSettings
import org.payswap.camscan.ocr.engine.catalog.OcrEngineCatalog
import org.payswap.camscan.ocr.engine.catalog.OcrEnginePolicy

// CAMSCAN-VERIFY-002 — production [PageTextSource] over the OCR engine
// catalog (PROD-014's documented MainActivity integration point,
// consumed here for the conversion flow only — one engine instance per
// export run, closed by the same run). Line rule (documented): each
// recognized block contributes its text split on newlines, in the
// block list's reading order; empty segments are preserved (the
// conversion layer's paragraph segmentation owns blank-line meaning).
// Recognition failures (including the unavailable-engine value) map to
// null — the honest "no text" signal, never a fake success.

/** ML Kit-backed [PageTextSource] for the Office conversion flow. */
class MlKitPageTextSource(
    timeSource: TimeSource,
) : PageTextSource {

    private val boundsDecoder = AndroidBoundsDecoder()
    private val engine: OcrEngine = OcrEngineCatalog
        .default(timeSource)
        .create(OcrEnginePolicy.PREFER_LIVE)

    override suspend fun textLinesFor(
        pageBytes: ByteArray,
        rotationDegrees: Int,
    ): List<String>? {
        if (pageBytes.isEmpty()) return null
        val bounds = boundsDecoder.decodeBounds(pageBytes) ?: return null
        val image = OcrImage(
            bytes = pageBytes,
            width = bounds.widthPx,
            height = bounds.heightPx,
            format = OcrImageFormat.UNKNOWN,
            rotationDegrees = rotationDegrees,
        ).normalized()
        return when (val outcome = engine.recognize(image, OcrSettings.DEFAULT)) {
            is OcrOutcome.Failure -> null
            is OcrOutcome.Success -> {
                val lines = mutableListOf<String>()
                for (block in outcome.result.blocks) {
                    val text = block.text
                    if (text.isEmpty()) {
                        lines.add(text)
                    } else {
                        for (segment in text.split('\n')) {
                            lines.add(segment)
                        }
                    }
                }
                lines
            }
        }
    }

    override fun close() {
        engine.close()
    }
}
