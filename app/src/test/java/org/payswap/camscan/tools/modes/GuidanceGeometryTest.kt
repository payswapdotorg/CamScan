package org.payswap.camscan.tools.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-012 §7 — guidance-geometry coverage: largest centered rect
// math (portrait / landscape / square viewports), the documented margin,
// corner-delta signs, round-half-up output rounding, determinism.

class GuidanceGeometryTest {

    private val idAspect = IdCardMode.targetAspect
    private val spreadAspect = BookSpreadMode.targetAspect

    private fun quad(
        x0: Double, y0: Double, x1: Double, y1: Double,
    ) = ModeQuad(
        ModePoint(x0, y0),
        ModePoint(x1, y0),
        ModePoint(x1, y1),
        ModePoint(x0, y1),
    )

    // ------------------------------------------------ largest centered rect

    @Test
    fun portraitViewportWidthBoundsTheIdCardFrame() {
        val r = GuidanceGeometry.computeGuidance(
            idAspect, 1080.0, 1920.0, quad(10.0, 300.0, 1000.0, 1500.0),
        )
        assertEquals(54.0, r!!.frame.x, 0.0)
        assertEquals(653.5247663551402, r.frame.y, 1e-9)
        assertEquals(972.0, r.frame.width, 0.0)
        assertEquals(612.9504672897197, r.frame.height, 1e-9)
    }

    @Test
    fun landscapeViewportHeightBoundsTheIdCardFrame() {
        val r = GuidanceGeometry.computeGuidance(
            idAspect, 1920.0, 1080.0, quad(100.0, 10.0, 1500.0, 1000.0),
        )
        assertEquals(189.31456094849943, r!!.frame.x, 1e-9)
        assertEquals(54.0, r.frame.y, 0.0)
        assertEquals(1541.3708781030011, r.frame.width, 1e-9)
        assertEquals(972.0, r.frame.height, 0.0)
    }

    @Test
    fun squareViewportWithSquareAspectFillsTheUsableArea() {
        val r = GuidanceGeometry.computeGuidance(
            1.0, 1000.0, 1000.0, quad(200.0, 200.0, 800.0, 800.0),
        )
        assertEquals(50.0, r!!.frame.x, 0.0)
        assertEquals(50.0, r.frame.y, 0.0)
        assertEquals(900.0, r.frame.width, 0.0)
        assertEquals(900.0, r.frame.height, 0.0)
    }

    @Test
    fun spreadAspectOnPortraitViewportIsWidthBound() {
        val r = GuidanceGeometry.computeGuidance(
            spreadAspect, 1080.0, 1920.0, quad(20.0, 400.0, 1060.0, 1600.0),
        )
        assertEquals(54.0, r!!.frame.x, 0.0)
        assertEquals(616.3636363636364, r.frame.y, 1e-9)
        assertEquals(972.0, r.frame.width, 0.0)
        assertEquals(687.2727272727273, r.frame.height, 1e-9)
    }

    @Test
    fun zeroMarginLetsTheFrameTouchTheViewportEdges() {
        val r = GuidanceGeometry.computeGuidance(
            1.0, 100.0, 100.0, quad(10.0, 10.0, 90.0, 90.0),
            marginFraction = 0.0,
        )
        assertEquals(0.0, r!!.frame.x, 0.0)
        assertEquals(0.0, r.frame.y, 0.0)
        assertEquals(100.0, r.frame.width, 0.0)
        assertEquals(100.0, r.frame.height, 0.0)
    }

    @Test
    fun marginFractionJustBelowHalfStillComputes() {
        val r = GuidanceGeometry.computeGuidance(
            1.0, 1000.0, 1000.0, quad(0.0, 0.0, 1000.0, 1000.0),
            marginFraction = 0.49,
        )
        // usable = 1000 * (1 - 0.98) = 20 -> centered at 490.
        assertEquals(490.0, r!!.frame.x, 1e-12)
        assertEquals(490.0, r.frame.y, 1e-12)
        assertEquals(20.0, r.frame.width, 1e-12)
        assertEquals(20.0, r.frame.height, 1e-12)
    }

