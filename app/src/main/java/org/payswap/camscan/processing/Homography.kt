package org.payswap.camscan.processing


import kotlin.math.abs

/*
 * CAMSCAN-PROD-003 §6.2 — 4-point homography, pure Kotlin, android-free.
 *
 * Solved by plain DLT on the 8x8 linear system (Gaussian elimination with
 * deterministic partial pivoting — no library, no SVD). The matrix is
 * row-major 3x3, normalized so h33 == 1:
 *
 *     [ m0 m1 m2 ]
 *     [ m3 m4 m5 ]
 *     [ m6 m7 1  ]
 *
 * u = (m0 x + m1 y + m2) / (m6 x + m7 y + 1)
 * v = (m3 x + m4 y + m5) / (m6 x + m7 y + 1)
 *
 * Degenerate point sets (collinear triples, coincident points, wrong
 * sizes) yield null — this class NEVER throws (PROD-002 discipline).
 */
data class PointD(val x: Double, val y: Double)

class Homography private constructor(

    /**
     * Row-major 3x3 coefficients, [8] == 1.0. Public for engine/tests;
     * treat as read-only.
     */
    val matrix: DoubleArray,
) {

    /** Forward transform of one point. Deterministic; never throws. */
    fun map(x: Double, y: Double): PointD {
        var denominator = matrix[6] * x + matrix[7] * y + 1.0
        // Guard the (unreachable-for-convex-quads) vanishing-denominator
        // case so the corrector can never see NaN/Inf from this seam.
        if (abs(denominator) < DENOMINATOR_EPSILON) {
            denominator = if (denominator < 0.0) -DENOMINATOR_EPSILON else DENOMINATOR_EPSILON
        }
        return PointD(
            (matrix[0] * x + matrix[1] * y + matrix[2]) / denominator,
            (matrix[3] * x + matrix[4] * y + matrix[5]) / denominator,
        )
    }

    /**
     * Inverse transform via adjugate / determinant, re-normalized so
     * h33 == 1. Returns null when the matrix is singular (or the inverse
     * cannot be re-normalized); never throws.
     */
    fun invert(): Homography? {
        val m = matrix
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7] // i == m[8] == 1

        val det = a * (e - f * h) - b * (d - f * g) + c * (d * h - e * g)
        if (abs(det) < DET_EPSILON) return null

        // Adjugate of [a b c; d e f; g h 1], divided by det.
        val inverse = doubleArrayOf(
            (e - f * h) / det, (c * h - b) / det, (b * f - c * e) / det,
            (f * g - d) / det, (a - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
        if (abs(inverse[8]) < DENOMINATOR_EPSILON) return null
        val scale = inverse[8]
        for (k in inverse.indices) inverse[k] /= scale
        return Homography(inverse)
    }

    companion object {

        /** Pivot below this magnitude => singular system (degenerate quad). */
        private const val PIVOT_EPSILON = 1e-7

        private const val DET_EPSILON = 1e-12

        private const val DENOMINATOR_EPSILON = 1e-12

        /**
         * Solves the homography mapping the four [src] points onto the
         * four [dst] points (index-aligned). Exactly 4 finite points per
         * side required; returns null otherwise or when the system is
         * singular (collinear point sets).
         */
        fun solve(src: List<PointD>, dst: List<PointD>): Homography? {
            if (src.size != 4 || dst.size != 4) return null
            if ((src + dst).any { !it.x.isFinite() || !it.y.isFinite() }) return null

            // 8x9 augmented system A | b (column 8 is b):
            // x h11 + y h12 + h13 - u x h31 - u y h32 = u
            // x h21 + y h22 + h23 - v x h31 - v y h32 = v
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = src[i].x
                val y = src[i].y
                val u = dst[i].x
                val v = dst[i].y
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }

            // Gaussian elimination with partial pivoting. Pivot choice is
            // the FIRST row holding the column maximum — fully
            // deterministic across runs.
            for (col in 0 until 8) {
                var pivotRow = col
                var best = abs(a[col][col])
                for (r in col + 1 until 8) {
                    val candidate = abs(a[r][col])
                    if (candidate > best) {
                        best = candidate
                        pivotRow = r
                    }
                }
                if (best < PIVOT_EPSILON) return null
                if (pivotRow != col) {
                    val tmp = a[pivotRow]
                    a[pivotRow] = a[col]
                    a[col] = tmp
                }
                val pivot = a[col][col]
                for (r in col + 1 until 8) {
                    val factor = a[r][col] / pivot
                    if (factor == 0.0) continue
                    for (c in col until 9) {
                        a[r][c] -= factor * a[col][c]
                    }
                }
            }

            // Back substitution on the upper-triangular system.
            val h = DoubleArray(8)
            for (row in 7 downTo 0) {
                var sum = a[row][8]
                for (c in row + 1 until 8) {
                    sum -= a[row][c] * h[c]
                }
                h[row] = sum / a[row][row]
            }
            return Homography(
                doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0),
            )
        }
    }
}
