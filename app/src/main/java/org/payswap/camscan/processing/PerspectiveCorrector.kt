package org.payswap.camscan.processing


import org.payswap.camscan.core.model.PageEnhancementMode
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * CAMSCAN-PROD-003 §6.2 — the geometry seam
 * (docs/SCAN-ENGINE-CONTRACT.md, Geometry):
 *
 *   PerspectiveCorrector.correct(image, quad) -> <the processed page>
 *
 * Steps (contract order): (1) canonical corner ordering, (2) target
 * dimension estimation from edge-length statistics, (3) perspective
 * transform, (4) render a rectangular page with BILINEAR sampling,
 * (5) retain source-to-page geometry metadata.
 *
 * Documented policies:
 *  - Target dimensions = round(mean of opposite edge pairs), clamped to
 *    [MIN_OUTPUT_DIM, MAX_OUTPUT_DIM]. Pixel-center geometry: a quad
 *    spanning pixel centers 0..W-1 yields W-1 output columns (consistent
 *    with PROD-002's QuadGeometry edge statistics). Quads smaller than
 *    MIN_OUTPUT_DIM are bilinearly UP-scaled (never a degenerate 1px
 *    target); larger than MAX are down-scaled (bounds memory).
 *  - Sampling beyond source bounds CLAMPS to the image edge (chosen over
 *    transparency; documented single policy): coordinates are coerced
 *    into [0, w-1] x [0, h-1] before interpolation.
 *  - The warp uses the INVERSE mapping (for each output pixel, map
 *    through the target-rect -> source-quad homography and bilinearly
 *    sample the source). All four ARGB channels are interpolated with
 *    round-half-up on the weighted Double sum.
 *  - Degenerate inputs (malformed image, non-canonicalizable quad,
 *    non-convex/reflex quad, zero edges, singular system) return null —
 *    never throws.
 */
interface PerspectiveCorrector {

    /**
     * Warps [image] so [quad] becomes an upright rectangular page.
     * Returns null for malformed/degenerate input; deterministic for
     * fixed input (byte-stable output, content-derived id).
     */
    fun correct(image: ImageBuffer, quad: QuadF): ProcessedImage?
}

class BilinearPerspectiveCorrector : PerspectiveCorrector {

    override fun correct(image: ImageBuffer, quad: QuadF): ProcessedImage? {
        if (!image.isWellFormed) return null

        // (1) canonical ordering + degeneracy screening.
        val canonicalQuad = QuadF.canonical(quad) ?: return null
        if (!QuadF.isConvex(canonicalQuad)) return null

        // (2) target dimension estimation from opposite-edge statistics.
        val top = QuadF.distance(canonicalQuad.topLeft, canonicalQuad.topRight)
        val bottom = QuadF.distance(canonicalQuad.bottomLeft, canonicalQuad.bottomRight)
        val left = QuadF.distance(canonicalQuad.topLeft, canonicalQuad.bottomLeft)
        val right = QuadF.distance(canonicalQuad.topRight, canonicalQuad.bottomRight)
        val meanWidth = (top + bottom) / 2.0
        val meanHeight = (left + right) / 2.0
        if (meanWidth <= 0.0 || meanHeight <= 0.0) return null
        val outputWidth = meanWidth.roundToInt().coerceIn(MIN_OUTPUT_DIM, MAX_OUTPUT_DIM)
        val outputHeight = meanHeight.roundToInt().coerceIn(MIN_OUTPUT_DIM, MAX_OUTPUT_DIM)

        // (3) inverse mapping: output pixel centers -> source quad. The
        // target rect corners map EXACTLY onto the quad corners, so the
        // warp reproduces the quad edges without a half-pixel shift.
        val target = listOf(
            PointD(0.0, 0.0),
            PointD(outputWidth - 1.0, 0.0),
            PointD(outputWidth - 1.0, outputHeight - 1.0),
            PointD(0.0, outputHeight - 1.0),
        )
        val source = canonicalQuad.corners.map { PointD(it.x.toDouble(), it.y.toDouble()) }
        val homography = Homography.solve(target, source) ?: return null

        // (4) render with bilinear sampling (edge-clamped).
        val out = IntArray(outputWidth * outputHeight)
        for (y in 0 until outputHeight) {
            for (x in 0 until outputWidth) {
                val mapped = homography.map(x.toDouble(), y.toDouble())
                out[y * outputWidth + x] = sampleBilinear(image, mapped.x, mapped.y)
            }
        }

        // (5) retained geometry + ORIGINAL enhancement + content id.
        val buffer = ImageBuffer(outputWidth, outputHeight, out)
        val geometry = ProcessedGeometry(canonicalQuad, outputWidth, outputHeight, meanWidth / meanHeight)
        return ProcessedImage(buffer, geometry, PageEnhancementMode.ORIGINAL, ContentId.of(buffer))
    }

    /** Bilinear sample with the documented edge-CLAMP policy. */
    private fun sampleBilinear(image: ImageBuffer, rawX: Double, rawY: Double): Int {
        val w = image.width
        val h = image.height
        val sx = rawX.coerceIn(0.0, (w - 1).toDouble())
        val sy = rawY.coerceIn(0.0, (h - 1).toDouble())

        val x0 = floor(sx).toInt()
        val y0 = floor(sy).toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = sx - x0
        val fy = sy - y0

        val p00 = image.argb[y0 * w + x0]
        val p10 = image.argb[y0 * w + x1]
        val p01 = image.argb[y1 * w + x0]
        val p11 = image.argb[y1 * w + x1]

        val a = bilinearChannel(p00, p10, p01, p11, fx, fy, ALPHA_SHIFT)
        val r = bilinearChannel(p00, p10, p01, p11, fx, fy, RED_SHIFT)
        val g = bilinearChannel(p00, p10, p01, p11, fx, fy, GREEN_SHIFT)
        val b = bilinearChannel(p00, p10, p01, p11, fx, fy, BLUE_SHIFT)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun bilinearChannel(
        p00: Int,
        p10: Int,
        p01: Int,
        p11: Int,
        fx: Double,
        fy: Double,
        shift: Int,
    ): Int {
        val v00 = (p00 shr shift) and 0xFF
        val v10 = (p10 shr shift) and 0xFF
        val v01 = (p01 shr shift) and 0xFF
        val v11 = (p11 shr shift) and 0xFF
        val value =
            v00 * (1.0 - fx) * (1.0 - fy) +
                v10 * fx * (1.0 - fy) +
                v01 * (1.0 - fx) * fy +
                v11 * fx * fy
        return value.roundToInt().coerceIn(0, 255)
    }

    companion object {

        /** Smallest rendered page dimension (degenerate-quad guard). */
        const val MIN_OUTPUT_DIM = 16

        /** Largest rendered page dimension (memory bound: 4096^2 * 4B = 64MB). */
        const val MAX_OUTPUT_DIM = 4096

        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val BLUE_SHIFT = 0
    }
}
