package org.payswap.camscan.capture.session

import org.payswap.camscan.processing.CornerF
import org.payswap.camscan.processing.ImageBuffer
import org.payswap.camscan.processing.QuadF

/**
 * CAMSCAN-PROD-004 §6.7 — deterministic synthetic pages for the pure JVM
 * session suites (same discipline as PROD-002's SyntheticFrames: no RNG, no
 * seeds, no I/O, no time reads — the same parameters always build
 * byte-identical buffers).
 *
 * The gradient is COLORED on purpose: enhancement modes (GRAYSCALE and
 * friends) must observably change the pixels, so R/G/B differ from the
 * start.
 */
object SyntheticPages {

    /** A deterministic colored gradient (packed ARGB, alpha 255). */
    fun gradient(width: Int, height: Int): ImageBuffer {
        val argb = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = (x * 10 + y * 3) % 256
                val r = v
                val g = (v * 3) % 256
                val b = 255 - v
                argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ImageBuffer(width, height, argb)
    }

    /** A uniform gray page (flat input — enhancement-identity checks). */
    fun uniform(width: Int, height: Int, gray: Int): ImageBuffer {
        val v = gray.coerceIn(0, 255)
        val pixel = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        return ImageBuffer(width, height, IntArray(width * height) { pixel })
    }

    /** Axis-aligned rect quad, canonical TL/TR/BR/BL order. */
    fun rectQuad(left: Int, top: Int, right: Int, bottom: Int): QuadF = QuadF(
        CornerF(left.toFloat(), top.toFloat()),
        CornerF(right.toFloat(), top.toFloat()),
        CornerF(right.toFloat(), bottom.toFloat()),
        CornerF(left.toFloat(), bottom.toFloat()),
    )
}
