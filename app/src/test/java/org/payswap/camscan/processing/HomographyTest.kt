package org.payswap.camscan.processing


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * CAMSCAN-PROD-003 §6.7 — Homography coverage: identity quads, exact
 * axis-aligned scale/translate systems, perspective corner mapping
 * (1e-3), inversion round-trips, and degenerate (collinear / coincident
 * / wrong-size) rejection.
 */
class HomographyTest {

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(
            "expected $expected but was $actual (tolerance $tolerance)",
            kotlin.math.abs(expected - actual) <= tolerance,
        )
    }

    private fun assertMatrix(h: Homography, m: DoubleArray, tolerance: Double = 1e-9) {
        for (i in 0 until 9) {
            assertNear(m[i], h.matrix[i], tolerance)
        }
        assertEquals(1.0, h.matrix[8], 0.0)
    }

    private val unitSquare = listOf(
        PointD(0.0, 0.0),
        PointD(1.0, 0.0),
        PointD(1.0, 1.0),
        PointD(0.0, 1.0),
    )

    @Test
    fun identityQuadYieldsIdentityMatrix() {
        val h = Homography.solve(unitSquare, unitSquare)
        assertNotNull(h)
        assertMatrix(h!!, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun axisAlignedScaleTranslateIsExact() {
        // src (0,0),(2,0),(2,2),(0,2) -> dst: x' = 2x + 10, y' = y + 20.
        val src = listOf(
            PointD(0.0, 0.0),
            PointD(2.0, 0.0),
            PointD(2.0, 2.0),
            PointD(0.0, 2.0),
        )
        val dst = listOf(
            PointD(10.0, 20.0),
            PointD(14.0, 20.0),
            PointD(14.0, 22.0),
            PointD(10.0, 22.0),
        )
        val h = Homography.solve(src, dst)
        assertNotNull(h)
        assertMatrix(h!!, doubleArrayOf(2.0, 0.0, 10.0, 0.0, 1.0, 20.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun perspectiveQuadMapsCornersWithin1eMinus3() {
        val dst = listOf(
            PointD(0.0, 0.0),
            PointD(10.0, 0.0),
            PointD(12.0, 8.0),
            PointD(0.0, 6.0),
        )
        val h = Homography.solve(unitSquare, dst)
        assertNotNull(h)
        for (i in 0 until 4) {
            val mapped = h!!.map(unitSquare[i].x, unitSquare[i].y)
            assertNear(dst[i].x, mapped.x, 1e-3)
            assertNear(dst[i].y, mapped.y, 1e-3)
        }
    }

    @Test
    fun identityMapFixesInteriorPoints() {
        val h = Homography.solve(unitSquare, unitSquare)!!
        val p = h.map(3.5, 4.25)
        assertNear(3.5, p.x)
        assertNear(4.25, p.y)
    }

    @Test
    fun invertRoundTripsPoints() {
        val dst = listOf(
            PointD(0.0, 0.0),
            PointD(10.0, 0.0),
            PointD(12.0, 8.0),
            PointD(0.0, 6.0),
        )
        val h = Homography.solve(unitSquare, dst)!!
        val inverse = h.invert()
        assertNotNull(inverse)
        val probes = listOf(PointD(0.5, 0.5), PointD(2.25, 1.75), PointD(1.0, 0.1))
        for (p in probes) {
            val mapped = h.map(p.x, p.y)
            val roundTripped = inverse!!.map(mapped.x, mapped.y)
            assertNear(p.x, roundTripped.x, 1e-6)
            assertNear(p.y, roundTripped.y, 1e-6)
        }
    }

    @Test
    fun doubleInvertRestoresMatrix() {
        val dst = listOf(
            PointD(0.0, 0.0),
            PointD(10.0, 0.0),
            PointD(12.0, 8.0),
            PointD(0.0, 6.0),
        )
        val h = Homography.solve(unitSquare, dst)!!
        val restored = h.invert()!!.invert()
        assertNotNull(restored)
        for (i in 0 until 9) {
            assertNear(h.matrix[i], restored!!.matrix[i], 1e-6)
        }
    }

    @Test
    fun collinearQuadIsRejected() {
        // All four points on y = x: the 8x8 system is singular (the
        // (c, -c) null direction fixes the whole line pointwise).
        val collinear = listOf(
            PointD(0.0, 0.0),
            PointD(1.0, 1.0),
            PointD(2.0, 2.0),
            PointD(3.0, 3.0),
        )
        assertNull(Homography.solve(collinear, collinear))
    }

    @Test
    fun collinearDestinationIsRejected() {
        val dst = listOf(
            PointD(0.0, 0.0),
            PointD(1.0, 0.0),
            PointD(2.0, 0.0),
            PointD(3.0, 0.0),
        )
        assertNull(Homography.solve(unitSquare, dst))
    }

    @Test
    fun coincidentPointsAreRejected() {
        val duplicate = listOf(
            PointD(0.0, 0.0),
            PointD(0.0, 0.0),
            PointD(1.0, 1.0),
            PointD(0.0, 1.0),
        )
        assertNull(Homography.solve(duplicate, duplicate))
    }

    @Test
    fun wrongPointSizeIsRejected() {
        val three = listOf(PointD(0.0, 0.0), PointD(1.0, 0.0), PointD(1.0, 1.0))
        assertNull(Homography.solve(three, three))
        assertNull(Homography.solve(unitSquare, three))
    }
}
