package org.payswap.camscan.tools.signature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.render.Point

// SignaturePadReducer tests (CAMSCAN-PROD-011 section 6.6): the pure
// state machine driven through input/output tables - thinning (min
// Euclidean distance), midpoint smoothing, stroke splitting on pen-up and
// implicit pen-down, tap strokes, and determinism.

class SignaturePadReducerTest {

    private fun reducer(min: Int = 2, smoothing: Boolean = false) =
        SignaturePadReducer(minDistancePx = min, smoothing = smoothing)

    private fun pts(state: PadState): List<Point> =
        state.inProgress?.points ?: emptyList()

    @Test
    fun initialState_isClean() {
        assertTrue(PadState.CLEAN.isClean)
        assertNull(PadState.CLEAN.inProgress)
    }

    @Test
    fun down_startsStrokeWithSinglePoint() {
        val state = reducer().reduce(PadEvent.Down(Point(10, 10)), PadState.CLEAN)
        assertEquals(listOf(Point(10, 10)), pts(state))
        assertTrue(state.finishedStrokes.isEmpty())
    }

    @Test
    fun move_withoutDown_isIgnored() {
        val state = reducer().reduce(PadEvent.Move(Point(4, 4)), PadState.CLEAN)
        assertTrue(state.isClean)
    }

    @Test
    fun move_closerThanMinDistance_isFiltered() {
        var state = reducer().reduce(PadEvent.Down(Point(10, 10)), PadState.CLEAN)
        state = reducer().reduce(PadEvent.Move(Point(11, 10)), state)
        assertEquals(listOf(Point(10, 10)), pts(state))
        state = reducer().reduce(PadEvent.Move(Point(11, 11)), state)
        // Squared distance 2 < 4: still filtered.
        assertEquals(listOf(Point(10, 10)), pts(state))
    }

    @Test
    fun move_atExactlyMinDistance_isAccepted() {
        var state = reducer().reduce(PadEvent.Down(Point(10, 10)), PadState.CLEAN)
        state = reducer().reduce(PadEvent.Move(Point(12, 10)), state)
        assertEquals(listOf(Point(10, 10), Point(12, 10)), pts(state))
    }

    @Test
    fun move_acceptsEuclideanDistance_beyondManhattanShortfall() {
        var state = reducer().reduce(PadEvent.Down(Point(0, 0)), PadState.CLEAN)
        // dx=2, dy=1: squared distance 5 >= 4 even though Chebyshev would
        // need 2 and Manhattan would need 3 - the documented gate is
        // squared Euclidean.
        state = reducer().reduce(PadEvent.Move(Point(2, 1)), state)
        assertEquals(listOf(Point(0, 0), Point(2, 1)), pts(state))
    }

    @Test
    fun move_appendsRawPoint_whenSmoothingDisabled() {
        var state = reducer().reduce(PadEvent.Down(Point(0, 0)), PadState.CLEAN)
        state = reducer().reduce(PadEvent.Move(Point(10, 4)), state)
        assertEquals(listOf(Point(0, 0), Point(10, 4)), pts(state))
    }

    @Test
    fun move_appendsFloorMidpoint_whenSmoothingEnabled() {
        var state = reducer(smoothing = true).reduce(PadEvent.Down(Point(0, 0)), PadState.CLEAN)
        state = reducer(smoothing = true).reduce(PadEvent.Move(Point(10, 0)), state)
        state = reducer(smoothing = true).reduce(PadEvent.Move(Point(10, 4)), state)
        assertEquals(listOf(Point(0, 0), Point(5, 0), Point(7, 2)), pts(state))
    }

    @Test
    fun smoothingMidpoint_usesIntegerFloorDivision() {
        var state = reducer(smoothing = true).reduce(PadEvent.Down(Point(1, 1)), PadState.CLEAN)
        // dx=3, dy=2 -> midpoint (1+4)/2=2, (1+3)/2=2.
        state = reducer(smoothing = true).reduce(PadEvent.Move(Point(4, 3)), state)
        assertEquals(listOf(Point(1, 1), Point(2, 2)), pts(state))
    }

    @Test
    fun up_archivesInProgressStroke() {
        var state = reducer().reduce(PadEvent.Down(Point(0, 0)), PadState.CLEAN)
        state = reducer().reduce(PadEvent.Move(Point(5, 0)), state)
        state = reducer().reduce(PadEvent.Up, state)
        assertNull(state.inProgress)
        assertEquals(1, state.finishedStrokes.size)
        assertEquals(listOf(Point(0, 0), Point(5, 0)), state.finishedStrokes[0].points)
        assertEquals(SignaturePadReducer.DEFAULT_STROKE_WIDTH_PX, state.finishedStrokes[0].strokeWidthPx)
    }

