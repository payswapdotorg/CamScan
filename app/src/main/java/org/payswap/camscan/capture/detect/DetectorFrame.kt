package org.payswap.camscan.capture.detect


/*
 * CAMSCAN-PROD-002 §6.1 — the frame the detector consumes (pure Kotlin, no
 * android.* imports so §6.7 JVM tests compile and run anywhere).
 *
 * Contract: packed 8-bit grayscale, row stride == width (no padding), row 0
 * is the top row, pixel (x, y) lives at luminance[y * width + x].
 *
 * The CameraX glue (DetectionAnalyzer, §6.5) is the only production code that
 * builds one — it extracts the Y plane of a YUV_420_888 ImageProxy (row
 * stride/pixel stride honored), rotates it upright and hands it in here.
 * Tests construct synthetic frames directly.
 */
data class DetectorFrame(
    val width: Int,
    val height: Int,
    val luminance: ByteArray,
) {

    /*
     * Note: data-class equality over ByteArray is identity-based (Kotlin does
     * not specialize array equals). That is acceptable and documented: frames
     * are transient transport objects compared for identity, never for
     * content. The determinism contract lives on DocumentDetection, whose
     * fields are all value types.
     */

    /**
     * Structural sanity: positive dimensions and a tightly packed luminance
     * buffer of exactly width*height bytes. The detector refuses (returns
     * null — never throws) frames that are not well-formed.
     */
    val isWellFormed: Boolean
        get() = width > 0 && height > 0 && luminance.size == width * height
}
