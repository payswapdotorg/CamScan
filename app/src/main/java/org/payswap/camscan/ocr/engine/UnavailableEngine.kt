package org.payswap.camscan.ocr.engine

/**

The "OCR never blocks the scan path" embodiment.

Wire this in wherever an [OcrEngine] is required and the app stays fully

functional: every recognition attempt deterministically reports

[OcrFailure.ENGINE_ERROR]("ocr unavailable") as a VALUE — it never throws,

never blocks, never touches the clock, and holds no resources, so [close]

is a no-op. Scanning and saving work unchanged with this engine wired in.
*/
class UnavailableEngine : OcrEngine {

override val engineId: String = "unavailable"

override suspend fun recognize(image: OcrImage, settings: OcrSettings): OcrOutcome =
OcrOutcome.Failure(OcrFailure.ENGINE_ERROR("ocr unavailable"))
override fun close() {
// Nothing to release: this engine holds no resources.
}

}