    @Test
    fun defaultMarginFractionIsFivePercent() {
        assertEquals(0.05, GuidanceGeometry.DEFAULT_MARGIN_FRACTION, 0.0)
    }

    // ------------------------------------------------ invalid inputs -> null

    @Test
    fun halfMarginFractionIsRejected() {
        val r = GuidanceGeometry.computeGuidance(
            1.0, 1000.0, 1000.0, quad(0.0, 0.0, 100.0, 100.0),
            marginFraction = 0.5,
        )
        assertNull(r)
    }

    @Test
    fun negativeMarginFractionIsRejected() {
        val r = GuidanceGeometry.computeGuidance(
            1.0, 1000.0, 1000.0, quad(0.0, 0.0, 100.0, 100.0),
            marginFraction = -0.01,
        )
        assertNull(r)
    }

    @Test
    fun nonPositiveAspectIsRejected() {
        assertNull(GuidanceGeometry.computeGuidance(0.0, 100.0, 100.0, quad(0.0, 0.0, 10.0, 10.0)))
        assertNull(GuidanceGeometry.computeGuidance(-1.0, 100.0, 100.0, quad(0.0, 0.0, 10.0, 10.0)))
    }

    @Test
    fun nanAspectIsRejected() {
        assertNull(
            GuidanceGeometry.computeGuidance(
                Double.NaN, 100.0, 100.0, quad(0.0, 0.0, 10.0, 10.0),
            )
        )
    }

    @Test
    fun nonPositiveViewportIsRejected() {
        assertNull(GuidanceGeometry.computeGuidance(1.0, 0.0, 100.0, quad(0.0, 0.0, 10.0, 10.0)))
        assertNull(GuidanceGeometry.computeGuidance(1.0, 100.0, -5.0, quad(0.0, 0.0, 10.0, 10.0)))
    }

    @Test
    fun degenerateObservedQuadIsRejected() {
        val flat = ModeQuad(
            ModePoint(0.0, 0.0),
            ModePoint(10.0, 0.0),
            ModePoint(20.0, 0.0),
            ModePoint(30.0, 0.0),
        )
        assertNull(GuidanceGeometry.computeGuidance(1.0, 100.0, 100.0, flat))
    }

    // ------------------------------------------------ corner deltas

    @Test
    fun cornerDeltasCarryGuidanceMinusObservedSigns() {
        // Guidance (50,50)-(950,950); observed (200,200)-(800,800):
        // TL delta negative/negative, TR positive/negative,
        // BR positive/positive, BL negative/positive (y-down convention).
        val r = GuidanceGeometry.computeGuidance(
            1.0, 1000.0, 1000.0, quad(200.0, 200.0, 800.0, 800.0),
        )
        val deltas = r!!.cornerDeltas
        assertEquals(4, deltas.size)
        assertEquals(-150.0, deltas[0].dx, 0.0)
        assertEquals(-150.0, deltas[0].dy, 0.0)
        assertEquals(150.0, deltas[1].dx, 0.0)
        assertEquals(-150.0, deltas[1].dy, 0.0)
        assertEquals(150.0, deltas[2].dx, 0.0)
        assertEquals(150.0, deltas[2].dy, 0.0)
        assertEquals(-150.0, deltas[3].dx, 0.0)
        assertEquals(150.0, deltas[3].dy, 0.0)
    }

    @Test
    fun cornerDeltasAreZeroWhenObservedMatchesGuidanceExactly() {
        val r = GuidanceGeometry.computeGuidance(
            1.0, 1000.0, 1000.0, quad(50.0, 50.0, 950.0, 950.0),
        )
        for (delta in r!!.cornerDeltas) {
            assertEquals(0.0, delta.dx, 0.0)
            assertEquals(0.0, delta.dy, 0.0)
        }
    }

