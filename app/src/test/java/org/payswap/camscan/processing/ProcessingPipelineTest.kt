package org.payswap.camscan.processing


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.PageEnhancementMode

/*
 * CAMSCAN-PROD-003 §6.7 — ProcessingPipeline (§6.6) coverage: the null-quad
 * full-frame path (documented interpretation: the mode applies on BOTH
 * paths), the quad path, and degenerate-input null propagation.
 */
class ProcessingPipelineTest {

    private val pipeline = ProcessingPipeline()

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun gradient3x2(): ImageBuffer =
        ImageBuffer(3, 2, intArrayOf(gray(10), gray(20), gray(30), gray(40), gray(50), gray(60)))

    @Test
    fun nullQuadGivesFullFrameCopyAndAppliesTheMode() {
        // Full-frame copy: geometry is the full-frame quad at source dims;
        // GRAYSCALE is the identity on gray pixels but stamps the mode.
        val source = gradient3x2()
        val result = pipeline.processCapture(source, null, PageEnhancementMode.GRAYSCALE)
        assertNotNull(result)
        assertEquals(3, result!!.buffer.width)
        assertEquals(2, result.buffer.height)
        assertEquals(QuadF.fullFrame(3, 2), result.geometry.sourceQuad)
        assertEquals(3, result.geometry.outputWidth)
        assertEquals(2, result.geometry.outputHeight)
        assertEquals(1.5, result.geometry.pageAspect, 1e-12)
        assertEquals(PageEnhancementMode.GRAYSCALE, result.enhancement)
        assertTrue(result.buffer.argb.contentEquals(source.argb.copyOf()))
        // Non-destructive: the source survives intact.
        assertTrue(source.argb.contentEquals(intArrayOf(gray(10), gray(20), gray(30), gray(40), gray(50), gray(60))))
    }

    @Test
    fun quadPathCorrectsThenEnhances() {
        // 2x2 quad on the 3x2 gradient: edges 1 -> clamped to 16x16; the
        // corner pixels still map exactly.
        val source = gradient3x2()
        val quad = QuadF(
            CornerF(0f, 0f),
            CornerF(1f, 0f),
            CornerF(1f, 1f),
            CornerF(0f, 1f),
        )
        val result = pipeline.processCapture(source, quad, PageEnhancementMode.CONTRAST)
        assertNotNull(result)
        assertEquals(16, result!!.buffer.width)
        assertEquals(16, result.buffer.height)
        assertEquals(PageEnhancementMode.CONTRAST, result.enhancement)
        // luma 10 (the TL pixel) stretches to the minimum of the window.
        assertEquals(0, (result.buffer.argb[0] shr 16) and 0xFF)
    }

    @Test
    fun degenerateQuadPropagatesNull() {
        val source = gradient3x2()
        val collinear = QuadF(
            CornerF(0f, 0f),
            CornerF(1f, 0f),
            CornerF(2f, 0f),
            CornerF(2.5f, 0f),
        )
        assertNull(pipeline.processCapture(source, collinear, PageEnhancementMode.ORIGINAL))
    }

    @Test
    fun malformedSourceWithNullQuadReturnsNull() {
        val malformed = ImageBuffer(3, 2, IntArray(5))
        assertNull(pipeline.processCapture(malformed, null, PageEnhancementMode.ORIGINAL))
    }

    @Test
    fun sameInputTwiceIsByteIdentical() {
        val source = gradient3x2()
        val quad = QuadF(
            CornerF(0f, 0f),
            CornerF(1f, 0f),
            CornerF(1f, 1f),
            CornerF(0f, 1f),
        )
        val first = pipeline.processCapture(source, quad, PageEnhancementMode.SHARPEN)!!
        val second = pipeline.processCapture(source, quad, PageEnhancementMode.SHARPEN)!!
        assertEquals(first, second)
        assertTrue(first.buffer.argb.contentEquals(second.buffer.argb))
    }
}
