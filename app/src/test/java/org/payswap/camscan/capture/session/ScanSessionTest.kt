package org.payswap.camscan.capture.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.processing.ImageBuffer
import org.payswap.camscan.processing.ProcessedGeometry
import org.payswap.camscan.processing.ProcessedImage
import org.payswap.camscan.processing.ProcessingPipeline
import org.payswap.camscan.processing.QuadF

/**
 * CAMSCAN-PROD-004 §6.7 — the ScanSession state machine suite (pure JVM).
 *
 * Covers the contract seams (addPage / retake / remove / reorder / finish)
 * plus the PROD-004 extensions (setEnhancement / setRotation / adjustCrop):
 * ordering, indices, atomic replace, retake lineage, re-processing
 * determinism, finish payload correctness, zero-page abort, and
 * illegal-transition rejection — the flag-not-exception discipline.
 */
class ScanSessionTest {

    private lateinit var pipeline: ProcessingPipeline

    @Before
    fun setUp() {
        pipeline = ProcessingPipeline()
    }

    // ------------------------------------------------------------------ helpers

    private fun source(width: Int = 24, height: Int = 24): ImageBuffer =
        SyntheticPages.gradient(width, height)

    private fun newPage(id: String, quad: QuadF? = null): SessionPage? =
        SessionPage.fromCapture(id, source(), null, quad, pipeline)

    // ------------------------------------------------------------------ addPage

    @Test
    fun addPageAppendsInOrder() {
        val session = ScanSession(pipeline)
        assertTrue(session.addPage(newPage("p1")!!))
        assertTrue(session.addPage(newPage("p2")!!))

        assertEquals(2, session.pageCount)
        assertEquals("p1", session.pages()[0].id)
        assertEquals("p2", session.pages()[1].id)
        assertEquals("p2", session.pageAt(1)?.id)
    }

