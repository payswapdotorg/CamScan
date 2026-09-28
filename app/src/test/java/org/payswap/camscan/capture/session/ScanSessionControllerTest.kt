package org.payswap.camscan.capture.session

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.capture.detect.Corner
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.processing.CornerF
import org.payswap.camscan.processing.ProcessingPipeline
import org.payswap.camscan.processing.QuadF

/**
 * CAMSCAN-PROD-004 §6.7 — the capture -> session coordinator suite (pure
 * JVM; decode + persist are injected fakes, the pipeline is the real
 * PROD-003 engine on small synthetic pages).
 *
 * Covers: append indices, detection-quad frame->image mapping, the retake
 * hand-off (armed retake, atomic replace, old page surviving when no new
 * capture lands), decode failure, delegating edits, finish-once
 * persistence caching, zero-page abort, and the unwired-persistence path.
 */
class ScanSessionControllerTest {

    private var nextId = 0
    private val idGenerator: () -> String = { "page-${++nextId}" }

    private fun controller(
        decode: (File) -> org.payswap.camscan.processing.ImageBuffer? =
            { SyntheticPages.gradient(24, 24) },
        persist: suspend (SessionResult) -> String? = { null },
    ): ScanSessionController = ScanSessionController(
        pipeline = ProcessingPipeline(),
        idGenerator = idGenerator,
        decodeCapture = decode,
        persistResult = persist,
    )

    private fun frameCorners(): List<Corner> = listOf(
        Corner(80, 60),
        Corner(240, 60),
        Corner(240, 180),
        Corner(80, 180),
    )

    @Test
    fun submitCaptureAppendsAndReturnsThePageIndex() {
        val controller = controller()

        val first = controller.submitCapture(File("/sim/1.jpg"), null, 0, 0)
        val second = controller.submitCapture(File("/sim/2.jpg"), null, 0, 0)

        assertEquals(0, first)
        assertEquals(1, second)
        assertEquals(2, controller.pageCount)
        assertEquals("page-1", controller.pageAt(0)?.id)
        assertEquals("page-2", controller.pageAt(1)?.id)
        assertEquals(0, controller.pageAt(0)?.rotationDegrees)
    }

    @Test
    fun submitCaptureMapsTheDetectionQuadOntoTheCapture() {
        val controller = controller(
            decode = { SyntheticPages.gradient(640, 480) },
        )

        val index = controller.submitCapture(File("/sim/1.jpg"), frameCorners(), 320, 240)

        assertEquals(0, index)
        // Per-axis proportional scale 640/320 x 480/240 = 2x, 2y.
        val expected = QuadF(
            CornerF(160f, 120f),
            CornerF(480f, 120f),
            CornerF(480f, 360f),
            CornerF(160f, 360f),
        )
        assertEquals(expected, controller.pageAt(0)?.sourceQuad)
        // The quad survives into the finish payload as normalized corners.
        val result = runBlocking { controller.finish() /* null: persistence unwired */ }
        assertNull(result) // unwired persistence — the payload is still frozen inside
        val payload = controller.pages().single().toResult()
        assertEquals(expected.toNormalizedCorners(640, 480), payload.cropQuad)
    }

    @Test
    fun invalidCornerCountFallsBackToFullFrame() {
        val controller = controller()
        val shortList = frameCorners().drop(1) // 3 corners: unusable

        val index = controller.submitCapture(File("/sim/1.jpg"), shortList, 320, 240)

        assertEquals(0, index)
        assertNull(controller.pageAt(0)?.sourceQuad)
    }

    @Test
    fun armedRetakeReplacesThePageAtTheSameIndex() {
        val controller = controller()
        controller.submitCapture(File("/sim/1.jpg"), null, 0, 0) // page-1 @ 0
        controller.submitCapture(File("/sim/2.jpg"), null, 0, 0) // page-2 @ 1
        val oldId = controller.pageAt(1)?.id

        assertTrue(controller.markRetake(1))
        assertEquals(1, controller.pendingRetake())

        val replacedIndex = controller.submitCapture(File("/sim/3.jpg"), null, 0, 0)

        assertEquals(1, replacedIndex)
        assertEquals(listOf("page-1", "page-3"), controller.pages().map { it.id })
        assertEquals(oldId, controller.pageAt(1)?.supersedesId)
        assertNull(controller.pendingRetake()) // armed retake consumed
    }

