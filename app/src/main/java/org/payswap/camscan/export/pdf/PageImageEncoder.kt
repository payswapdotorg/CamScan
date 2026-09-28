package org.payswap.camscan.export.pdf

/**
 * A page image in export JPEG form: the encoded payload plus the pixel
 * geometry the export layers need (PDF MediaBox sizing, JPG artifact bytes).
 */
data class EncodedJpeg(
    val bytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    /** JPEG component count (1/3/4); 3 is the encoder's RGB output default. */
    val colorComponents: Int = COLOR_COMPONENTS_RGB,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedJpeg) return false
        return widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            colorComponents == other.colorComponents &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int =
        31 * (31 * (31 * widthPx + heightPx) + colorComponents) + bytes.contentHashCode()

    override fun toString(): String =
        "EncodedJpeg(widthPx=$widthPx, heightPx=$heightPx, " +
            "colorComponents=$colorComponents, bytes=${bytes.size})"

    companion object {
        const val COLOR_COMPONENTS_RGB = 3
    }
}

/**
 * Seam between stored page bytes (whatever the scan pipeline persisted —
 * today PNG via the session adapter) and the export JPEG form. Pure,
 * android-free, so the JVM-side [org.payswap.camscan.export.ExportEngine]
 * stays testable with fakes; the production implementation is the
 * android-side [PageJpegEncoder].
 */
interface PageImageEncoder {

    /**
     * Encodes [storedBytes] into export JPEG form. Returns null when the
     * bytes are empty or undecodable (flag discipline — never throws).
     */
    fun encode(storedBytes: ByteArray): EncodedJpeg?
}

/** Clamps a caller-supplied JPEG quality into the valid 1..100 range. */
fun clampJpegQuality(quality: Int): Int = quality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)

private const val MIN_JPEG_QUALITY = 1
private const val MAX_JPEG_QUALITY = 100
