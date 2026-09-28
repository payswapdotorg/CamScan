package org.payswap.camscan.processing


/*
 * CAMSCAN-PROD-003 §6.1 — retained source-to-page geometry metadata
 * (docs/SCAN-ENGINE-CONTRACT.md, Geometry step 5: "retain source-to-page
 * geometry metadata"). Pure Kotlin value type.
 */
data class ProcessedGeometry(

    /** The canonical source-space quad the page was extracted from. */
    val sourceQuad: QuadF,

    /** Rendered page width in pixels (estimated + clamped). */
    val outputWidth: Int,

    /** Rendered page height in pixels (estimated + clamped). */
    val outputHeight: Int,

    /**
     * Estimated physical page aspect (width / height) from the source
     * quad's opposite-edge statistics, BEFORE rounding and clamping to
     * output dimensions. A portrait A4 page is ~0.707.
     */
    val pageAspect: Double,
)
