package org.payswap.camscan.export.compress

/**
 * One resampled page JPEG: the re-encoded payload plus its ACTUAL output
 * pixel dimensions (echoed by the resampler — never assumed).
 */
data class ResampledJpeg(
    val jpegBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResampledJpeg) return false
        return widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int = 31 * (31 * widthPx + heightPx) + jpegBytes.contentHashCode()

    override fun toString(): String =
        "ResampledJpeg(widthPx=$widthPx, heightPx=$heightPx, jpegBytes=${jpegBytes.size})"
}

/**
 * Resampling seam (CAMSCAN-PROD-008 §6.5): decode a JPEG, downsample to the
 * exact target dims, re-encode at [quality]. The DECISION logic stays
 * JVM-side in [DownsamplePlanner]; the implementation is bitmap work. Null
 * on decode/encode failure — flag discipline, never throws, and the caller
 * NEVER silently substitutes the original bytes for a failed resample.
 */
interface ImageResampler {

    fun resample(
        jpegBytes: ByteArray,
        targetWidthPx: Int,
        targetHeightPx: Int,
        quality: Int,
    ): ResampledJpeg?
}
