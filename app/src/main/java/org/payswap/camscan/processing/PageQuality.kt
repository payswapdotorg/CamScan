package org.payswap.camscan.processing


import kotlin.math.sqrt

/*
 * CAMSCAN-PROD-003 §6.4 — page quality gates, pure Kotlin, android-free.
 *
 * Deterministic, never throws. Metrics on the BT.601 luma plane:
 *  - sharpness     normalized Laplacian RMS: 4-neighbor Laplacian
 *                  (edge-clamped borders, same policy as SHARPEN) on
 *                  luma, RMS over all pixels / 255. Flat => 0; an 8x8
 *                  100/160 checkerboard => ~0.771.
 *  - contrastSpread (99.5th - 0.5th luma percentile) / 255.
 *  - blownFraction fraction of pixels with luma >= 250 (blown highlights).
 *  - darkFraction  fraction of pixels with luma <= 5.
 *
 * Flag pairing: SHARP/BLURRED (sharpness >= threshold) and
 * GOOD_CONTRAST/LOW_CONTRAST (spread >= threshold) are exclusive pairs;
 * GLARE (blownFraction > threshold) and TOO_DARK (darkFraction >
 * threshold) are independent and may co-occur. All thresholds are
 * ctor-configurable with the documented defaults below.
 *
 * Malformed images return the worst-case advisory flags
 * {BLURRED, LOW_CONTRAST} with zero metrics — never throws.
 */
enum class QualityFlag {
    SHARP,
    BLURRED,
    GOOD_CONTRAST,
    LOW_CONTRAST,
    GLARE,
    TOO_DARK,
}

/** Measured page quality: metrics + advisory flags. Deterministic. */
data class PageQuality(
    val sharpness: Double,
    val contrastSpread: Double,
    val blownFraction: Double,
    val darkFraction: Double,
    val flags: Set<QualityFlag>,
)

class QualityGates(

    /** sharpness >= this => SHARP (else BLURRED). */
    val sharpnessThreshold: Double = DEFAULT_SHARPNESS_THRESHOLD,

    /** contrastSpread >= this => GOOD_CONTRAST (else LOW_CONTRAST). */
    val contrastThreshold: Double = DEFAULT_CONTRAST_THRESHOLD,

    /** blownFraction > this => GLARE. */
    val glareFractionThreshold: Double = DEFAULT_GLARE_FRACTION,

    /** darkFraction > this => TOO_DARK. */
    val darkFractionThreshold: Double = DEFAULT_DARK_FRACTION,
) {

    fun evaluate(image: ImageBuffer): PageQuality {
        if (!image.isWellFormed) {
            return PageQuality(0.0, 0.0, 0.0, 0.0, WORST_CASE_FLAGS)
        }

        val width = image.width
        val height = image.height
        val n = width * height

        val hist = LumaOps.histogram(image.argb)
        val lo = LumaOps.quantile(hist, LOW_PERCENTILE)
        val hi = LumaOps.quantile(hist, HIGH_PERCENTILE)
        val contrastSpread = (hi - lo) / 255.0

        var blown = 0
        for (v in BLOWN_LUMA..255) blown += hist[v]
        var dark = 0
        for (v in 0..DARK_LUMA) dark += hist[v]
        val blownFraction = blown.toDouble() / n
        val darkFraction = dark.toDouble() / n

        // Normalized Laplacian RMS on the luma plane (edge-clamped
        // borders — the SHARPEN border policy, so the two kernels share
        // one discipline).
        val luma = IntArray(n)
        for (i in 0 until n) {
            luma[i] = LumaOps.luma(image.argb[i])
        }
        var sumSquares = 0.0
        for (y in 0 until height) {
            val up = (y - 1).coerceAtLeast(0)
            val down = (y + 1).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - 1).coerceAtLeast(0)
                val right = (x + 1).coerceAtMost(width - 1)
                val center = luma[y * width + x]
                val response = 4 * center -
                    luma[up * width + x] -
                    luma[down * width + x] -
                    luma[y * width + left] -
                    luma[y * width + right]
                sumSquares += response.toDouble() * response.toDouble()
            }
        }
        val sharpness = sqrt(sumSquares / n) / 255.0

        val flags = buildSet {
            if (sharpness >= sharpnessThreshold) add(QualityFlag.SHARP) else add(QualityFlag.BLURRED)
            if (contrastSpread >= contrastThreshold) add(QualityFlag.GOOD_CONTRAST) else add(QualityFlag.LOW_CONTRAST)
            if (blownFraction > glareFractionThreshold) add(QualityFlag.GLARE)
            if (darkFraction > darkFractionThreshold) add(QualityFlag.TOO_DARK)
        }
        return PageQuality(sharpness, contrastSpread, blownFraction, darkFraction, flags)
    }

    companion object {

        /** Normalized-Laplacian-RMS default: mid-tone checkerboards clear it easily. */
        const val DEFAULT_SHARPNESS_THRESHOLD = 0.02

        /** Percentile-spread default: 38+ luma levels of dynamic range. */
        const val DEFAULT_CONTRAST_THRESHOLD = 0.15

        /** >10% blown highlights = GLARE. */
        const val DEFAULT_GLARE_FRACTION = 0.10

        /** >25% near-black pixels = TOO_DARK. */
        const val DEFAULT_DARK_FRACTION = 0.25

        const val BLOWN_LUMA = 250

        const val DARK_LUMA = 5

        const val LOW_PERCENTILE = 0.005

        const val HIGH_PERCENTILE = 0.995

        private val WORST_CASE_FLAGS: Set<QualityFlag> =
            setOf(QualityFlag.BLURRED, QualityFlag.LOW_CONTRAST)
    }
}