    @Test
    fun addPageRejectsAfterFinish() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)
        assertNotNull(session.finish())

        assertFalse(session.addPage(newPage("p2")!!))
        assertEquals(1, session.pageCount)
    }

    @Test
    fun addPageRejectsInvalidPage() {
        val session = ScanSession(pipeline)
        val bad = ImageBuffer(4, 4, IntArray(3)) // malformed: 3 pixels for 4x4
        val geometry = ProcessedGeometry(QuadF.fullFrame(4, 4), 4, 4, 1.0)
        val processed = ProcessedImage(bad, geometry, PageEnhancementMode.ORIGINAL, "bad-id")
        val page = SessionPage("bad", bad, null, null, processed, PageEnhancementMode.ORIGINAL, 0)

        assertFalse(page.isValid)
        assertFalse(session.addPage(page))
        assertEquals(0, session.pageCount)
    }

    // ------------------------------------------------------------------ retake

    @Test
    fun retakeReplacesAtomicallyWithLineage() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)
        session.addPage(newPage("p2")!!)
        val replacement = newPage("p3")!!

        assertTrue(session.retake(0, replacement))

        assertEquals(2, session.pageCount)
        assertEquals("p3", session.pages()[0].id)
        assertEquals("p1", session.pages()[0].supersedesId)
        assertEquals("p2", session.pages()[1].id)
        assertNull(session.pages()[1].supersedesId)
    }

    @Test
    fun retakeRejectsInvalidIndicesAndAfterFinish() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)

        assertFalse(session.retake(-1, newPage("x")!!))
        assertFalse(session.retake(5, newPage("x")!!))

        session.finish()
        assertFalse(session.retake(0, newPage("y")!!))
        assertEquals("p1", session.pages()[0].id)
    }

    // ------------------------------------------------------------------ remove / reorder

    @Test
    fun removeShiftsLaterPages() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)
        session.addPage(newPage("p2")!!)
        session.addPage(newPage("p3")!!)

        assertTrue(session.remove(1))

        assertEquals(listOf("p1", "p3"), session.pages().map { it.id })
        assertFalse(session.remove(9))
    }

    @Test
    fun reorderUsesListMoveSemantics() {
        val sessionA = ScanSession(pipeline)
        listOf("a", "b", "c").forEach { sessionA.addPage(newPage(it)!!) }

        // remove at 0, insert at post-removal index 2 -> end.
        assertTrue(sessionA.reorder(0, 2))
        assertEquals(listOf("b", "c", "a"), sessionA.pages().map { it.id })

        val sessionB = ScanSession(pipeline)
        listOf("a", "b", "c").forEach { sessionB.addPage(newPage(it)!!) }

        // remove at 2, insert at 0 -> front.
        assertTrue(sessionB.reorder(2, 0))
        assertEquals(listOf("c", "a", "b"), sessionB.pages().map { it.id })

        val sessionC = ScanSession(pipeline)
        listOf("a", "b", "c").forEach { sessionC.addPage(newPage(it)!!) }

        // out-of-range from is rejected; to is clamped to the post-removal
        // end (99 -> 2): [a, b, c] -> remove b -> [a, c] -> append b.
        assertFalse(sessionC.reorder(-1, 0))
        assertTrue(sessionC.reorder(1, 99))
        assertEquals(listOf("a", "c", "b"), sessionC.pages().map { it.id })
    }

    // ------------------------------------------------------------------ setEnhancement

    @Test
    fun setEnhancementReprocessesFromSourceDeterministically() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)
        val before = session.pageAt(0)!!
        val sourceCopy = before.source.argb.copyOf()

        assertTrue(session.setEnhancement(0, PageEnhancementMode.GRAYSCALE))

        val grayPage = session.pageAt(0)!!
        assertEquals(PageEnhancementMode.GRAYSCALE, grayPage.enhancement)
        assertEquals(PageEnhancementMode.GRAYSCALE, grayPage.processed.enhancement)
        assertEquals("p1", grayPage.id) // id survives re-processing
        // Non-destructive: the SOURCE buffer is untouched.
        assertTrue(before.source.argb.contentEquals(sourceCopy))
        // GRAYSCALE observable: every pixel is gray now.
        for (pixel in grayPage.processed.buffer.argb) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertEquals(r, g)
            assertEquals(g, b)
        }

        val grayBytes = grayPage.processed.buffer.argb.copyOf()
        assertTrue(session.setEnhancement(0, PageEnhancementMode.ORIGINAL))
        assertTrue(session.setEnhancement(0, PageEnhancementMode.GRAYSCALE))
        // Deterministic: the same (source, quad, mode) -> byte-identical output.
        assertTrue(session.pageAt(0)!!.processed.buffer.argb.contentEquals(grayBytes))

        // Idempotent no-op: re-applying the current mode is accepted.
        assertTrue(session.setEnhancement(0, PageEnhancementMode.GRAYSCALE))
    }

    @Test
    fun setEnhancementRejectsBadIndicesAndAfterFinish() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)
        assertFalse(session.setEnhancement(3, PageEnhancementMode.CONTRAST))

        session.finish()
        assertFalse(session.setEnhancement(0, PageEnhancementMode.CONTRAST))
    }

    // ------------------------------------------------------------------ setRotation

    @Test
    fun setRotationIsMetadataOnlyAndValidated() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)
        val bytesBefore = session.pageAt(0)!!.processed.buffer.argb.copyOf()

        assertTrue(session.setRotation(0, 90))
        val rotated = session.pageAt(0)!!
        assertEquals(90, rotated.rotationDegrees)
        // Rotation NEVER re-processes: the processed bytes are identical.
        assertTrue(rotated.processed.buffer.argb.contentEquals(bytesBefore))

        assertTrue(session.setRotation(0, 270))
        assertEquals(270, session.pageAt(0)!!.rotationDegrees)

        // Only right angles are legal; invalid values are rejected.
        assertFalse(session.setRotation(0, 45))
        assertFalse(session.setRotation(0, -90))
        assertEquals(270, session.pageAt(0)!!.rotationDegrees)

        session.finish()
        assertFalse(session.setRotation(0, 0))
    }

    // ------------------------------------------------------------------ adjustCrop

    @Test
    fun adjustCropReprocessesFromSourceWithTheNewQuad() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!) // full-frame 24x24
        val adjusted = SyntheticPages.rectQuad(8, 8, 16, 16)

        assertTrue(session.adjustCrop(0, adjusted))

        val page = session.pageAt(0)!!
        assertEquals(adjusted, page.sourceQuad)
        assertEquals(adjusted, page.processed.geometry.sourceQuad)
        // 8px edges are clamped UP to the corrector's documented
        // MIN_OUTPUT_DIM = 16 (PROD-003 delivery note).
        assertEquals(16, page.processed.geometry.outputWidth)
        assertEquals(16, page.processed.geometry.outputHeight)

        session.finish()
        assertFalse(session.adjustCrop(0, adjusted))
    }

    // ------------------------------------------------------------------ finish

    @Test
    fun finishFreezesTheFullPayload() {
        val session = ScanSession(pipeline)
        val fullFrame = newPage("p1")!!
        session.addPage(fullFrame)
        session.setRotation(0, 90)
        session.setEnhancement(0, PageEnhancementMode.GRAYSCALE)

        val quad = SyntheticPages.rectQuad(8, 8, 16, 16)
        session.addPage(newPage("p2", quad)!!)

        val result = session.finish()!!
        assertEquals(2, result.pageCount)

        // Page 1: full-frame capture -> NO crop quad (no perspective metadata).
        val page1 = result.pages[0]
        assertEquals("p1", page1.pageId)
        assertNull(page1.supersedesId)
        assertNull(page1.cropQuad)
        assertEquals(90, page1.rotationDegrees)
        assertEquals(PageEnhancementMode.GRAYSCALE, page1.enhancement)

        // Page 2: warped capture -> normalized crop quad over the SOURCE dims.
        val page2 = result.pages[1]
        assertEquals("p2", page2.pageId)
        assertEquals(quad.toNormalizedCorners(24, 24), page2.cropQuad)
        assertEquals(0, page2.rotationDegrees)
        assertEquals(PageEnhancementMode.ORIGINAL, page2.enhancement)
    }

    @Test
    fun finishWithZeroPagesAbortsAndCloses() {
        val session = ScanSession(pipeline)
        assertNull(session.finish())
        assertFalse(session.addPage(newPage("p1")!!))
        assertNull(session.finish())
    }

    @Test
    fun finishIsIdempotentAndStable() {
        val session = ScanSession(pipeline)
        session.addPage(newPage("p1")!!)
        session.addPage(newPage("p2")!!)

        val first = session.finish()!!
        val firstIds = first.pages.map { it.pageId }

        // Every mutation is rejected after finish…
        assertFalse(session.addPage(newPage("p3")!!))
        assertFalse(session.remove(0))
        assertFalse(session.reorder(0, 1))
        assertFalse(session.retake(0, newPage("p4")!!))
        assertFalse(session.setEnhancement(0, PageEnhancementMode.CONTRAST))
        assertFalse(session.setRotation(0, 180))

        // …and repeated finish returns the SAME snapshot (stable final ids).
        val second = session.finish()
        assertSame(first, second)
        assertEquals(firstIds, second?.pages?.map { it.pageId })
    }
}
