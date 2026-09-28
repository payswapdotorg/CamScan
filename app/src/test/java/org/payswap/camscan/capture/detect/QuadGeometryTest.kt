package org.payswap.camscan.capture.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAMSCAN-PROD-002 §6.7 — exhaustive QuadGeometry suite (pure JUnit 4, no
 * android.* imports). The geometry helpers are shared by the detector and
 * the stabilizer; their contracts are pinned here.
 */
class QuadGeometryTest {

    private val rect = listOf(
        Corner(100, 60),
        Corner(300, 60),
        Corner(300, 200),
        Corner(100, 200),
    )

    // A perspective quad (no parallel sides) — ordering must be permutation-
    // invariant for general convex quads, not just rectangles.
    private val perspective = listOf(
        Corner(140, 80),
        Corner(360, 60),
        Corner(330, 300),
        Corner(110, 280),
    )

    // ------------------------------------------------------------------ ordering

    @Test
    fun `orderCorners rect all 24 permutations yield canonical TL TR BR BL`() {
        for (permutation in permutations(rect)) {
            val ordered = QuadGeometry.orderCorners(permutation)
            assertEquals(
                "permutation ${permutation.map { "${it.x},${it.y}" }} must canonicalize",
                canonical(rect),
                canonical(ordered),
            )
        }
    }

    @Test
    fun `orderCorners perspective all 24 permutations agree`() {
        val canonicalOnce = canonical(QuadGeometry.orderCorners(perspective))
        for (permutation in permutations(perspective)) {
            val ordered = QuadGeometry.orderCorners(permutation)
            assertEquals(canonicalOnce, canonical(ordered))
        }
    }

    @Test
    fun `orderCorners canonical order is TL TR BR BL by construction`() {
        val ordered = QuadGeometry.orderCorners(
            listOf(
                Corner(100, 200), // BL fed first
                Corner(300, 200), // BR
                Corner(300, 60), // TR
                Corner(100, 60), // TL
            ),
        )
        // TL first (smallest y, then smallest x), then TR, BR, BL.
        assertEquals(Corner(100, 60), ordered[0])
        assertEquals(Corner(300, 60), ordered[1])
        assertEquals(Corner(300, 200), ordered[2])
        assertEquals(Corner(100, 200), ordered[3])
    }

    @Test
    fun `orderCorners malformed input returned unchanged`() {
        val three = listOf(Corner(0, 0), Corner(1, 1), Corner(2, 2))
        assertEquals(three, QuadGeometry.orderCorners(three))
    }

    // ------------------------------------------------------------------ convexity

    @Test
    fun `isConvex true for rectangle in canonical order`() {
        assertTrue(QuadGeometry.isConvex(rect))
    }

    @Test
    fun `isConvex true for perspective quad`() {
        assertTrue(QuadGeometry.isConvex(QuadGeometry.orderCorners(perspective)))
    }

    @Test
    fun `isConvex false for bowtie ordering`() {
        // TL, TR, BL, BR: edges cross — self-intersecting, not convex.
        val bowtie = listOf(
            Corner(100, 60),
            Corner(300, 60),
            Corner(100, 200),
            Corner(300, 200),
        )
        assertFalse(QuadGeometry.isConvex(bowtie))
    }

    @Test
    fun `isConvex false for collinear triple`() {
        val collinear = listOf(
            Corner(0, 0),
            Corner(10, 10), // on the diagonal
            Corner(100, 100),
            Corner(0, 100),
        )
        assertFalse(QuadGeometry.isConvex(collinear))
    }

    @Test
    fun `isConvex false for wrong size`() {
        assertFalse(QuadGeometry.isConvex(rect.take(3)))
        assertFalse(QuadGeometry.isConvex(rect + Corner(5, 5)))
    }

    // ------------------------------------------------------------------ area

    @Test
    fun `area of 10x10 rectangle is 100`() {
        val square = listOf(
            Corner(0, 0),
            Corner(10, 0),
            Corner(10, 10),
            Corner(0, 10),
        )
        assertEquals(100.0, QuadGeometry.area(square), 1e-9)
    }

    @Test
    fun `area of 4x3 rectangle is 12`() {
        val quad = listOf(
            Corner(0, 0),
            Corner(4, 0),
            Corner(4, 3),
            Corner(0, 3),
        )
        assertEquals(12.0, QuadGeometry.area(quad), 1e-9)
    }

    @Test
    fun `area of degenerate collinear quad is zero`() {
        val degenerate = listOf(
            Corner(0, 0),
            Corner(10, 10),
            Corner(20, 20),
            Corner(30, 30),
        )
        assertEquals(0.0, QuadGeometry.area(degenerate), 1e-9)
    }

    @Test
    fun `area of malformed input is zero`() {
        assertEquals(0.0, QuadGeometry.area(rect.take(3)), 1e-9)
    }

    @Test
    fun `area is orientation independent`() {
        assertEquals(QuadGeometry.area(rect), QuadGeometry.area(rect.reversed()), 1e-9)
    }

    // ------------------------------------------------------------------ IoU

