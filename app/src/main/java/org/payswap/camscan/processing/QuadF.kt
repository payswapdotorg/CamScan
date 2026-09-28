package org.payswap.camscan.processing


import org.payswap.camscan.core.model.Corner
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * CAMSCAN-PROD-003 §6.1 — float quad in source-image (pixel) space, pure
 * Kotlin, android-free.
 *
 * QuadF is the geometry currency of the processing tree:
 *  - PROD-002's DocumentDetection corners are frame-space Ints at analysis
 *    resolution (~640x480); [fromFrameQuad] is this work order's mandated
 *    frame-space -> image-space scale helper that maps them onto a capture
 *    at capture resolution;
 *  - [canonical] is the processing-side float variant of PROD-002's
 *    QuadGeometry.orderCorners discipline (centroid-angle ordering with
 *    (y, x) tie-break — y-down coordinates make ascending angle order
 *    TL, TR, BR, BL directly);
 *  - [isConvex] mirrors QuadGeometry.isConvex (strict convexity; collinear
 *    or reflex quads are degenerate and rejected by the corrector).
 *
 * The lead-owned durable model (core/model/Documents.kt) stores its
 * cropQuad as NORMALIZED `Corner`s (0.0..1.0); [toNormalizedCorners]
 * bridges QuadF onto that exact contract type for the PROD-004 session
 * seam (DocumentDetection's Corner KDoc explicitly defers this mapping to
 * this work order).
 */
data class CornerF(val x: Float, val y: Float)

