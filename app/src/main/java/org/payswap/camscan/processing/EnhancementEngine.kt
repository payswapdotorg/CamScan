package org.payswap.camscan.processing


import org.payswap.camscan.core.model.PageEnhancementMode
import kotlin.math.pow

/*
 * CAMSCAN-PROD-003 §6.3 — the enhancement seam
 * (docs/SCAN-ENGINE-CONTRACT.md, Enhancement):
 *
 *   EnhancementEngine.process(page, mode) -> <the processed page>
 *
 * Deterministic and NON-DESTRUCTIVE: the input page and its buffer are
 * never mutated (a fresh output buffer is always allocated); same input
 * + mode -> byte-identical output (pure integer/Double math, no RNG, no
 * time, no device state). The page id and geometry are preserved
 * verbatim — a mode chain keeps one stable identity.
 *
 * Documented mode semantics (all: alpha preserved, luma per
 * [LumaOps.luma]):
 *  - ORIGINAL      identity COPY (new buffer, same pixels).
 *  - GRAYSCALE     BT.601 luma -> equal RGB channels.
 *  - BLACK_AND_WHITE  global Otsu threshold t; luma > t -> 255 (paper),
 *                  else 0. Flat-histogram tie-break: paper-positive
 *                  (see LumaOps.otsuThreshold).
 *  - CONTRAST      0.5th/99.5th luma percentiles (lo, hi) -> each RGB
 *                  channel stretched by ((c - lo) * 255 + span/2) / span
 *                  (integer division, truncated toward zero, clamped to
 *                  [0, 255]). Degenerate flat input (hi <= lo): identity.
 *  - SHARPEN       single-pass 2D convolution, kernel center 5 / cross
 *                  -1 (sum 1, not separable), per RGB channel, clamped
 *                  [0, 255]. Border policy: EDGE CLAMP (replicated
 *                  neighbors). Alpha copied from the source pixel.
 *  - LOW_LIGHT     luma-domain gamma with constant exponent 1/1.6
 *                  (documented; < 1 brightens shadows), then the CONTRAST
 *                  percentile stretch computed on the GAMMA-CORRECTED
 *                  luma (flat -> identity), then chroma preserved
 *                  PROPORTIONALLY: each channel scaled by out_luma /
 *                  in_luma (integer: (c * lumaOut + lumaIn / 2) / lumaIn,
 *                  clamped). Black pixels (luma 0) stay black.
 *
 * Malformed buffers never throw: the engine returns a page with the same
 * id/geometry/mode over a zero-filled buffer of the DECLARED dimensions
 * (flag-not-exception discipline).
 */
interface EnhancementEngine {

    /** Applies [mode] to [page]; returns a new [ProcessedImage]. Never throws. */
    fun process(page: ProcessedImage, mode: PageEnhancementMode): ProcessedImage
}

class DefaultEnhancementEngine : EnhancementEngine {

    override fun process(page: ProcessedImage, mode: PageEnhancementMode): ProcessedImage {
        val source = page.buffer
        val width = source.width
        val height = source.height
        if (!source.isWellFormed) {
            val declared = width * height
            val empty = ImageBuffer(width, height, IntArray(if (declared > 0) declared else 0))
            return ProcessedImage(empty, page.geometry, mode, page.id)
        }

        val out = when (mode) {
            PageEnhancementMode.ORIGINAL -> source.argb.copyOf()
            PageEnhancementMode.GRAYSCALE -> grayscale(source.argb)
            PageEnhancementMode.BLACK_AND_WHITE -> blackAndWhite(source.argb)
            PageEnhancementMode.CONTRAST -> contrast(source.argb)
            PageEnhancementMode.SHARPEN -> sharpen(source.argb, width, height)
            PageEnhancementMode.LOW_LIGHT -> lowLight(source.argb)
        }
        return ProcessedImage(ImageBuffer(width, height, out), page.geometry, mode, page.id)
    }

    // ------------------------------------------------------------- modes

