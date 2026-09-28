package org.payswap.camscan.export.pdf

/**
 * Pixel geometry read from a JPEG's SOF (start-of-frame) marker — pure
 * Kotlin, android-free (CAMSCAN-PROD-007 §6.1 helper).
 *
 * Used on the export path for two purposes: giving the android-side
 * [PageJpegEncoder] a lossless fast path (already-JPEG bytes pass through
 * verbatim with their parsed dimensions), and letting JVM tests reason about
 * JPEG structure without a decoder. The scan follows the marker-segment
 * grammar of ISO/IEC 10918-1: SOI, optional fill bytes, then length-prefixed
 * segments until the first SOF; malformed or truncated input returns null
 * (flag discipline — never throws).
 */
data class JpegDimensions(
    val widthPx: Int,
    val heightPx: Int,
    /** SOF component count: 1 = grayscale, 3 = YCbCr, 4 = CMYK. */
    val componentCount: Int,
)

/** Parser for [JpegDimensions] from raw JPEG bytes. */
object JpegDimensionParser {

    /** Parses [jpeg], or null when it is not a recognizable JPEG stream. */
    fun parse(jpeg: ByteArray): JpegDimensions? {
        if (jpeg.size < 4) return null
        if (jpeg[0] != 0xFF.toByte() || jpeg[1] != SOI.toByte()) return null

        var cursor = 2
        while (cursor + 1 < jpeg.size) {
            if (jpeg[cursor] != 0xFF.toByte()) return null
            // Skip fill bytes (0xFF padding) allowed before a marker.
            while (cursor + 1 < jpeg.size && jpeg[cursor + 1] == 0xFF.toByte()) {
                cursor++
            }
            if (cursor + 1 >= jpeg.size) return null
            val marker = jpeg[cursor + 1].toInt() and 0xFF

            when {
                marker == SOI || marker == TEM || marker in RST_MIN..RST_MAX ->
                    cursor += 2 // standalone markers carry no length

                marker == EOI || marker == SOS ->
                    // Reached the end (or compressed data) without a frame.
                    return null

                isStartOfFrame(marker) -> {
                    // SOF layout from the marker: length(2) precision(1)
                    // height(2) width(2) components(1).
                    if (cursor + 10 > jpeg.size) return null
                    val length = readU16(jpeg, cursor + 2)
                    if (length < MIN_SOF_LENGTH) return null
                    val height = readU16(jpeg, cursor + 5)
                    val width = readU16(jpeg, cursor + 7)
                    val components = jpeg[cursor + 9].toInt() and 0xFF
                    if (width == 0 || height == 0 || components == 0) return null
                    return JpegDimensions(width, height, components)
                }

                else -> {
                    // Any other segment: skip via its 2-byte length (which
                    // includes the length bytes themselves).
                    if (cursor + 4 > jpeg.size) return null
                    val length = readU16(jpeg, cursor + 2)
                    if (length < 2) return null
                    cursor += 2 + length
                }
            }
        }
        return null
    }

    private fun isStartOfFrame(marker: Int): Boolean =
        marker in SOF_MIN..SOF_MAX &&
            marker != DHT &&
            marker != JPG &&
            marker != DAC

    private fun readU16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val TEM = 0x01
    private const val RST_MIN = 0xD0
    private const val RST_MAX = 0xD7
    private const val SOF_MIN = 0xC0
    private const val SOF_MAX = 0xCF
    private const val DHT = 0xC4
    private const val JPG = 0xC8
    private const val DAC = 0xCC

    /** Minimum legal SOF length: length(2)+precision(1)+height(2)+width(2)+components(1). */
    private const val MIN_SOF_LENGTH = 8
}
