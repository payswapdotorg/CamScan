package org.payswap.camscan.capture.detect

/**
 * CAMSCAN-PROD-002 §6.7 — deterministic synthetic frame construction for the
 * pure JVM detector/stabilizer/geometry suites.
 *
 * Properties (contract for every fixture built here):
 *  - **deterministic**: no RNG, no seeds, no I/O, no time reads — the same
 *    parameters always build byte-identical frames;
 *  - **configurable**: corner positions (arbitrary convex quads — axis-aligned,
 *    rotated, perspective, receipt aspect), page/background luminance, and
 *    deterministic noise amplitude;
 *  - **honest**: pages are rendered by exact point-in-quad tests (half-plane
 *    sign consistency), so the ground-truth quad IS the rendered boundary.
 *
 * The noise pattern is a fixed arithmetic function of (x, y) — bounded,
 * zero-mean over its period, and identical across runs.
 */
object SyntheticFrames {

    /** A uniform frame — blank wall, lens cap, nothing to detect. */
    fun blank(width: Int, height: Int, luma: Int = 128): DetectorFrame {
        val v = luma.coerceIn(0, 255)
        return DetectorFrame(width, height, ByteArray(width * height) { v.toByte() })
    }

    /**
     * A bright page on a darker background — the dominant document-scanning
     * scene. [quad] is the exact ground truth: four corners in any order
     * (they are canonicalized internally); the page is the interior of the
     * convex quad they span.
     */
    fun page(
        width: Int,
        height: Int,
        quad: List<Corner>,
        pageLuma: Int = 235,
        backgroundLuma: Int = 40,
        noiseAmplitude: Int = 0,
    ): DetectorFrame {
        require(quad.size == 4) { "quad must have exactly 4 corners" }
        val page = pageLuma.coerceIn(0, 255)
        val background = backgroundLuma.coerceIn(0, 255)
        val ordered = QuadGeometry.orderCorners(quad)
        val luminance = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = if (isInsideQuad(x, y, ordered)) page else background
                luminance[y * width + x] = clamp(base + noiseAt(x, y, noiseAmplitude)).toByte()
            }
        }
        return DetectorFrame(width, height, luminance)
    }

    /** Canonical (TL, TR, BR, BL) helper for readable fixtures. */
    fun quad(
        topLeft: Pair<Int, Int>,
        topRight: Pair<Int, Int>,
        bottomRight: Pair<Int, Int>,
        bottomLeft: Pair<Int, Int>,
    ): List<Corner> = listOf(
        Corner(topLeft.first, topLeft.second),
        Corner(topRight.first, topRight.second),
        Corner(bottomRight.first, bottomRight.second),
        Corner(bottomLeft.first, bottomLeft.second),
    )

    /**
     * Jitters a canonical quad by (dx, dy) — every corner shifted by the same
     * offset: an exact same-shape, same-orientation quad at a new position
     * (the stabilizer's "agreeing" family).
     */
    fun shifted(quad: List<Corner>, dx: Int, dy: Int): List<Corner> =
        quad.map { Corner(it.x + dx, it.y + dy) }

    // ------------------------------------------------------------------ internals

    /**
     * Point-in-convex-quad via half-plane sign consistency: a point is inside
     * iff the cross products against all four (ordered) edges share a sign.
     */
    private fun isInsideQuad(x: Int, y: Int, orderedQuad: List<Corner>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = orderedQuad[i]
            val b = orderedQuad[(i + 1) % 4]
            val cross = (b.x - a.x).toLong() * (y - a.y) - (b.y - a.y).toLong() * (x - a.x)
            if (cross != 0L) {
                val crossSign = if (cross > 0) 1 else -1
                if (sign == 0) sign = crossSign else if (sign != crossSign) return false
            }
        }
        return true // interior or exactly on the boundary — both render as page
    }

    /**
     * Fixed deterministic noise: a bounded arithmetic pattern in (x, y),
     * zero-mean over its period. No randomness anywhere.
     */
    private fun noiseAt(x: Int, y: Int, amplitude: Int): Int {
        if (amplitude <= 0) return 0
        val period = 2 * amplitude + 1
        return (x * 31 + y * 17 + ((x * y) % 13)) % period - amplitude
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
