package org.payswap.camscan.capture.detect


import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


/**
 * CAMSCAN-PROD-002 §6.4 — quad geometry, pure Kotlin, android-free.
 *
 * One implementation used by BOTH the detector (quad hypothesis, area,
 * regularity inputs) and the stabilizer (agreement metrics): canonical corner
 * ordering, strict convexity, shoelace area, quad-vs-quad IoU (exact
 * Sutherland–Hodgman clipping on convex quads), aspect ratio and per-corner
 * distance. Exhaustively tested in QuadGeometryTest.
 *
 * Defensive discipline (never throws on the scan path): malformed inputs —
 * not exactly 4 corners, degenerate/zero-area quads — return safe neutral
 * values (unchanged list, area 0, IoU 0, ratio 0, empty distance list) so a
 * bad detection degrades into a miss rather than a crash.
 */
object QuadGeometry {

    /** Canonical corner order: TL, TR, BR, BL by angle around the centroid. */
    fun orderCorners(points: List<Corner>): List<Corner> {
        if (points.size != CORNER_COUNT) {
            // Malformed: returned unchanged (defensive, documented; callers
            // treat non-canonical quads as misses downstream).
            return points.toList()
        }
        val cx = points.sumOf { it.x } / CORNER_COUNT.toDouble()
        val cy = points.sumOf { it.y } / CORNER_COUNT.toDouble()
        // Image coordinates are y-down, so ascending atan2 angle starting
        // below the +x axis sweeps clockwise from the most-negative angle:
        // TL (~ -135°) < TR (~ -45°) < BR (~ 45°) < BL (~ 135°). Ties (rare
        // symmetric/degenerate shapes) break on y then x — fully
        // deterministic and permutation-invariant.
        return points.sortedWith(
            compareBy(
                { atan2(it.y - cy, it.x - cx) },
                { it.y },
                { it.x },
            ),
        )
    }

    /**
     * Strict convexity for a 4-point polygon given in perimeter order (the
     * canonical TL, TR, BR, BL order is perimeter order). Collinear or
     * "bowtie" orderings are not convex here.
     */
    fun isConvex(quad: List<Corner>): Boolean {
        if (quad.size != CORNER_COUNT) return false
        var positive = false
        var negative = false
        for (i in 0 until CORNER_COUNT) {
            val p0 = quad[(i + 3) % CORNER_COUNT]
            val p1 = quad[i]
            val p2 = quad[(i + 1) % CORNER_COUNT]
            val cross = crossProduct(p0, p1, p2)
            if (cross == 0L) return false // collinear triple: degenerate
            if (cross > 0L) positive = true else negative = true
            if (positive && negative) return false // reflex or self-crossing
        }
        return positive || negative
    }

    /** Signed shoelace area (sign encodes orientation); 0.0 for malformed input. */
    fun signedArea(quad: List<Corner>): Double = when {
        quad.size != CORNER_COUNT -> 0.0
        else -> signedArea(quad[0], quad[1], quad[2], quad[3])
    }

    /** Unsigned shoelace area; 0.0 for malformed or degenerate input. */
    fun area(quad: List<Corner>): Double = abs(signedArea(quad))

    private fun signedArea(a: Corner, b: Corner, c: Corner, d: Corner): Double {
        // Long accumulation: Int*Int products overflow Int, never Long.
        val sum = (
            a.x.toLong() * b.y - b.x.toLong() * a.y +
                b.x.toLong() * c.y - c.x.toLong() * b.y +
                c.x.toLong() * d.y - d.x.toLong() * c.y +
                d.x.toLong() * a.y - a.x.toLong() * d.y
            )
        return sum / 2.0
    }

    /** Allocation-free area for hot loops (the detector's quad search). */
    fun area(a: Corner, b: Corner, c: Corner, d: Corner): Double = abs(signedArea(a, b, c, d))

    private fun crossProduct(o: Corner, a: Corner, b: Corner): Long =
        (a.x - o.x).toLong() * (b.y - o.y) - (a.y - o.y).toLong() * (b.x - o.x)

    /**
     * Quad-vs-quad IoU: intersection over union of the two polygon areas.
     * Convex quads are clipped exactly (Sutherland–Hodgman); corners are
     * Int-valued so all intermediate cross products are exact in Double —
     * identical quads clip to themselves and IoU is exactly 1.0. Disjoint or
     * degenerate quads return 0.0.
     */
    fun iou(quadA: List<Corner>, quadB: List<Corner>): Double {
        if (quadA.size != CORNER_COUNT || quadB.size != CORNER_COUNT) return 0.0
        val a = orientedCorners(quadA) ?: return 0.0
        val b = orientedCorners(quadB) ?: return 0.0
        val intersection = clipConvex(a, b)
        if (intersection.size < 3) return 0.0
        val intersectionArea = polygonArea(intersection)
        val areaA = polygonArea(a)
        val areaB = polygonArea(b)
        val union = areaA + areaB - intersectionArea
        return if (union <= EPSILON) 0.0 else intersectionArea / union
    }

