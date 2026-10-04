package org.payswap.camscan.export.longimage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.document.InMemoryDocumentRepository
import org.payswap.camscan.ocr.FakeTimeSource
import org.payswap.camscan.imports.BoundsDecoder
import org.payswap.camscan.imports.ImageBounds
import org.payswap.camscan.tools.exportops.LongImagePlan

// CAMSCAN-VERIFY-002 — JVM tests of the long-image FLOW engine. The
// rasterizer is faked (android.graphics is not under test here); the
// assertions pin the PLANNER-driven contract: the strip geometry the
// rasterizer receives, the PROD-007-matching key vocabulary, honest
// nulls, and determinism under a fixed clock.

class LongImageExportEngineTest {

    /** Recording fake: captures the plan, returns canned PNG bytes. */
    private class RecordingRasterizer : StripRasterizer {
        var seenPlan: LongImagePlan? = null
        var seenPages: List<LongImagePageBytes> = emptyList()

        override fun render(plan: LongImagePlan, pages: List<LongImagePageBytes>): ByteArray? {
            seenPlan = plan
            seenPages = pages
            return PNG_BYTES
        }
    }

    /** Deterministic fake bounds decoder: fixed geometry per payload. */
    private class FakeBoundsDecoder : BoundsDecoder {
        val seen = mutableListOf<ByteArray>()

        override fun decodeBounds(bytes: ByteArray): ImageBounds? {
            seen.add(bytes)
            return if (bytes.contentEquals(PAGE_A_BYTES)) {
                ImageBounds(widthPx = 100, heightPx = 200)
            } else if (bytes.contentEquals(PAGE_B_BYTES)) {
                ImageBounds(widthPx = 300, heightPx = 150)
            } else {
                null
            }
        }
    }

    private class RecordingStore : ContentStore {
        val stored = HashMap<String, ByteArray>()
        val keys = ArrayList<String>()

        override suspend fun put(key: String, bytes: ByteArray): String {
            stored[key] = bytes
            keys.add(key)
            return key
        }

        override suspend fun open(ref: String): ByteArray? = stored[ref]

        override suspend fun delete(ref: String): Boolean = stored.remove(ref) != null

        override suspend fun exists(ref: String): Boolean = stored.containsKey(ref)
    }

    private inner class Fixture(
        pageCount: Int = 2,
        pageRefs: List<String?>? = null,
        rasterizer: RecordingRasterizer = RecordingRasterizer(),
        boundsDecoder: BoundsDecoder = FakeBoundsDecoder(),
    ) {
        val store = RecordingStore()
        val rasterizer = rasterizer
        val repository = InMemoryDocumentRepository(FakeTimeSource(FIXED_MILLIS))
        val engine = LongImageExportEngine(
            contentStore = store,
            boundsDecoder = boundsDecoder,
            rasterizer = rasterizer,
            timeSource = FakeTimeSource(FIXED_MILLIS),
            dispatcher = Dispatchers.Unconfined,
        )

        init {
            val refs = pageRefs ?: List(pageCount) { index -> "ref-" + index }
            store.stored["ref-0"] = PAGE_A_BYTES
            if (refs.size > 1) {
                store.stored["ref-1"] = PAGE_B_BYTES
            }
            val pages = refs.mapIndexed { index, ref ->
                Page(
                    id = "page-" + index,
                    documentId = DOC_ID,
                    index = index,
                    processedImageRef = ref,
                    rotationDegrees = if (index == 1) 90 else 0,
                    createdAtMillis = FIXED_MILLIS,
                    updatedAtMillis = FIXED_MILLIS,
                )
            }
            runBlocking {
                repository.upsertDocument(
                    Document(
                        id = DOC_ID,
                        title = "Receipts/Q3",
                        pageIds = pages.map { it.id },
                        createdAtMillis = FIXED_MILLIS,
                        updatedAtMillis = FIXED_MILLIS,
                    ),
                    pages,
                )
            }
        }
    }