    @Test
    fun up_withoutStroke_isNoOp() {
        val state = reducer().reduce(PadEvent.Up, PadState.CLEAN)
        assertTrue(state.isClean)
    }

    @Test
    fun tap_producesSinglePointStroke() {
        var state = reducer().reduce(PadEvent.Down(Point(7, 7)), PadState.CLEAN)
        state = reducer().reduce(PadEvent.Up, state)
        assertEquals(1, state.finishedStrokes.size)
        assertEquals(listOf(Point(7, 7)), state.finishedStrokes[0].points)
    }

    @Test
    fun down_whileInProgress_closesAndArchivesPreviousStroke() {
        var state = reducer().reduce(PadEvent.Down(Point(0, 0)), PadState.CLEAN)
        state = reducer().reduce(PadEvent.Move(Point(6, 0)), state)
        state = reducer().reduce(PadEvent.Down(Point(10, 10)), state)
        assertEquals(1, state.finishedStrokes.size)
        assertEquals(listOf(Point(0, 0), Point(6, 0)), state.finishedStrokes[0].points)
        assertEquals(listOf(Point(10, 10)), pts(state))
    }

    @Test
    fun multiStrokeSequence_matchesInputOutputTable() {
        val r = reducer()
        var state = PadState.CLEAN
        state = r.reduce(PadEvent.Down(Point(0, 0)), state)
        state = r.reduce(PadEvent.Move(Point(3, 0)), state)
        state = r.reduce(PadEvent.Move(Point(3, 4)), state)
        state = r.reduce(PadEvent.Up, state)
        state = r.reduce(PadEvent.Down(Point(9, 9)), state)
        state = r.reduce(PadEvent.Move(Point(9, 12)), state)
        state = r.reduce(PadEvent.Up, state)

        assertEquals(2, state.finishedStrokes.size)
        assertEquals(listOf(Point(0, 0), Point(3, 0), Point(3, 4)), state.finishedStrokes[0].points)
        assertEquals(listOf(Point(9, 9), Point(9, 12)), state.finishedStrokes[1].points)
        assertNull(state.inProgress)
    }

    @Test
    fun filteringComparesAgainstLastStoredPoint_notLastRawEvent() {
        val r = reducer(min = 4)
        var state = r.reduce(PadEvent.Down(Point(0, 0)), PadState.CLEAN)
        // Both moves are within 4 of the STORED point (0,0) - filtered.
        state = r.reduce(PadEvent.Move(Point(3, 0)), state)
        state = r.reduce(PadEvent.Move(Point(0, 3)), state)
        assertEquals(listOf(Point(0, 0)), pts(state))
        // This one is at distance 4 exactly - accepted.
        state = r.reduce(PadEvent.Move(Point(4, 0)), state)
        assertEquals(listOf(Point(0, 0), Point(4, 0)), pts(state))
    }

    @Test
    fun reducer_isDeterministic_replayTwice() {
        val r = reducer(smoothing = true)
        val events = listOf(
            PadEvent.Down(Point(2, 2)),
            PadEvent.Move(Point(6, 3)),
            PadEvent.Move(Point(9, 8)),
            PadEvent.Up,
            PadEvent.Down(Point(20, 20)),
            PadEvent.Move(Point(30, 21)),
            PadEvent.Up,
        )
        var first = PadState.CLEAN
        var second = PadState.CLEAN
        for (event in events) {
            first = r.reduce(event, first)
            second = r.reduce(event, second)
        }
        assertEquals(first, second)
    }

    @Test
    fun reducer_neverMutatesGivenState() {
        val r = reducer()
        val state = r.reduce(PadEvent.Down(Point(0, 0)), PadState.CLEAN)
        val snapshot = state.finishedStrokes.toList()
        r.reduce(PadEvent.Move(Point(9, 9)), state)
        r.reduce(PadEvent.Up, state)
        assertEquals(snapshot, state.finishedStrokes)
        assertEquals(listOf(Point(0, 0)), state.inProgress!!.points)
    }

    @Test
    fun strokeCarries_defaultWidthAndColor() {
        val state = reducer().reduce(PadEvent.Down(Point(1, 1)), PadState.CLEAN)
        val stroke = state.inProgress!!
        assertEquals(SignaturePadReducer.DEFAULT_STROKE_WIDTH_PX, stroke.strokeWidthPx)
        assertEquals(SignaturePadReducer.DEFAULT_STROKE_COLOR, stroke.colorArgb)
    }
}
