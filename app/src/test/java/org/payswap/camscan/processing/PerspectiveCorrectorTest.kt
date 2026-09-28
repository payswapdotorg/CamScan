package org.payswap.camscan.processing


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.PageEnhancementMode
import kotlin.math.sqrt

/*
 * CAMSCAN-PROD-003 §6.7 — PerspectiveCorrector coverage on hand-computed
 * fixtures.
 *
 * Fixture 1 — planar24: a 24x24 image with p(x, y) = 3 + 4x + 6y (max 233).
 * A linear image is bilinear-exact: sampling ANY position (sx, sy) yields
 * round(3 + 4 sx + 6 sy), so every warp value below is hand-derived from
 * the documented pixel-center geometry, edge-statistics dimensions, and
 * fx/fy bilinear weights.
 *
 * Fixture 2 — gradient4x4 (10..160): used for the MIN_OUTPUT_DIM clamp,
 * degeneracy, determinism, and non-destructiveness tests where exact
 * content is still hand-computable.
 */
class PerspectiveCorrectorTest {

    private val corrector: PerspectiveCorrector = BilinearPerspectiveCorrector()

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun lumaOf(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun planar24(): ImageBuffer {
        val argb = IntArray(24 * 24)
        for (y in 0 until 24) {
            for (x in 0 until 24) {
                argb[y * 24 + x] = gray(3 + 4 * x + 6 * y)
            }
        }
        return ImageBuffer(24, 24, argb)
    }

    private fun gradient4x4(): ImageBuffer {
        val rows = arrayOf(
            intArrayOf(10, 20, 30, 40),
            intArrayOf(50, 60, 70, 80),
            intArrayOf(90, 100, 110, 120),
            intArrayOf(130, 140, 150, 160),
        )
        val argb = IntArray(16)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                argb[y * 4 + x] = gray(rows[y][x])
            }
        }
        return ImageBuffer(4, 4, argb)
    }

    private fun quad(
        tlx: Float, tly: Float,
        trx: Float, trY: Float,
        brx: Float, bry: Float,
        blx: Float, bly: Float,
    ): QuadF = QuadF(
        CornerF(tlx, tly),
        CornerF(trx, trY),
        CornerF(brx, bry),
        CornerF(blx, bly),
    )

    @Test
    fun fullFrameQuadResamplesWithExactBilinearValues() {
        // Full-frame quad (0,0)-(23,23): pixel-center edges are 23 -> 23x23
        // output; output (x, y) samples source (23x/22, 23y/22).
        val result = corrector.correct(planar24(), quad(0f, 0f, 23f, 0f, 23f, 23f, 0f, 23f))
        assertNotNull(result)
        assertEquals(23, result!!.buffer.width)
        assertEquals(23, result.buffer.height)
        val argb = result.buffer.argb
        assertEquals(gray(3), argb[0])                    // (0,0) -> (0,0)
        assertEquals(gray(7), argb[1])                    // (1,0) -> 3 + 4*23/22 = 7.18
        assertEquals(gray(95), argb[22])                  // (22,0) -> (23,0)
        assertEquals(gray(118), argb[11 * 23 + 11])       // (11,11) -> (11.5,11.5): 3+46+69
        assertEquals(gray(68), argb[7 * 23 + 5])          // (5,7) -> 3 + 4*5.227 + 6*7.318
        assertEquals(gray(141), argb[22 * 23])            // (0,22) -> (0,23)
        assertEquals(gray(233), argb[22 * 23 + 22])       // (22,22) -> (23,23)
    }

    @Test
    fun axisAlignedSubQuadSamplesExactPixels() {
        // Quad (2,1)-(22,21): edges 20 -> 20x20 output; the target rect
        // maps corner-to-corner, so the four output corners sample exact
        // integer source pixels; (10,10) samples (12.526, 11.526) ->
        // 117 + 10*(10/19) = 122.26 -> 122.
        val result = corrector.correct(planar24(), quad(2f, 1f, 22f, 1f, 22f, 21f, 2f, 21f))
        assertNotNull(result)
        assertEquals(20, result!!.buffer.width)
        assertEquals(20, result.buffer.height)
        val argb = result.buffer.argb
        assertEquals(gray(17), argb[0])          // (0,0) -> (2,1)
        assertEquals(gray(97), argb[19])         // (19,0) -> (22,1)
        assertEquals(gray(137), argb[19 * 20])   // (0,19) -> (2,21)
        assertEquals(gray(217), argb[19 * 20 + 19]) // (19,19) -> (22,21)
        assertEquals(gray(122), argb[10 * 20 + 10]) // fractional interior sample
        assertEquals(PageEnhancementMode.ORIGINAL, result.enhancement)
        assertEquals(16, result.id.length)
    }

    @Test
    fun subPixelQuadAveragesFourNeighbors() {
        // Quad (0.5,0.5)-(16.5,16.5): edges 16 -> 16x16 output; the
        // [0,15] pixel-center rect maps onto [0.5,16.5] with scale 16/15,
        // so output (x,y) samples (0.5 + 16x/15, 0.5 + 16y/15) —
        // bilinear-exact value round(8 + 64x/15 + 96y/15). Corners land
        // on exact quarter-mixes, e.g. (15,0) -> (16.5, 0.5) =
        // (67+71+73+77)/4 = 72.
        val result = corrector.correct(
            planar24(),
            quad(0.5f, 0.5f, 16.5f, 0.5f, 16.5f, 16.5f, 0.5f, 16.5f),
        )
        assertNotNull(result)
        assertEquals(16, result!!.buffer.width)
        assertEquals(16, result.buffer.height)
        val argb = result.buffer.argb
        assertEquals(gray(8), argb[0])              // 8
        assertEquals(gray(12), argb[1])             // 8 + 64/15 = 12.27
        assertEquals(gray(72), argb[15])            // 8 + 64
        assertEquals(gray(104), argb[15 * 16])      // 8 + 96
        assertEquals(gray(83), argb[7 * 16 + 7])    // 8 + 448/15 + 672/15 = 82.67
        assertEquals(gray(168), argb[15 * 16 + 15]) // 8 + 64 + 96
    }

    @Test
    fun outputDimensionsComeFromEdgeStatisticsAndShuffledInputIsCanonicalized() {
        // Quad (0,0),(18,0),(21,20),(0,16): width = round((18+sqrt(457))/2)
        // = 20, height = round((16+sqrt(409))/2) = 18. The corners are in a
        // rotated slot assignment — canonicalization restores TL, TR, BR, BL.
        val shuffled = QuadF(
            topLeft = CornerF(21f, 20f),   // actually BR
            topRight = CornerF(0f, 0f),    // actually TL
            bottomRight = CornerF(0f, 16f), // actually BL
            bottomLeft = CornerF(18f, 0f),  // actually TR
        )
        val result = corrector.correct(planar24(), shuffled)
        assertNotNull(result)
        assertEquals(20, result!!.buffer.width)
        assertEquals(18, result.buffer.height)
        // The target (0,0) corner maps exactly onto canonical TL (0,0).
        assertEquals(gray(3), result.buffer.argb[0])
        // Retained geometry: canonical quad + aspect from edge means.
        assertEquals(CornerF(0f, 0f), result.geometry.sourceQuad.topLeft)
        assertEquals(CornerF(18f, 0f), result.geometry.sourceQuad.topRight)
        assertEquals(CornerF(21f, 20f), result.geometry.sourceQuad.bottomRight)
        assertEquals(CornerF(0f, 16f), result.geometry.sourceQuad.bottomLeft)
        val expectedAspect = ((18.0 + sqrt(457.0)) / 2.0) / ((16.0 + sqrt(409.0)) / 2.0)
        assertTrue(
            "aspect ${result.geometry.pageAspect} vs $expectedAspect",
            kotlin.math.abs(result.geometry.pageAspect - expectedAspect) < 1e-9,
        )
    }

    @Test
    fun collinearQuadReturnsNull() {
        val result = corrector.correct(planar24(), quad(0f, 0f, 4f, 0f, 8f, 0f, 12f, 0f))
        assertNull(result)
    }

    @Test
    fun reflexQuadReturnsNull() {
        // BL corner pulled inside the triangle of the other three.
        val result = corrector.correct(
            planar24(),
            quad(0f, 0f, 20f, 0f, 20f, 20f, 12.6f, 3.8f),
        )
        assertNull(result)
    }

    @Test
    fun outOfBoundsQuadClampsToEdgePixels() {
        // Quad (-8,-8)-(16,16) on the 24x24 image: edges 24 -> 24x24
        // output; out-of-bounds samples clamp to the image edge
        // (documented policy).
        val result = corrector.correct(planar24(), quad(-8f, -8f, 16f, -8f, 16f, 16f, -8f, 16f))
        assertNotNull(result)
        assertEquals(24, result!!.buffer.width)
        assertEquals(24, result.buffer.height)
        val argb = result.buffer.argb
        // (0,0) samples clamped (-8,-8) -> (0,0) = 3.
        assertEquals(gray(3), argb[0])
        // (0,12): x clamps to 0, y = -8 + 24*12/23 = 4.522 -> 27 + 6*0.522 = 30.13 -> 30.
        assertEquals(gray(30), argb[12 * 24])
        // (12,12) -> (4.522, 4.522): 3 + 10*4.522 = 48.22 -> 48.
        assertEquals(gray(48), argb[12 * 24 + 12])
        // (23,23) -> (16,16) in bounds: 3 + 64 + 96 = 163.
        assertEquals(gray(163), argb[23 * 24 + 23])
    }

    @Test
    fun tinyQuadIsClampedToMinimumDimension() {
        // 3x3 quad -> MIN_OUTPUT_DIM (16): corners still map exactly, so
        // the warp upscales bilinearly instead of degenerating.
        val result = corrector.correct(gradient4x4(), quad(0f, 0f, 3f, 0f, 3f, 3f, 0f, 3f))
        assertNotNull(result)
        assertEquals(16, result!!.buffer.width)
        assertEquals(16, result.buffer.height)
        // (0,0) -> (0,0) exactly.
        assertEquals(gray(10), result.buffer.argb[0])
        // (15,15) -> (3,3) exactly.
        assertEquals(gray(160), result.buffer.argb[15 * 16 + 15])
        // (8,8) -> (1.6, 1.6): 0.16*60 + 0.24*70 + 0.24*100 + 0.36*110 = 90.
        assertEquals(gray(90), result.buffer.argb[8 * 16 + 8])
    }

    @Test
    fun sameInputTwiceIsByteIdentical() {
        val image = gradient4x4()
        val q = quad(0.5f, 0.5f, 2.5f, 0.5f, 2.5f, 2.5f, 0.5f, 2.5f)
        val first = corrector.correct(image, q)!!
        val second = corrector.correct(image, q)!!
        assertTrue(first.buffer.argb.contentEquals(second.buffer.argb))
        assertEquals(first.id, second.id)
        assertEquals(first.geometry, second.geometry)
        assertEquals(first, second)
    }

    @Test
    fun inputImageIsNotModified() {
        val image = gradient4x4()
        val snapshot = image.argb.copyOf()
        corrector.correct(image, quad(0.5f, 0.5f, 2.5f, 0.5f, 2.5f, 2.5f, 0.5f, 2.5f))
        assertTrue(image.argb.contentEquals(snapshot))
    }

    @Test
    fun malformedImageReturnsNull() {
        val malformed = ImageBuffer(3, 2, IntArray(5))
        val result = corrector.correct(malformed, quad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        assertNull(result)
    }
}
