package org.payswap.camscan.processing


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * CAMSCAN-PROD-003 §6.7 — ImageBuffer / QuadF helper coverage: structural
 * equality, malformed-input flags, the frame-space -> image-space scale
 * helper (the PROD-002-noted mapping this work order owns), canonical
 * ordering (float mirror of QuadGeometry.orderCorners), convexity,
 * full-frame construction, normalized-corner conversion, and
 * content-addressed ids.
 */
class ImageDomainTest {

    // -------------------------------------------------------- ImageBuffer

    @Test
    fun imageBufferWellFormedForPackedStorage() {
        val buffer = ImageBuffer(3, 2, IntArray(6))
        assertTrue(buffer.isWellFormed)
        assertEquals(3, buffer.width)
        assertEquals(2, buffer.height)
    }

    @Test
    fun imageBufferMalformedForMismatchedStorage() {
        assertFalse(ImageBuffer(3, 2, IntArray(5)).isWellFormed)
        assertFalse(ImageBuffer(0, 2, IntArray(0)).isWellFormed)
        assertFalse(ImageBuffer(3, -1, IntArray(0)).isWellFormed)
    }

    @Test
    fun imageBufferEqualityIsStructural() {
        val pixels = intArrayOf(1, 2, 3, 4)
        val a = ImageBuffer(2, 2, pixels)
        val b = ImageBuffer(2, 2, pixels.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun imageBufferEqualityDistinguishesContentAndDims() {
        val a = ImageBuffer(2, 2, intArrayOf(1, 2, 3, 4))
        assertNotEquals(a, ImageBuffer(2, 2, intArrayOf(1, 2, 3, 5)))
        assertNotEquals(a, ImageBuffer(2, 1, intArrayOf(1, 2)))
    }

    // ------------------------------------------------------------- QuadF

    @Test
    fun quadfFromFrameQuadScalesUniformly() {
        // Analysis frame 640x480 -> capture 3200x2400 (x5 both axes).
        val quad = QuadF.fromFrameQuad(
            0, 0, 640, 0, 640, 480, 0, 480,
            frameWidth = 640, frameHeight = 480,
            imageWidth = 3200, imageHeight = 2400,
        )
        requireNotNull(quad)
        assertEquals(CornerF(0f, 0f), quad.topLeft)
        assertEquals(CornerF(3200f, 0f), quad.topRight)
        assertEquals(CornerF(3200f, 2400f), quad.bottomRight)
        assertEquals(CornerF(0f, 2400f), quad.bottomLeft)
    }

    @Test
    fun quadfFromFrameQuadScalesNonUniformlyPerAxis() {
        // 4:3 analysis frame -> 16:9 capture: independent per-axis scale.
        val quad = QuadF.fromFrameQuad(
            320, 240, 480, 240, 480, 360, 320, 360,
            frameWidth = 640, frameHeight = 480,
            imageWidth = 1920, imageHeight = 1080,
        )
        requireNotNull(quad)
        // x: *3, y: *2.25
        assertEquals(CornerF(960f, 540f), quad.topLeft)
        assertEquals(CornerF(1440f, 540f), quad.topRight)
        assertEquals(CornerF(1440f, 810f), quad.bottomRight)
        assertEquals(CornerF(960f, 810f), quad.bottomLeft)
    }

    @Test
    fun quadfFromFrameQuadRejectsInvalidDimensions() {
        assertNull(
            QuadF.fromFrameQuad(
                0, 0, 1, 0, 1, 1, 0, 1,
                frameWidth = 0, frameHeight = 480,
                imageWidth = 100, imageHeight = 100,
            ),
        )
        assertNull(
            QuadF.fromFrameQuad(
                0, 0, 1, 0, 1, 1, 0, 1,
                frameWidth = 640, frameHeight = 480,
                imageWidth = 100, imageHeight = 0,
            ),
        )
    }

    @Test
    fun quadfCanonicalOrdersShuffledSquare() {
        // Deliberately shuffled assignment of a square's corners.
        val shuffled = QuadF(
            topLeft = CornerF(1f, 1f),      // actually BR
            topRight = CornerF(0f, 0f),     // actually TL
            bottomRight = CornerF(0f, 1f),  // actually BL
            bottomLeft = CornerF(1f, 0f),   // actually TR
        )
        val canonical = requireNotNull(QuadF.canonical(shuffled))
        assertEquals(CornerF(0f, 0f), canonical.topLeft)
        assertEquals(CornerF(1f, 0f), canonical.topRight)
        assertEquals(CornerF(1f, 1f), canonical.bottomRight)
        assertEquals(CornerF(0f, 1f), canonical.bottomLeft)
    }

    @Test
    fun quadfCanonicalOrdersRotatedDiamond() {
        // Diamond: T(1,0) R(2,1) B(1,2) L(0,1) given in a rotated order.
        val rotated = QuadF(
            topLeft = CornerF(2f, 1f),   // actually R
            topRight = CornerF(1f, 2f),  // actually B
            bottomRight = CornerF(0f, 1f), // actually L
            bottomLeft = CornerF(1f, 0f),  // actually T
        )
        val canonical = requireNotNull(QuadF.canonical(rotated))
        assertEquals(CornerF(1f, 0f), canonical.topLeft)
        assertEquals(CornerF(2f, 1f), canonical.topRight)
        assertEquals(CornerF(1f, 2f), canonical.bottomRight)
        assertEquals(CornerF(0f, 1f), canonical.bottomLeft)
    }

    @Test
    fun quadfCanonicalUntanglesBowtieOrderIntoPerimeterOrder() {
        // A self-crossing ORDER of square corners canonicalizes to the
        // square's perimeter order (same documented behavior as the
        // PROD-002 discipline — canonicalization never fails on distinct
        // convex-position corners; the convexity gate is separate).
        val bowtie = QuadF(
            topLeft = CornerF(0f, 0f),
            topRight = CornerF(2f, 0f),
            bottomRight = CornerF(0f, 2f),  // crossing assignment
            bottomLeft = CornerF(2f, 2f),
        )
        val canonical = requireNotNull(QuadF.canonical(bowtie))
        assertEquals(CornerF(0f, 0f), canonical.topLeft)
        assertEquals(CornerF(2f, 0f), canonical.topRight)
        assertEquals(CornerF(2f, 2f), canonical.bottomRight)
        assertEquals(CornerF(0f, 2f), canonical.bottomLeft)
    }

    @Test
    fun quadfCanonicalRejectsCoincidentCorners() {
        val bad = QuadF(
            CornerF(0f, 0f), CornerF(1f, 0f), CornerF(1f, 1f), CornerF(0f, 0.0000001f),
        )
        // The BL corner coincides with TL within DISTINCT_EPSILON.
        assertNull(QuadF.canonical(bad))
    }

    @Test
    fun quadfCanonicalRejectsNonFiniteCorners() {
        val bad = QuadF(
            CornerF(0f, 0f), CornerF(Float.NaN, 0f), CornerF(1f, 1f), CornerF(0f, 1f),
        )
        assertNull(QuadF.canonical(bad))
    }

    @Test
    fun quadfIsConvexAcceptsSquareAndRejectsReflexQuad() {
        assertTrue(QuadF.isConvex(QuadF.fullFrame(4, 4)))

        // Reflex vertex (2.6, 0.8) strictly inside the triangle of the
        // other three corners: canonicalization cannot untangle it and
        // the convexity gate rejects.
        val reflex = QuadF(
            topLeft = CornerF(0f, 0f),
            topRight = CornerF(4f, 0f),
            bottomRight = CornerF(4f, 4f),
            bottomLeft = CornerF(2.6f, 0.8f),
        )
        assertFalse(QuadF.isConvex(requireNotNull(QuadF.canonical(reflex))))
    }

    @Test
    fun quadfIsConvexRejectsCollinearQuad() {
        val collinear = QuadF(
            CornerF(0f, 0f), CornerF(2f, 0f), CornerF(4f, 0f), CornerF(6f, 0f),
        )
        val canonical = requireNotNull(QuadF.canonical(collinear))
        assertFalse(QuadF.isConvex(canonical))
    }

    @Test
    fun quadfFullFrameCoversPixelExtremes() {
        val quad = QuadF.fullFrame(4, 3)
        assertEquals(CornerF(0f, 0f), quad.topLeft)
        assertEquals(CornerF(3f, 0f), quad.topRight)
        assertEquals(CornerF(3f, 2f), quad.bottomRight)
        assertEquals(CornerF(0f, 2f), quad.bottomLeft)
    }

    @Test
    fun quadfToNormalizedCornersMapsFullFrameToUnitSquare() {
        val normalized = requireNotNull(QuadF.fullFrame(5, 4).toNormalizedCorners(5, 4))
        assertEquals(org.payswap.camscan.core.model.Corner(0f, 0f), normalized[0])
        assertEquals(org.payswap.camscan.core.model.Corner(1f, 0f), normalized[1])
        assertEquals(org.payswap.camscan.core.model.Corner(1f, 1f), normalized[2])
        assertEquals(org.payswap.camscan.core.model.Corner(0f, 1f), normalized[3])
    }

    @Test
    fun quadfToNormalizedCornersRejectsDegenerateDims() {
        assertNull(QuadF.fullFrame(1, 1).toNormalizedCorners(1, 1))
    }

    // --------------------------------------------------------- ContentId

    @Test
    fun contentIdIsDeterministicAndContentSensitive() {
        val pixels = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val a = ContentId.of(ImageBuffer(2, 1, pixels))
        val b = ContentId.of(ImageBuffer(2, 1, pixels.copyOf()))
        assertEquals(a, b)

        val changed = ContentId.of(ImageBuffer(2, 1, intArrayOf(0xFF112233.toInt(), 0xFF445567.toInt())))
        assertNotEquals(a, changed)

        // Dimensions participate in the id.
        val transposed = ContentId.of(ImageBuffer(1, 2, pixels.copyOf()))
        assertNotEquals(a, transposed)
    }
}