    @Test
    fun cornerDeltasMatchGuidanceFrameCorners() {
        val r = GuidanceGeometry.computeGuidance(
            idAspect, 1080.0, 1920.0, quad(0.0, 0.0, 1080.0, 1920.0),
        )
        val corners = r!!.frame.corners
        val observed = listOf(
            ModePoint(0.0, 0.0),
            ModePoint(1080.0, 0.0),
            ModePoint(1080.0, 1920.0),
            ModePoint(0.0, 1920.0),
        )
        for (i in corners.indices) {
            assertEquals(corners[i].x - observed[i].x, r.cornerDeltas[i].dx, 0.0)
            assertEquals(corners[i].y - observed[i].y, r.cornerDeltas[i].dy, 0.0)
        }
    }

    // ------------------------------------------------ output rounding

    @Test
    fun roundHalfUpRoundsTiesTowardPositiveInfinity() {
        assertEquals(1.0, roundHalfUp(0.5), 0.0)
        assertEquals(2.0, roundHalfUp(1.5), 0.0)
        assertEquals(3.0, roundHalfUp(2.5), 0.0)
        assertEquals(1.0, roundHalfUp(1.4), 0.0)
        assertEquals(0.0, roundHalfUp(-0.5), 0.0)
        assertEquals(-1.0, roundHalfUp(-1.5), 0.0)
        assertEquals(0.0, roundHalfUp(-0.4), 0.0)
    }

    @Test
    fun frameRoundsHalfPixelValuesUp() {
        // viewport 100 x 100, aspect 2.0, margin 5 percent:
        // usable 90 x 90 -> width 90, height 45, y = 27.5 -> 28.
        val r = GuidanceGeometry.computeGuidance(
            2.0, 100.0, 100.0, quad(0.0, 0.0, 100.0, 100.0),
        )
        assertEquals(27.5, r!!.frame.y, 0.0)
        assertEquals(28.0, r.frame.roundedY, 0.0)
        assertEquals(5.0, r.frame.roundedX, 0.0)
        assertEquals(45.0, r.frame.roundedHeight, 0.0)
    }

    @Test
    fun cornerDeltasRoundHalfUpForOutput() {
        val delta = CornerDelta(-150.5, 250.5)
        assertEquals(-150.0, delta.roundedDx, 0.0)
        assertEquals(251.0, delta.roundedDy, 0.0)
    }

    // ------------------------------------------------ determinism

    @Test
    fun guidanceComputationIsDeterministic() {
        val observed = quad(120.0, 400.0, 900.0, 1400.0)
        val a = GuidanceGeometry.computeGuidance(idAspect, 1080.0, 1920.0, observed)
        val b = GuidanceGeometry.computeGuidance(idAspect, 1080.0, 1920.0, observed)
        assertEquals(a, b)
    }

    @Test
    fun guidanceFrameCornersAreInCanonicalOrder() {
        val r = GuidanceGeometry.computeGuidance(
            1.0, 1000.0, 1000.0, quad(10.0, 10.0, 900.0, 900.0),
        )
        val corners = r!!.frame.corners
        assertEquals(ModePoint(50.0, 50.0), corners[0])
        assertEquals(ModePoint(950.0, 50.0), corners[1])
        assertEquals(ModePoint(950.0, 950.0), corners[2])
        assertEquals(ModePoint(50.0, 950.0), corners[3])
    }

    @Test
    fun guidanceResultExposesExactlyFourDeltasInCornerOrder() {
        val r = GuidanceGeometry.computeGuidance(
            idAspect, 1080.0, 1920.0, quad(0.0, 0.0, 1080.0, 1920.0),
        )
        assertEquals(4, r!!.cornerDeltas.size)
        assertTrue(r.cornerDeltas[0].dy > 0.0)
        assertTrue(r.cornerDeltas[1].dy > 0.0)
        assertTrue(r.cornerDeltas[2].dy < 0.0)
        assertTrue(r.cornerDeltas[3].dy < 0.0)
    }
}