    @Test
    fun export_plansTheStripFromDecodedBoundsAndStoresThePng() = runTest {
        val fixture = Fixture()
        val artifact = fixture.engine.export(fixture.repository, DOC_ID)

        assertNotNull(artifact)
        assertEquals("exports/" + DOC_ID + "-" + FIXED_MILLIS + "-2p-long.png", artifact!!.ref)
        assertEquals("image/png", artifact.mime)
        // The display name follows the export engine's sanitizer.
        assertEquals("Receipts_Q3_long.png", artifact.displayName)
        assertEquals(2, artifact.pageCount)
        assertTrue(fixture.store.stored[artifact.ref]!!.contentEquals(PNG_BYTES))

        val plan = fixture.rasterizer.seenPlan!!
        // Width normalization: target = MAX planned width. Page A plans
        // 100x200 (rotation 0); page B's decoded bounds 300x150 with a 90
        // rotation SWAP to 150x300 — so the target is 150.
        assertEquals(150, plan.targetWidthPx)
        assertEquals(2, plan.pageCount)
        // Page A scales 100x200 -> 150x300; page B 150x300 stays 150x300.
        val placementA = plan.placements.first { it.pageId == "page-0" }
        assertEquals(0, placementA.yOffsetPx)
        assertEquals(150, placementA.scaledWidthPx)
        assertEquals(300, placementA.scaledHeightPx)
        val placementB = plan.placements.first { it.pageId == "page-1" }
        assertEquals(300, placementB.yOffsetPx)
        assertEquals(150, placementB.scaledWidthPx)
        assertEquals(300, placementB.scaledHeightPx)
        assertEquals(600, plan.totalHeightPx)
    }

    @Test
    fun export_passesPageBytesAndFoldedRotationToTheRasterizer() = runTest {
        val fixture = Fixture()
        fixture.engine.export(fixture.repository, DOC_ID)

        val pages = fixture.rasterizer.seenPages
        assertEquals(2, pages.size)
        assertTrue(pages[0].bytes.contentEquals(PAGE_A_BYTES))
        assertEquals(0, pages[0].rotationDegrees)
        assertTrue(pages[1].bytes.contentEquals(PAGE_B_BYTES))
        assertEquals(90, pages[1].rotationDegrees)
    }

    @Test
    fun export_unknownDocument_returnsNull() = runTest {
        val fixture = Fixture()
        assertNull(fixture.engine.export(fixture.repository, "no-such-doc"))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun export_emptyDocument_returnsNull() = runTest {
        val fixture = Fixture(pageCount = 0)
        assertNull(fixture.engine.export(fixture.repository, DOC_ID))
    }

    @Test
    fun export_pageWithoutImageRef_returnsNull() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-a", null))
        assertNull(fixture.engine.export(fixture.repository, DOC_ID))
    }

    @Test
    fun export_undecodablePage_returnsNull() = runTest {
        val fixture = Fixture(pageRefs = listOf("ref-0", "ref-1"))
        fixture.store.stored["ref-1"] = "garbage-bytes".toByteArray()
        assertNull(fixture.engine.export(fixture.repository, DOC_ID))
    }

    @Test
    fun export_rasterizerFailure_returnsNull() = runTest {
        val failing = object : StripRasterizer {
            override fun render(plan: LongImagePlan, pages: List<LongImagePageBytes>): ByteArray? = null
        }
        val fixture = Fixture()
        val engine = LongImageExportEngine(
            contentStore = fixture.store,
            boundsDecoder = FakeBoundsDecoder(),
            rasterizer = failing,
            timeSource = FakeTimeSource(FIXED_MILLIS),
            dispatcher = Dispatchers.Unconfined,
        )
        assertNull(engine.export(fixture.repository, DOC_ID))
        assertTrue(fixture.store.keys.isEmpty())
    }

    @Test
    fun export_isDeterministic_forFixedInputsAndTime() = runTest {
        val first = Fixture()
        val second = Fixture()
        val firstArtifact = first.engine.export(first.repository, DOC_ID)!!
        val secondArtifact = second.engine.export(second.repository, DOC_ID)!!

        assertEquals(firstArtifact.ref, secondArtifact.ref)
        assertTrue(
            first.store.stored[firstArtifact.ref]!!.contentEquals(
                second.store.stored[secondArtifact.ref],
            ),
        )
    }

    private companion object {
        const val DOC_ID = "doc-1"
        const val FIXED_MILLIS = 1_000_000L
        val PAGE_A_BYTES = "page-a-bytes".toByteArray()
        val PAGE_B_BYTES = "page-b-bytes".toByteArray()
        val PNG_BYTES = "fake-png-bytes".toByteArray()
    }
}