    @Test
    fun armedRetakeKeepsTheOldPageWhenNoCaptureLands() {
        val controller = controller()
        controller.submitCapture(File("/sim/1.jpg"), null, 0, 0)

        assertTrue(controller.markRetake(0))

        assertEquals("page-1", controller.pageAt(0)?.id)
        runBlocking {
            assertNull(controller.finish()) // unwired persistence
        }
        assertEquals(listOf("page-1"), controller.pages().map { it.id })
    }

    @Test
    fun markRetakeRejectsBadIndexAndAfterFinish() {
        val controller = controller()
        controller.submitCapture(File("/sim/1.jpg"), null, 0, 0)

        assertFalse(controller.markRetake(5))
        runBlocking { controller.finish() }
        assertFalse(controller.markRetake(0))
    }

    @Test
    fun decodeFailureIsAFlagNotAnException() {
        val controller = controller(decode = { null })

        assertNull(controller.submitCapture(File("/missing.jpg"), null, 0, 0))
        assertEquals(0, controller.pageCount)

        val malformed = org.payswap.camscan.processing.ImageBuffer(8, 8, IntArray(3))
        val broken = controller(decode = { malformed })
        assertNull(broken.submitCapture(File("/bad.jpg"), null, 0, 0))
        assertEquals(0, broken.pageCount)
    }

    @Test
    fun delegatingEditsReachTheSession() {
        val controller = controller()
        controller.submitCapture(File("/sim/1.jpg"), null, 0, 0)

        assertTrue(controller.setRotation(0, 180))
        assertEquals(180, controller.pageAt(0)?.rotationDegrees)

        assertTrue(controller.setEnhancement(0, PageEnhancementMode.BLACK_AND_WHITE))
        assertEquals(PageEnhancementMode.BLACK_AND_WHITE, controller.pageAt(0)?.enhancement)

        assertTrue(controller.adjustCrop(0, SyntheticPages.rectQuad(4, 4, 12, 12)))
        assertEquals(SyntheticPages.rectQuad(4, 4, 12, 12), controller.pageAt(0)?.sourceQuad)

        assertFalse(controller.setEnhancement(7, PageEnhancementMode.CONTRAST))
        assertFalse(controller.setRotation(0, 45))
    }

    @Test
    fun finishPersistsExactlyOnceAndCachesTheDocumentId() = runBlocking {
        val persisted = mutableListOf<SessionResult>()
        val controller = controller(persist = { result ->
            persisted.add(result)
            "doc-42"
        })
        controller.submitCapture(File("/sim/1.jpg"), null, 0, 0)
        controller.submitCapture(File("/sim/2.jpg"), null, 0, 0)

        assertEquals("doc-42", controller.finish())
        assertEquals("doc-42", controller.finish()) // cached, no re-persist
        assertEquals("doc-42", controller.finish())

        assertEquals(1, persisted.size) // exactly one repository-worthy payload
        assertEquals(2, persisted.single().pageCount)
        assertEquals(listOf("page-1", "page-2"), persisted.single().pages.map { it.pageId })
    }

    @Test
    fun finishWithZeroPagesAbortsWithoutPersisting() = runBlocking {
        var persisted = 0
        val controller = controller(persist = {
            persisted++
            "should-not-happen"
        })

        assertNull(controller.finish())
        assertEquals(0, persisted)
    }

    @Test
    fun finishWithUnwiredPersistenceReportsNullHonestly() = runBlocking {
        val controller = controller(persist = { null })
        controller.submitCapture(File("/sim/1.jpg"), null, 0, 0)

        // Pages exist; persistence is unwired/failed -> the shell observes
        // onScanFinished(null), the documented placeholder behavior.
        assertNull(controller.finish())
        assertEquals(1, controller.pageCount)
    }

    @Test
    fun submitCaptureIsRejectedAfterFinish() = runBlocking {
        val controller = controller()
        controller.submitCapture(File("/sim/1.jpg"), null, 0, 0)
        controller.finish()

        assertNull(controller.submitCapture(File("/sim/2.jpg"), null, 0, 0))
        assertEquals(1, controller.pageCount)
    }

    @Test
    fun registryRoundTripsTokensAndClears() {
        val token = "registry-token"
        val controller = controller()
        try {
            ScanSessionRegistry.put(token, controller)
            assertNotNull(ScanSessionRegistry.get(token))
            assertEquals(controller.pageCount, ScanSessionRegistry.get(token)?.pageCount)
            assertNull(ScanSessionRegistry.get("other"))
        } finally {
            ScanSessionRegistry.remove(token)
        }
        assertNull(ScanSessionRegistry.get(token))
    }
}