data class QuadF(
    val topLeft: CornerF,
    val topRight: CornerF,
    val bottomRight: CornerF,
    val bottomLeft: CornerF,
) {

    /** Corners in canonical order TL, TR, BR, BL. */
    val corners: List<CornerF>
        get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /**
     * Converts to the lead-owned normalized `core.model.Corner` list
     * (0.0..1.0). Convention: divide by (imageWidth - 1) /
     * (imageHeight - 1) so a full-frame quad maps exactly onto the unit
     * square (0,0), (1,0), (1,1), (0,1). Returns null for degenerate
     * (< 2 pixel) dimensions.
     */
    fun toNormalizedCorners(imageWidth: Int, imageHeight: Int): List<Corner>? {
        if (imageWidth < 2 || imageHeight < 2) return null
        val sx = 1f / (imageWidth - 1)
        val sy = 1f / (imageHeight - 1)
        return listOf(
            Corner(topLeft.x * sx, topLeft.y * sy),
            Corner(topRight.x * sx, topRight.y * sy),
            Corner(bottomRight.x * sx, bottomRight.y * sy),
            Corner(bottomLeft.x * sx, bottomLeft.y * sy),
        )
    }

    companion object {

        private const val CORNER_COUNT = 4

        /** Two corners closer than this in BOTH axes are "the same corner". */
        private const val DISTINCT_EPSILON = 1e-6f

        /** Cross products below this magnitude count as collinear. */
        private const val CONVEX_EPSILON = 1e-6

        /** Full-frame quad over pixel centers (0,0) .. (w-1, h-1). */
        fun fullFrame(width: Int, height: Int): QuadF = QuadF(
            CornerF(0f, 0f),
            CornerF((width - 1).toFloat(), 0f),
            CornerF((width - 1).toFloat(), (height - 1).toFloat()),
            CornerF(0f, (height - 1).toFloat()),
        )

        /**
         * CAMSCAN-PROD-003 §6.1 scale helper: maps a frame-space Int quad
         * (e.g. PROD-002 DocumentDetection corners, canonical TL, TR, BR,
         * BL order at analysis resolution) onto image space at capture
         * resolution. Per-axis proportional scale
         * (x * imageWidth / frameWidth, y * imageHeight / frameHeight).
         *
         * Assumption (documented): the analysis frame and the target image
         * cover the same source region (both derive from the same sensor
         * crop — true for the PROD-002 analyzer, whose rotation is already
         * applied to the frame and whose output quad is upright). If the
         * session ever needs crop-mode awareness (e.g. FILL_CENTER crop),
         * PROD-004 must pre-adjust the quad — this helper stays purely
         * proportional. Returns null for non-positive dimensions.
         */
        fun fromFrameQuad(
            topLeftX: Int,
            topLeftY: Int,
            topRightX: Int,
            topRightY: Int,
            bottomRightX: Int,
            bottomRightY: Int,
            bottomLeftX: Int,
            bottomLeftY: Int,
            frameWidth: Int,
            frameHeight: Int,
            imageWidth: Int,
            imageHeight: Int,
        ): QuadF? {
            if (frameWidth <= 0 || frameHeight <= 0) return null
            if (imageWidth <= 0 || imageHeight <= 0) return null
            val sx = imageWidth.toDouble() / frameWidth
            val sy = imageHeight.toDouble() / frameHeight
            return QuadF(
                CornerF((topLeftX * sx).toFloat(), (topLeftY * sy).toFloat()),
                CornerF((topRightX * sx).toFloat(), (topRightY * sy).toFloat()),
                CornerF((bottomRightX * sx).toFloat(), (bottomRightY * sy).toFloat()),
                CornerF((bottomLeftX * sx).toFloat(), (bottomLeftY * sy).toFloat()),
            )
        }

        /**
         * Canonical TL, TR, BR, BL ordering — the float mirror of
         * PROD-002's QuadGeometry.orderCorners: sort by atan2 angle around
         * the centroid (y-down coordinates: TL ~ -135deg < TR ~ -45deg <
         * BR ~ +45deg < BL ~ +135deg), ties broken on y then x. Fully
         * deterministic and permutation-invariant.
         *
         * Returns null for non-finite coordinates or coincident corners
         * (degenerate quads must not reach the corrector). A self-crossing
         * ("bowtie") input order is untangled into its perimeter order —
         * the same documented behavior as the PROD-002 discipline.
         */
        fun canonical(quad: QuadF): QuadF? {
            val cs = quad.corners
            if (cs.any { !it.x.isFinite() || !it.y.isFinite() }) return null
            for (i in 0 until CORNER_COUNT) {
                for (j in i + 1 until CORNER_COUNT) {
                    if (abs(cs[i].x - cs[j].x) < DISTINCT_EPSILON &&
                        abs(cs[i].y - cs[j].y) < DISTINCT_EPSILON
                    ) {
                        return null
                    }
                }
            }
            val cx = cs.sumOf { it.x.toDouble() } / CORNER_COUNT
            val cy = cs.sumOf { it.y.toDouble() } / CORNER_COUNT
            val sorted = cs.sortedWith(
                compareBy(
                    { atan2(it.y - cy, it.x - cx) },
                    { it.y },
                    { it.x },
                ),
            )
            return QuadF(sorted[0], sorted[1], sorted[2], sorted[3])
        }

        /**
         * Strict convexity in perimeter (canonical) order — the float
         * mirror of PROD-002's QuadGeometry.isConvex: every consecutive
         * triple must turn the same way; a collinear triple (cross product
         * magnitude below [CONVEX_EPSILON]) or a reflex vertex makes the
         * quad degenerate for warping.
         */
        fun isConvex(quad: QuadF): Boolean {
            val cs = quad.corners
            var positive = false
            var negative = false
            for (i in 0 until CORNER_COUNT) {
                val p0 = cs[(i + 3) % CORNER_COUNT]
                val p1 = cs[i]
                val p2 = cs[(i + 1) % CORNER_COUNT]
                val cross = crossProduct(p0, p1, p2)
                if (abs(cross) < CONVEX_EPSILON) return false
                if (cross > 0.0) positive = true else negative = true
                if (positive && negative) return false
            }
            return positive || negative
        }

        /** Euclidean distance between two corners (Double precision). */
        fun distance(a: CornerF, b: CornerF): Double =
            hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

        private fun crossProduct(o: CornerF, a: CornerF, b: CornerF): Double =
            (a.x - o.x).toDouble() * (b.y - a.y).toDouble() -
                (a.y - o.y).toDouble() * (b.x - a.x).toDouble()
    }
}