    private val squareA = listOf(
        Corner(0, 0),
        Corner(10, 0),
        Corner(10, 10),
        Corner(0, 10),
    )

    private val squareHalfOverlap = listOf(
        Corner(5, 0),
        Corner(15, 0),
        Corner(15, 10),
        Corner(5, 10),
    )

    private val squareFarAway = listOf(
        Corner(100, 100),
        Corner(110, 100),
        Corner(110, 110),
        Corner(100, 110),
    )

    @Test
    fun `iou of identical quads is exactly one`() {
        assertEquals(1.0, QuadGeometry.iou(squareA, squareA.toList()), 1e-12)
    }

    @Test
    fun `iou of disjoint quads is zero`() {
        assertEquals(0.0, QuadGeometry.iou(squareA, squareFarAway), 1e-12)
    }

    @Test
    fun `iou of half-overlapping unit squares is one third`() {
        // Intersection 5x10 = 50, union 100 + 100 - 50 = 150 -> 1/3.
        assertEquals(1.0 / 3.0, QuadGeometry.iou(squareA, squareHalfOverlap), 1e-9)
    }

    @Test
    fun `iou is symmetric`() {
        assertEquals(
            QuadGeometry.iou(squareA, squareHalfOverlap),
            QuadGeometry.iou(squareHalfOverlap, squareA),
            1e-12,
        )
    }

    @Test
    fun `iou of degenerate or malformed quads is zero`() {
        val degenerate = listOf(
            Corner(0, 0),
            Corner(10, 10),
            Corner(20, 20),
            Corner(30, 30),
        )
        assertEquals(0.0, QuadGeometry.iou(degenerate, squareA), 1e-12)
        assertEquals(0.0, QuadGeometry.iou(squareA.take(3), squareA), 1e-12)
    }

    @Test
    fun `iou of translated quad matches hand-computed value`() {
        // squareA 0..10 shifted by (2, 2): intersection 8x8 = 64,
        // union 100 + 100 - 64 = 136.
        val shifted = listOf(
            Corner(2, 2),
            Corner(12, 2),
            Corner(12, 12),
            Corner(2, 12),
        )
        assertEquals(64.0 / 136.0, QuadGeometry.iou(squareA, shifted), 1e-9)
    }

    // ------------------------------------------------------------------ cornerDistances / aspectRatio / distance

    @Test
    fun `cornerDistances pairwise index-aligned`() {
        val a = listOf(
            Corner(0, 0),
            Corner(10, 0),
            Corner(10, 10),
            Corner(0, 10),
        )
        val b = listOf(
            Corner(3, 4),
            Corner(10, 0),
            Corner(13, 10),
            Corner(0, 22),
        )
        val distances = QuadGeometry.cornerDistances(a, b)
        assertEquals(4, distances.size)
        assertEquals(5.0, distances[0], 1e-9) // 3-4-5
        assertEquals(0.0, distances[1], 1e-9)
        assertEquals(3.0, distances[2], 1e-9)
        assertEquals(12.0, distances[3], 1e-9)
    }

    @Test
    fun `cornerDistances malformed input is empty`() {
        assertTrue(QuadGeometry.cornerDistances(rect.take(3), rect).isEmpty())
    }

    @Test
    fun `aspectRatio of 200x100 rect is 2`() {
        val wide = listOf(
            Corner(0, 0),
            Corner(200, 0),
            Corner(200, 100),
            Corner(0, 100),
        )
        assertEquals(2.0, QuadGeometry.aspectRatio(wide), 1e-9)
    }

    @Test
    fun `aspectRatio of square is 1`() {
        assertEquals(1.0, QuadGeometry.aspectRatio(squareA), 1e-9)
    }

    @Test
    fun `aspectRatio malformed or degenerate is 0`() {
        assertEquals(0.0, QuadGeometry.aspectRatio(rect.take(3)), 1e-9)
        val degenerate = listOf(
            Corner(0, 0),
            Corner(10, 10),
            Corner(20, 20),
            Corner(30, 30),
        )
        assertEquals(0.0, QuadGeometry.aspectRatio(degenerate), 1e-9)
    }

    @Test
    fun `distance is euclidean`() {
        assertEquals(5.0, QuadGeometry.distance(Corner(0, 0), Corner(3, 4)), 1e-9)
        assertEquals(0.0, QuadGeometry.distance(Corner(7, 8), Corner(7, 8)), 1e-9)
    }

    // ------------------------------------------------------------------ helpers

    /** Renders a canonical-order quad as a comparable string tuple list. */
    private fun canonical(quad: List<Corner>): List<String> = quad.map { "${it.x},${it.y}" }

    /** All 24 permutations of a 4-element list (deterministic recursion). */
    private fun <T> permutations(items: List<T>): List<List<T>> {
        val out = mutableListOf<List<T>>()
        fun walk(remaining: List<T>, acc: List<T>) {
            if (remaining.isEmpty()) {
                out.add(acc)
                return
            }
            for (item in remaining) {
                walk(remaining - item, acc + item)
            }
        }
        walk(items, emptyList())
        return out
    }
}
