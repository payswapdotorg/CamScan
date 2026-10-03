package org.payswap.camscan.tools.modes

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

// CAMSCAN-PROD-012 §6.2/§6.3/§6.4 — geometry primitives for the specialist
// scan-mode tree. Pure Kotlin + JVM stdlib only: zero android.* imports, zero
// dependencies outside kotlin stdlib; wave-4 UI/camera wiring consumes these
// read-only. Coordinate convention: x grows right, y grows DOWN (preview /
// image space), matching the processing tree's convention.

/** Immutable 2D point in Double space (x right, y down). Pure value type. */
data class ModePoint(val x: Double, val y: Double) {

    operator fun plus(o: ModePoint): ModePoint = ModePoint(x + o.x, y + o.y)

    operator fun minus(o: ModePoint): ModePoint = ModePoint(x - o.x, y - o.y)

    operator fun times(s: Double): ModePoint = ModePoint(x * s, y * s)

    // Exact-halves midpoint; deterministic (no rounding policy needed beyond
    // Double arithmetic itself).
    fun midpoint(o: ModePoint): ModePoint =
        ModePoint((x + o.x) / 2.0, (y + o.y) / 2.0)

    fun distanceTo(o: ModePoint): Double {
        val dx = x - o.x
        val dy = y - o.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        val ZERO: ModePoint = ModePoint(0.0, 0.0)
    }
}

/** Cross product of the 2D vectors (a x b) — z component of the 3D cross. */
internal fun cross(a: ModePoint, b: ModePoint): Double = a.x * b.y - a.y * b.x

/** Axis-aligned bounding box of a point set (min/max corners). Pure. */
data class ModeBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

/**
 * Quad of four [ModePoint] corners in CANONICAL order TL, TR, BR, BL
 * (y-down). The detection pipeline's edge ordering produces this order;
 * [fromPoints] applies the same discipline to arbitrary input order.
 */
data class ModeQuad(
    val topLeft: ModePoint,
    val topRight: ModePoint,
    val bottomRight: ModePoint,
    val bottomLeft: ModePoint,
) {

    val corners: List<ModePoint>
        get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Signed shoelace area; positive for the canonical clockwise order. */
    fun signedArea(): Double {
        var sum = 0.0
        val pts = corners
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x * b.y - a.y * b.x
        }
        return sum / 2.0
    }

    /** Axis-aligned bounding box of the four corners (order-independent). */
    fun boundingBox(): ModeBox {
        var minX = topLeft.x
        var minY = topLeft.y
        var maxX = topLeft.x
        var maxY = topLeft.y
        for (p in corners) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return ModeBox(minX, minY, maxX, maxY)
    }

    /**
     * Width/height aspect ratio of the axis-aligned bounding box AFTER
     * canonical edge ordering (the §6.2 observed aspect). Always positive
     * for a non-degenerate quad.
     */
    fun boundingBoxAspect(): Double {
        val box = boundingBox()
        return box.width / box.height
    }

    /**
     * True when all four consecutive edge turns have the same strict sign
     * (strictly convex; collinear or reflex quads are not convex). Mirrors
     * the processing tree's isConvex discipline without importing it.
     */
    fun isConvex(): Boolean {
        val pts = corners
        var pos = 0
        var neg = 0
        for (i in pts.indices) {
            val o = pts[i]
            val a = pts[(i + 1) % pts.size]
            val b = pts[(i + 2) % pts.size]
            val c = cross(a - o, b - a)
            if (c > 0.0) pos++
            if (c < 0.0) neg++
        }
        return pos == 4 || neg == 4
    }

    companion object {

        // Point-set degeneracy floor: quads whose |area| is below this are
        // treated as collinear (squared input units; documented §6.2).
        const val AREA_EPSILON = 1e-12

        /**
         * Canonicalizes four arbitrary points into TL, TR, BR, BL order:
         * centroid-angle ordering with (y, x) tie-break (the processing
         * tree's canonical discipline; with y-down coordinates ascending
         * angle order is TL, TR, BR, BL directly). Returns null when the
         * input cannot form a quad: wrong count, duplicate points (exact
         * equality), or a point set that is collinear (|shoelace area|
         * below [AREA_EPSILON]). Never throws.
         */
        fun fromPoints(points: List<ModePoint>): ModeQuad? {
            if (points.size != 4) return null
            for (i in points.indices) {
                for (j in i + 1 until points.size) {
                    if (points[i] == points[j]) return null
                }
            }
            var cx = 0.0
            var cy = 0.0
            for (p in points) {
                cx += p.x
                cy += p.y
            }
            cx /= 4.0
            cy /= 4.0
            val centroid = ModePoint(cx, cy)
            val ordered = points.sortedWith(
                compareBy(
                    { p: ModePoint -> atan2(p.y - centroid.y, p.x - centroid.x) },
                    { p: ModePoint -> p.y },
                    { p: ModePoint -> p.x },
                )
            )
            val quad = ModeQuad(ordered[0], ordered[1], ordered[2], ordered[3])
            if (abs(quad.signedArea()) < AREA_EPSILON) return null
            return quad
        }
    }
}
