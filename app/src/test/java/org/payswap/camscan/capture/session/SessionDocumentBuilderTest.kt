package org.payswap.camscan.capture.session

import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.processing.ProcessingPipeline

/**
 * CAMSCAN-PROD-004 §6.7 — the pure half of §6.4: title formatting under a
 * fixed TimeSource + injected zone, and the (Document, Pages) assembly from
 * a finished session payload (android-free: refs arrive as data).
 */
class SessionDocumentBuilderTest {

    private lateinit var pipeline: ProcessingPipeline

    @Before
    fun setUp() {
        pipeline = ProcessingPipeline()
    }

    // 1750000000000 ms == 2025-06-15T15:06:40Z.
    private val fixedNowMillis = 1_750_000_000_000L

    private fun sessionResult(): SessionResult {
        val fullFrame = SessionPage.fromCapture("p1", SyntheticPages.gradient(24, 24), null, null, pipeline)!!
        val quad = SyntheticPages.rectQuad(4, 4, 20, 20)
        val warped = SessionPage.fromCapture("p2", SyntheticPages.gradient(24, 24), null, quad, pipeline)!!
        return SessionResult(listOf(fullFrame.toResult(), warped.toResult()))
    }

    @Test
    fun titleFormatsUnderTheInjectedUtcZone() {
        val builder = SessionDocumentBuilder(ZoneOffset.UTC)
        assertEquals("Scan 2025-06-15 15:06", builder.formatTitle(fixedNowMillis))
    }

    @Test
    fun titleFormatsUnderInjectedNonDefaultZones() {
        val plusTwo = SessionDocumentBuilder(ZoneId.of("UTC+02:00"))
        assertEquals("Scan 2025-06-15 17:06", plusTwo.formatTitle(fixedNowMillis))

        val minusFive = SessionDocumentBuilder(ZoneId.of("UTC-05:00"))
        assertEquals("Scan 2025-06-15 10:06", minusFive.formatTitle(fixedNowMillis))
    }

    @Test
    fun buildAssemblesOrderedDocumentAndPages() {
        val builder = SessionDocumentBuilder(ZoneOffset.UTC)
        val result = sessionResult()

        val built = builder.build(
            result = result,
            documentId = "doc-1",
            processedRefs = listOf("ref-processed-1", "ref-processed-2"),
            sourceRefs = listOf(null, "ref-source-2"),
            nowMillis = fixedNowMillis,
            title = "Custom title",
        )
        assertNotNull(built)

        val (document, pages) = built!!
        assertEquals("doc-1", document.id)
        assertEquals("Custom title", document.title)
        assertEquals(listOf("p1", "p2"), document.pageIds)
        assertEquals(DocumentSource.SCAN, document.sourceType)
        assertEquals(fixedNowMillis, document.createdAtMillis)
        assertEquals(fixedNowMillis, document.updatedAtMillis)

        assertEquals(2, pages.size)
        val page1 = pages[0]
        assertEquals("p1", page1.id)
        assertEquals("doc-1", page1.documentId)
        assertEquals(0, page1.index)
        assertEquals("ref-processed-1", page1.processedImageRef)
        assertNull(page1.sourceCaptureRef)
        assertNull(page1.cropQuad) // full-frame capture: no perspective metadata
        assertEquals(0, page1.rotationDegrees)
        assertEquals(org.payswap.camscan.core.model.PageEnhancementMode.ORIGINAL, page1.enhancement)
        assertNull(page1.ocrResultId)
        assertEquals(fixedNowMillis, page1.createdAtMillis)
        assertEquals(fixedNowMillis, page1.updatedAtMillis)

        val page2 = pages[1]
        assertEquals("p2", page2.id)
        assertEquals(1, page2.index)
        assertEquals("ref-processed-2", page2.processedImageRef)
        assertEquals("ref-source-2", page2.sourceCaptureRef)
        assertNotNull(page2.cropQuad)
        // The normalized quad is the warp quad over the source dims.
        assertEquals(
            SyntheticPages.rectQuad(4, 4, 20, 20).toNormalizedCorners(24, 24),
            page2.cropQuad,
        )
    }

    @Test
    fun buildUsesTheDefaultTitleWhenNoneIsGiven() {
        val builder = SessionDocumentBuilder(ZoneOffset.UTC)
        val built = builder.build(
            result = sessionResult(),
            documentId = "doc-1",
            processedRefs = listOf("a", "b"),
            sourceRefs = listOf(null, null),
            nowMillis = fixedNowMillis,
        )!!
        assertEquals(builder.formatTitle(fixedNowMillis), built.first.title)
        assertTrue(built.first.title.startsWith(SessionDocumentBuilder.TITLE_PREFIX))
    }

    @Test
    fun buildRejectsRefSizeMismatchesWithoutThrowing() {
        val builder = SessionDocumentBuilder(ZoneOffset.UTC)
        val result = sessionResult()

        assertNull(
            builder.build(result, "doc-1", listOf("only-one"), listOf(null, null), fixedNowMillis),
        )
        assertNull(
            builder.build(result, "doc-1", listOf("a", "b"), listOf("only-one"), fixedNowMillis),
        )
        assertNull(
            builder.build(result, " ", listOf("a", "b"), listOf(null, null), fixedNowMillis),
        )
    }

    @Test
    fun storeKeysFollowTheContractConventions() {
        val builder = SessionDocumentBuilder(ZoneOffset.UTC)
        assertEquals("pages/p1.png", builder.processedKey("p1"))
        assertEquals("sources/p1.jpg", builder.sourceJpegKey("p1"))
        assertEquals("sources/p1.png", builder.sourcePngKey("p1"))
    }
}
