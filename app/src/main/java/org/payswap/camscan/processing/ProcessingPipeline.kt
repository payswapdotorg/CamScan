package org.payswap.camscan.processing


import org.payswap.camscan.core.model.PageEnhancementMode

/*
 * CAMSCAN-PROD-003 §6.6 — minimal engine-side pipeline wiring (the
 * review/session UI is PROD-004 and intentionally NOT built here; no
 * ScanFragment hook was needed for this work order).
 *
 * processCapture contract:
 *  - detectionQuad == null => FULL-FRAME COPY (no warp), then enhance
 *    (the mode applies on both paths — the alternative reading, "null
 *    quad returns an ORIGINAL copy and ignores the mode", would make the
 *    mode parameter dead for full-frame captures; interpretation
 *    documented here and flagged in the delivery report).
 *  - detectionQuad != null => correct() then enhance().
 *  - Returns null only when the corrector rejects the input (malformed
 *    image / degenerate quad) — enhancement never fails.
 *
 * Deterministic end to end: same (source, quad, mode) -> byte-identical
 * output and the same content-derived id.
 */
class ProcessingPipeline(

    private val corrector: PerspectiveCorrector = BilinearPerspectiveCorrector(),
    private val enhancer: EnhancementEngine = DefaultEnhancementEngine(),
) {

    fun processCapture(
        source: ImageBuffer,
        detectionQuad: QuadF?,
        mode: PageEnhancementMode,
    ): ProcessedImage? {
        val corrected = if (detectionQuad == null) {
            fullFrameCopy(source)
        } else {
            corrector.correct(source, detectionQuad)
        }
        return corrected?.let { enhancer.process(it, mode) }
    }

    /** Non-destructive full-frame wrap: copy pixels, retain full-frame geometry. */
    private fun fullFrameCopy(source: ImageBuffer): ProcessedImage? {
        if (!source.isWellFormed) return null
        val buffer = ImageBuffer(source.width, source.height, source.argb.copyOf())
        val geometry = ProcessedGeometry(
            sourceQuad = QuadF.fullFrame(source.width, source.height),
            outputWidth = source.width,
            outputHeight = source.height,
            pageAspect = source.width.toDouble() / source.height,
        )
        return ProcessedImage(buffer, geometry, PageEnhancementMode.ORIGINAL, ContentId.of(buffer))
    }
}
