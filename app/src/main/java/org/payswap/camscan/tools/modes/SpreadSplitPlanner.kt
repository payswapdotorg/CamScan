package org.payswap.camscan.tools.modes

import kotlin.math.abs

// CAMSCAN-PROD-012 §6.4 — book-spread split planner. Pure, deterministic,
// never throws on degenerate input (Degenerate results instead).
//
// Midpoint-line construction (documented exactly): the input quad is the
// observed spread in CANONICAL corner order TL, TR, BR, BL (the detection
// pipeline's edge ordering; the planner does NOT re-order — misordered or
// self-intersecting inputs yield Degenerate results, never a throw). The
// page midpoint line ("the spine") is the segment connecting the midpoints
// of the two opposite edges the spine crosses: M1 = midpoint(TL, TR) (the
// top edge's midpoint, where the spine meets the top page border) and
// M2 = midpoint(BL, BR) (the bottom edge's midpoint). For a convex spread
// the segment M1..M2 lies inside the quad and the split line enters
// through the top edge and exits through the bottom edge — exactly two of
// the four edges, always.
//
// Split construction: the infinite line through M1..M2 is clipped against
// the four quad edges parametrically (edge point = E0 + s * edgeDir; line
// point = M1 + t * lineDir; solved from the 2x2 cross-product system).
// The crossing on the top edge is A, the crossing on the bottom edge is
// B; the LEFT page quad is (TL, A, B, BL) and the RIGHT page quad is
// (A, TR, BR, B), both in canonical order.
//
// Rejections:
//   COLLINEAR_POINTS     — point-set degeneracy: wrong count handled by the
//                          caller's type; duplicate points, |shoelace area|
//                          below AREA_EPSILON, or a midpoint line that
//                          degenerates to a point (|M2 - M1| below
//                          LENGTH_EPSILON).
//   PARALLEL_EDGES       — an edge is parallel to the midpoint line AND
//                          collinear with it (the split line would run
//                          along an edge — no two clean crossings exist).
//   INTERSECTION_OFF_QUAD — the parametric clipping does not produce
//                          exactly one crossing on the top edge and one on
//                          the bottom edge with line parameter t within
//                          [0, 1] (extra crossings on side edges, crossing
//                          parameters off the edge/midline segments, or a
//                          wrong crossing count — e.g. self-intersecting
//                          input order).

/** Why a spread split was rejected. */
enum class SplitRejectionReason {
    PARALLEL_EDGES,
    INTERSECTION_OFF_QUAD,
    COLLINEAR_POINTS,
}

/** Result of planning a book-spread split: Split or Degenerate. */
sealed class SplitResult {
    data class Split(val leftQuad: ModeQuad, val rightQuad: ModeQuad) :
        SplitResult()

    data class Degenerate(val reason: SplitRejectionReason) : SplitResult()
}

/** Pure book-spread split planner (line-quad intersection). */
object SpreadSplitPlanner {

    /** Point-set degeneracy floor, shared with [ModeQuad]. */
    const val AREA_EPSILON = ModeQuad.AREA_EPSILON

    /** Midpoint-line length floor (input-space units). */
    const val LENGTH_EPSILON = 1e-12

    /** Parametric (s, t) slack for on-segment membership tests. */
    const val PARAM_EPSILON = 1e-9

    /**
     * Plans the LEFT/RIGHT page split of a spread quad given in canonical
     * TL, TR, BR, BL order. Pure; deterministic; never throws.
     */
    fun planSplit(quad: ModeQuad): SplitResult {
        val pts = quad.corners
        for (i in pts.indices) {
            for (j in i + 1 until pts.size) {
                if (pts[i] == pts[j]) {
                    return SplitResult.Degenerate(
                        SplitRejectionReason.COLLINEAR_POINTS,
                    )
                }
            }
        }
        if (abs(quad.signedArea()) < AREA_EPSILON) {
            return SplitResult.Degenerate(SplitRejectionReason.COLLINEAR_POINTS)
        }
        val m1 = quad.topLeft.midpoint(quad.topRight)
        val m2 = quad.bottomLeft.midpoint(quad.bottomRight)
        if (m1.distanceTo(m2) < LENGTH_EPSILON) {
            return SplitResult.Degenerate(SplitRejectionReason.COLLINEAR_POINTS)
        }
        val lineDir = m2 - m1

        // Edge index 0 = top (TL->TR), 1 = right (TR->BR),
        // 2 = bottom (BR->BL), 3 = left (BL->TL).
        data class Crossing(
            val edgeIndex: Int,
            val s: Double,
            val t: Double,
            val point: ModePoint,
        )
        val crossings = mutableListOf<Crossing>()
        for (edgeIndex in 0 until 4) {
            val e0 = pts[edgeIndex]
            val e1 = pts[(edgeIndex + 1) % 4]
            val edgeDir = e1 - e0
            val denom = cross(edgeDir, lineDir)
            if (abs(denom) < PARAM_EPSILON) {
                val collinear = abs(cross(edgeDir, m1 - e0)) < PARAM_EPSILON
                if (collinear) {
                    return SplitResult.Degenerate(
                        SplitRejectionReason.PARALLEL_EDGES,
                    )
                }
                continue
            }
            val w = m1 - e0
            val s = cross(w, lineDir) / denom
            val t = cross(w, edgeDir) / denom
            if (s < -PARAM_EPSILON || s > 1.0 + PARAM_EPSILON) continue
            crossings.add(Crossing(edgeIndex, s, t, e0 + edgeDir * s))
        }

        // Exactly one crossing on the top edge and one on the bottom edge,
        // both within the midpoint-line segment (t in [0, 1]).
        val top = crossings.filter { it.edgeIndex == 0 }
        val bottom = crossings.filter { it.edgeIndex == 2 }
        val side = crossings.filter { it.edgeIndex == 1 || it.edgeIndex == 3 }
        if (side.isNotEmpty() ||
            top.size != 1 ||
            bottom.size != 1 ||
            crossings.size != 2
        ) {
            return SplitResult.Degenerate(
                SplitRejectionReason.INTERSECTION_OFF_QUAD,
            )
        }
        val a = top[0]
        val b = bottom[0]
        if (a.t < -PARAM_EPSILON || a.t > 1.0 + PARAM_EPSILON ||
            b.t < -PARAM_EPSILON || b.t > 1.0 + PARAM_EPSILON
        ) {
            return SplitResult.Degenerate(
                SplitRejectionReason.INTERSECTION_OFF_QUAD,
            )
        }
        val leftQuad = ModeQuad(
            topLeft = quad.topLeft,
            topRight = a.point,
            bottomRight = b.point,
            bottomLeft = quad.bottomLeft,
        )
        val rightQuad = ModeQuad(
            topLeft = a.point,
            topRight = quad.topRight,
            bottomRight = quad.bottomRight,
            bottomLeft = b.point,
        )
        return SplitResult.Split(leftQuad, rightQuad)
    }
}
