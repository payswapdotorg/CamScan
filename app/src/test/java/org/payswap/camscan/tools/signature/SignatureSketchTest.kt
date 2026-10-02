package org.payswap.camscan.tools.signature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.FakeTimeSource
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect

// SignatureSketch / SignatureStroke / SignatureStore tests
// (CAMSCAN-PROD-011 section 6.6): bounding boxes, plan conversion, store
// ordering, ids, and TimeSource-driven timestamps.

class SignatureSketchTest {

    private fun stroke(vararg points: Point, width: Int = 1, color: Long = 0xFF000000L) =
        SignatureStroke(points.toList(), width, color)

    @Test
    fun emptySketch_hasNoBoundingBox() {
        val sketch = SignatureSketch(emptyList())
        assertNull(sketch.boundingBox)
        assertTrue(sketch.isEmpty)
    }

    @Test
    fun singleStroke_boundingBoxIsInclusive() {
        val sketch = SignatureSketch(listOf(stroke(Point(0, 0), Point(5, 3))))
        assertEquals(Rect(0, 0, 6, 4), sketch.boundingBox)
    }

    @Test
    fun boundingBox_handlesNegativeCoordinates() {
        val sketch = SignatureSketch(listOf(stroke(Point(-5, -3), Point(2, 1))))
        assertEquals(Rect(-5, -3, 8, 5), sketch.boundingBox)
    }

    @Test
    fun boundingBox_spansAllStrokes() {
        val sketch = SignatureSketch(
            listOf(
                stroke(Point(0, 0), Point(3, 3)),
                stroke(Point(10, 5), Point(12, 9)),
            ),
        )
        assertEquals(Rect(0, 0, 13, 10), sketch.boundingBox)
    }

    @Test
    fun boundingBox_ignoresStrokeWidth() {
        val sketch = SignatureSketch(listOf(stroke(Point(0, 0), Point(4, 0), width = 9)))
        assertEquals(Rect(0, 0, 5, 1), sketch.boundingBox)
    }

    @Test
    fun toDrawPlan_emitsStrokeOpsInOrder() {
        val strokes = listOf(
            stroke(Point(0, 0), Point(1, 1), width = 2, color = 0xFF112233L),
            stroke(Point(5, 5), width = 3, color = 0xFF445566L),
        )
        val plan = SignatureSketch(strokes).toDrawPlan()
        assertEquals(2, plan.ops.size)
        val first = plan.ops[0] as org.payswap.camscan.tools.render.DrawOp.Stroke
        val second = plan.ops[1] as org.payswap.camscan.tools.render.DrawOp.Stroke
        assertEquals(2, first.widthPx)
        assertEquals(0xFF112233L, first.colorArgb)
        assertEquals(listOf(Point(0, 0), Point(1, 1)), first.points)
        assertEquals(3, second.widthPx)
        assertEquals(listOf(Point(5, 5)), second.points)
    }

    @Test
    fun emptySketch_toDrawPlanIsEmpty() {
        assertTrue(SignatureSketch(emptyList()).toDrawPlan().isEmpty)
    }

    @Test
    fun sketchValueSemantics() {
        val a = SignatureSketch(listOf(stroke(Point(0, 0))))
        val b = SignatureSketch(listOf(stroke(Point(0, 0))))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun computeBoundingBox_staticHelperHandlesEmpty() {
        assertNull(SignatureSketch.computeBoundingBox(emptyList()))
    }
}

class SignatureStoreTest {

    private val time = FakeTimeSource(1_000L)

    private fun sketch(): SignatureSketch =
        SignatureSketch(listOf(SignatureStroke(listOf(Point(0, 0), Point(5, 5)), 2, 0xFF000000L)))

    @Test
    fun add_assignsDeterministicIdsInInsertionOrder() {
        val store = SignatureStore(time)
        val first = store.add("Work", sketch())
        val second = store.add("Personal", sketch())
        assertEquals("sig-1", first.id)
        assertEquals("sig-2", second.id)
    }

    @Test
    fun add_readsCreatedMillisFromInjectedTimeSource() {
        val store = SignatureStore(time)
        val first = store.add("A", sketch())
        assertEquals(1_000L, first.createdMillis)
        time.advance(500L)
        val second = store.add("B", sketch())
        assertEquals(1_500L, second.createdMillis)
    }

    @Test
    fun list_preservesInsertionOrder() {
        val store = SignatureStore(time)
        store.add("Alpha", sketch())
        store.add("Beta", sketch())
        store.add("Gamma", sketch())
        assertEquals(listOf("Alpha", "Beta", "Gamma"), store.list().map { it.name })
    }

    @Test
    fun list_remainsOrdered_afterRemoval() {
        val store = SignatureStore(time)
        val a = store.add("Alpha", sketch())
        store.add("Beta", sketch())
        store.remove(a.id)
        assertEquals(listOf("Beta"), store.list().map { it.name })
    }

    @Test
    fun remove_returnsFalseForUnknownId() {
        val store = SignatureStore(time)
        assertEquals(false, store.remove("sig-99"))
    }

    @Test
    fun remove_deletesEntry() {
        val store = SignatureStore(time)
        val entry = store.add("Only", sketch())
        assertTrue(store.remove(entry.id))
        assertEquals(0, store.size)
        assertNull(store.get(entry.id))
    }

    @Test
    fun get_returnsStoredSketch() {
        val store = SignatureStore(time)
        val sketch = sketch()
        val entry = store.add("Keep", sketch)
        assertEquals(sketch, store.get(entry.id)!!.sketch)
    }

    @Test
    fun add_rejectsEmptyName() {
        val store = SignatureStore(time)
        try {
            store.add("", sketch())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("name"))
        }
    }
}