    private fun grayscale(argb: IntArray): IntArray {
        val out = IntArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val g = LumaOps.luma(pixel)
            out[i] = (pixel and 0xFF000000.toInt()) or (g shl 16) or (g shl 8) or g
        }
        return out
    }

    private fun blackAndWhite(argb: IntArray): IntArray {
        val hist = LumaOps.histogram(argb)
        val threshold = LumaOps.otsuThreshold(hist)
        val out = IntArray(argb.size)
        for (i in argb.indices) {
            val pixel = argb[i]
            val value = if (LumaOps.luma(pixel) > threshold) 255 else 0
            out[i] = (pixel and 0xFF000000.toInt()) or (value shl 16) or (value shl 8) or value
        }
        return out
    }

    private fun contrast(argb: IntArray): IntArray {
        val hist = LumaOps.histogram(argb)
        val lo = LumaOps.quantile(hist, LOW_PERCENTILE)
        val hi = LumaOps.quantile(hist, HIGH_PERCENTILE)
        val out = IntArray(argb.size)
        val span = hi - lo
        if (span <= 0) {
            // Degenerate flat input: identity (documented).
            argb.copyInto(out)
            return out
        }
        val half = span / 2
        for (i in argb.indices) {
            val pixel = argb[i]
            val r = stretchChannel((pixel shr 16) and 0xFF, lo, span, half)
            val g = stretchChannel((pixel shr 8) and 0xFF, lo, span, half)
            val b = stretchChannel(pixel and 0xFF, lo, span, half)
            out[i] = (pixel and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun sharpen(argb: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(argb.size)
        for (y in 0 until height) {
            val up = (y - 1).coerceAtLeast(0)
            val down = (y + 1).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - 1).coerceAtLeast(0)
                val right = (x + 1).coerceAtMost(width - 1)
                val center = argb[y * width + x]
                val upPixel = argb[up * width + x]
                val downPixel = argb[down * width + x]
                val leftPixel = argb[y * width + left]
                val rightPixel = argb[y * width + right]
                val r = sharpenChannel(
                    (center shr 16) and 0xFF,
                    (upPixel shr 16) and 0xFF,
                    (downPixel shr 16) and 0xFF,
                    (leftPixel shr 16) and 0xFF,
                    (rightPixel shr 16) and 0xFF,
                )
                val g = sharpenChannel(
                    (center shr 8) and 0xFF,
                    (upPixel shr 8) and 0xFF,
                    (downPixel shr 8) and 0xFF,
                    (leftPixel shr 8) and 0xFF,
                    (rightPixel shr 8) and 0xFF,
                )
                val b = sharpenChannel(
                    center and 0xFF,
                    upPixel and 0xFF,
                    downPixel and 0xFF,
                    leftPixel and 0xFF,
                    rightPixel and 0xFF,
                )
                out[y * width + x] =
                    (center and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun lowLight(argb: IntArray): IntArray {
        val n = argb.size
        val lumaIn = IntArray(n)
        val lumaGamma = IntArray(n)
        for (i in 0 until n) {
            lumaIn[i] = LumaOps.luma(argb[i])
            lumaGamma[i] = gammaRound(lumaIn[i])
        }

        val hist = IntArray(256)
        for (v in lumaGamma) {
            hist[v]++
        }
        val lo = LumaOps.quantile(hist, LOW_PERCENTILE)
        val hi = LumaOps.quantile(hist, HIGH_PERCENTILE)
        val span = hi - lo
        val half = span / 2

        val out = IntArray(n)
        for (i in 0 until n) {
            val pixel = argb[i]
            val luma = lumaIn[i]
            if (luma <= 0) {
                // Pure black stays black (luma 0 implies R = G = B = 0).
                out[i] = pixel and 0xFF000000.toInt()
                continue
            }
            val gammaValue = lumaGamma[i]
            val lumaOut = if (span <= 0) {
                gammaValue // flat gamma-luma: identity stretch (documented)
            } else {
                (((gammaValue - lo) * 255 + half) / span).coerceIn(0, 255)
            }
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            val rOut = ((r * lumaOut + luma / 2) / luma).coerceIn(0, 255)
            val gOut = ((g * lumaOut + luma / 2) / luma).coerceIn(0, 255)
            val bOut = ((b * lumaOut + luma / 2) / luma).coerceIn(0, 255)
            out[i] = (pixel and 0xFF000000.toInt()) or (rOut shl 16) or (gOut shl 8) or bOut
        }
        return out
    }

    // ---------------------------------------------------------- internals

    /** ((c - lo) * 255 + span/2) / span, truncated toward zero, clamped. */
    private fun stretchChannel(channel: Int, lo: Int, span: Int, half: Int): Int =
        (((channel - lo) * 255 + half) / span).coerceIn(0, 255)

    /** 5*center - up - down - left - right (kernel sum 1), clamped. */
    private fun sharpenChannel(
        center: Int,
        up: Int,
        down: Int,
        left: Int,
        right: Int,
    ): Int = (5 * center - up - down - left - right).coerceIn(0, 255)

    /** 255 * (luma/255)^(1/1.6), round-half-up; endpoints exact. */
    private fun gammaRound(luma: Int): Int {
        if (luma <= 0) return 0
        if (luma >= 255) return 255
        val normalized = luma / 255.0
        return (255.0 * normalized.pow(GAMMA_EXPONENT) + 0.5).toInt()
    }

    companion object {

        /** Documented LOW_LIGHT brightening constant (exponent 1/1.6 = 0.625). */
        const val GAMMA_EXPONENT = 1.0 / 1.6

        /** CONTRAST / LOW_LIGHT stretch percentiles (0.5th / 99.5th). */
        const val LOW_PERCENTILE = 0.005
        const val HIGH_PERCENTILE = 0.995
    }
}
