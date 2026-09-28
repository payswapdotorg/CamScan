package org.payswap.camscan.processing


/*
 * CAMSCAN-PROD-003 §6.1 — platform-neutral image domain type, pure Kotlin.
 *
 * One packed ARGB image: every Int is 0xAARRGGBB, row-major, stride ==
 * width (no padding, no crop rectangles — the CameraX/YUV adapter feeds
 * rotated, packed luminance through its own path in capture/detect and the
 * Bitmap adapter (§6.5) converts ARGB_8888 bitmaps one-to-one).
 *
 * Malformed construction is a FLAG, not an exception (the PROD-002
 * DetectorFrame discipline): consumers on the scan path check
 * [isWellFormed] and degrade to null/neutral results instead of throwing.
 */
class ImageBuffer(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {

    /**
     * Structural well-formedness: positive dimensions and exactly
     * `width * height` packed pixels. False for mismatched storage; every
     * engine entry point degrades gracefully on false.
     */
    val isWellFormed: Boolean
        get() = width > 0 && height > 0 && argb.size == width * height

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageBuffer) return false
        return width == other.width &&
            height == other.height &&
            argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int =
        31 * (31 * width + height) + argb.contentHashCode()

    override fun toString(): String =
        "ImageBuffer(width=$width, height=$height, pixels=${argb.size})"
}
