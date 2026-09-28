package org.payswap.camscan.processing


import kotlin.math.ceil

/*
 * CAMSCAN-PROD-003 §6.3/§6.4 — shared deterministic luma statistics,
 * pure Kotlin, android-free. One implementation used by BOTH the
 * enhancement engine (GRAYSCALE / BLACK_AND_WHITE / CONTRAST / LOW_LIGHT)
 * and the quality gates (contrast spread). All integer/Double math with
 * fixed iteration order: same input -> same output, byte-stable.
 */
object LumaOps {

    /**
     * ITU-R BT.601 luma with round-half-up integer arithmetic:
     * (299 R + 587 G + 114 B + 500) / 1000. Exactly reversible fixtures:
     * luma of a gray pixel (v, v, v) is v; (255, 0, 0) -> 76,
     * (0, 255, 0) -> 150, (0, 0, 255) -> 29.
     */
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b + 500) / 1000
    }

    /** 256-bin luma histogram over packed ARGB pixels (alpha ignored). */
    fun histogram(argb: IntArray): IntArray {
        val hist = IntArray(256)
        for (pixel in argb) {
            hist[luma(pixel)]++
        }
        return hist
    }

    /**
     * Percentile by rank on a luma histogram: k = ceil(fraction * N)
     * (1-based, clamped to [1, N]); returns the SMALLEST luma value v
     * whose cumulative count is >= k. Deterministic and exact on
     * hand-computed fixtures (N = 9: 0.005 -> the minimum,
     * 0.995 -> the maximum).
     */
    fun quantile(hist: IntArray, fraction: Double): Int {
        val n = hist.sum()
        if (n <= 0) return 0
        val k = ceil(fraction * n).toInt().coerceIn(1, n)
        var seen = 0
        for (v in 0..255) {
            seen += hist[v]
            if (seen >= k) return v
        }
        return 255
    }

    /**
     * Global Otsu threshold. Between-class variance is computed only for
     * thresholds with BOTH classes non-empty; ties keep the SMALLEST
     * maximizing threshold; a flat histogram (no valid split at all)
     * returns 0.
     *
     * Documented tie-break semantics (the "bimodal-flat" rule): with
     * classification `luma > t -> paper (255)`, the smallest-t bias classifies
     * borderline pixels as PAPER — a flat bright page stays white (255),
     * a flat all-black frame stays black (0). Paper-positive prior:
     * blank pages are the common scan failure, not ink fields.
     */
    fun otsuThreshold(hist: IntArray): Int {
        val n = hist.sum().toDouble()
        if (n <= 0.0) return 0
        var totalSum = 0.0
        for (v in 0..255) {
            totalSum += v.toDouble() * hist[v]
        }

        var bestThreshold = 0
        var bestVariance = 0.0
        var recorded = false
        var count0 = 0.0
        var sum0 = 0.0
        for (t in 0..255) {
            count0 += hist[t]
            sum0 += t.toDouble() * hist[t]
            val count1 = n - count0
            if (count0 > 0.0 && count1 > 0.0) {
                val mean0 = sum0 / count0
                val mean1 = (totalSum - sum0) / count1
                val diff = mean0 - mean1
                val variance = (count0 / n) * (count1 / n) * diff * diff
                if (!recorded || variance > bestVariance) {
                    recorded = true
                    bestVariance = variance
                    bestThreshold = t
                }
            }
        }
        return bestThreshold
    }
}