    /**
     * Aspect ratio (>= 1.0) of the quad's average side pairs:
     * width = avg(top, bottom), height = avg(left, right), ratio = max/min.
     * Degenerate (zero-area or zero-side) quads return 0.0.
     */
    fun aspectRatio(quad: List<Corner>): Double {
        if (quad.size != CORNER_COUNT) return 0.0
        if (abs(signedArea(quad)) <= EPSILON) return 0.0
        val top = distance(quad[0], quad[1])
        val bottom = distance(quad[2], quad[3])
        val left = distance(quad[3], quad[0])
        val right = distance(quad[1], quad[2])
        val w = (top + bottom) / 2.0
        val h = (left + right) / 2.0
        val smaller = min(w, h)
        return if (smaller <= EPSILON) 0.0 else max(w, h) / smaller
    }

    /** Euclidean distance between two corners. */
    fun distance(a: Corner, b: Corner): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /**
     * Per-corner distances between two index-aligned (canonical-order) quads:
     * [TL↔TL, TR↔TR, BR↔BR, BL↔BL]. Malformed sizes return an empty list.
     */
    fun cornerDistances(quadA: List<Corner>, quadB: List<Corner>): List<Double> {
        if (quadA.size != CORNER_COUNT || quadB.size != CORNER_COUNT) return emptyList()
        return (0 until CORNER_COUNT).map { distance(quadA[it], quadB[it]) }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Normalizes a corner list to consistent orientation (positive signed
     * area under the shoelace formula) and rejects degenerate quads.
     */
    private fun orientedCorners(quad: List<Corner>): List<Corner>? {
        val signed = signedArea(quad)
        if (abs(signed) <= EPSILON) return null
        return if (signed > 0.0) quad.toList() else quad.reversed()
    }

    /** Sutherland–Hodgman clip of a consistently-oriented subject by a convex clip polygon. */
    private fun clipConvex(subject: List<Corner>, clip: List<Corner>): List<Corner> {
        var output = subject.map { Point(it.x.toDouble(), it.y.toDouble()) }
        for (i in clip.indices) {
            if (output.isEmpty()) break
            val edgeStart = clip[i]
            val edgeEnd = clip[(i + 1) % clip.size]
            val input = output
            output = mutableListOf()
            for (j in input.indices) {
                val current = input[j]
                val previous = input[(j + input.size - 1) % input.size]
                val currentInside = isInside(current, edgeStart, edgeEnd)
                val previousInside = isInside(previous, edgeStart, edgeEnd)
                when {
                    previousInside && currentInside -> output.add(current)
                    previousInside && !currentInside -> output.add(intersect(previous, current, edgeStart, edgeEnd))
                    !previousInside && currentInside -> {
                        output.add(intersect(previous, current, edgeStart, edgeEnd))
                        output.add(current)
                    }

                    else -> Unit // both outside: nothing emitted
                }
            }
        }
        // Int-valued doubles (the only values clipping exact Int-corner
        // vertices can produce) round back exactly; general values are clamped.
        return output.map { Corner(it.x.roundToInt(), it.y.roundToInt()) }
    }

    /** Points on the clip-side of the directed edge (consistently oriented polygons). */
    private fun isInside(p: Point, a: Corner, b: Corner): Boolean {
        val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        return cross >= -COLLINEAR_EPSILON
    }

    private fun intersect(s: Point, e: Point, a: Corner, b: Corner): Point {
        val dcx = (b.x - a.x).toDouble()
        val dcy = (b.y - a.y).toDouble()
        val dx = e.x - s.x
        val dy = e.y - s.y
        val denominator = dx * dcy - dy * dcx
        if (abs(denominator) < EPSILON) return s // parallel — clamp to the segment start
        val t = ((a.x - s.x) * dcy - (a.y - s.y) * dcx) / denominator
        val clamped = t.coerceIn(0.0, 1.0)
        return Point(s.x + clamped * dx, s.y + clamped * dy)
    }

    /** Shoelace area over Int corners — Long accumulation is exact. */
    private fun polygonArea(polygon: List<Corner>): Double {
        var sum = 0L
        for (i in polygon.indices) {
            val p = polygon[i]
            val q = polygon[(i + 1) % polygon.size]
            sum += p.x.toLong() * q.y - q.x.toLong() * p.y
        }
        return abs(sum / 2.0)
    }

    private data class Point(val x: Double, val y: Double)

    private const val CORNER_COUNT = 4
    private const val EPSILON = 1e-9
    private const val COLLINEAR_EPSILON = 1e-7
}
