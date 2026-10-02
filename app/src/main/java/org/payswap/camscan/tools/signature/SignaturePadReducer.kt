package org.payswap.camscan.tools.signature

import org.payswap.camscan.tools.render.Point

// SignaturePadReducer (CAMSCAN-PROD-011 section 6.2): a pure input reducer
// for a live signature pad. It is a deterministic state machine driven by
// pen events; no clocks, no randomness, no android dependency.
//
// Event semantics (documented, binding):
//   - Down(p): starts a new in-progress stroke containing exactly p. If a
//     stroke is already in progress (a missing Up), that stroke is closed
//     and archived FIRST (pen-down implies the previous pen-up).
//   - Move(p): ignored entirely when no stroke is in progress. Otherwise
//     the point is accepted only when its squared Euclidean distance from
//     the current stroke's LAST STORED point is >= minDistancePx^2 (exact
//     integer comparison, no floating point). When smoothing is disabled
//     the raw point is appended; when smoothing is enabled the integer
//     floor MIDPOINT between the last stored point and the raw point is
//     appended instead (the accepted point itself is never stored raw).
//   - Up: closes the in-progress stroke and archives it (even a single
//     point tap becomes a dot stroke). Up with no in-progress stroke is a
//     no-op.
//
// Both thinning and midpoint smoothing are deterministic: the same event
// sequence always yields the same state.

/** Pen events for the signature pad. */
sealed class PadEvent {

    class Down(val point: Point) : PadEvent()

    class Move(val point: Point) : PadEvent()

    object Up : PadEvent() {
        override fun toString(): String = "Up"
    }
}

/** Reducer state: finished strokes plus the in-progress stroke if any. */
class PadState(
    val finishedStrokes: List<SignatureStroke>,
    val inProgress: SignatureStroke?,
) {

    val isClean: Boolean get() = finishedStrokes.isEmpty() && inProgress == null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PadState) return false
        return finishedStrokes == other.finishedStrokes && inProgress == other.inProgress
    }

    override fun hashCode(): Int = 31 * finishedStrokes.hashCode() + (inProgress?.hashCode() ?: 0)

    override fun toString(): String =
        "PadState[finished=" + finishedStrokes.size + ";inProgress=" +
            (inProgress?.points?.size ?: 0) + " pts]"

    companion object {
        val CLEAN: PadState = PadState(emptyList(), null)
    }
}

 // Pure reducer over [PadEvent]s. [minDistancePx] is the point-thinning
 // threshold; [smoothing] toggles midpoint smoothing.
 // /
class SignaturePadReducer(
    val minDistancePx: Int = 2,
    val smoothing: Boolean = false,
) {

    init {
        require(minDistancePx >= 0) { "minDistancePx must be non-negative" }
    }

    /** Pure transition: returns the next state, never mutates [state]. */
    fun reduce(event: PadEvent, state: PadState): PadState = when (event) {
        is PadEvent.Down -> reduceDown(event, state)
        is PadEvent.Move -> reduceMove(event, state)
        is PadEvent.Up -> reduceUp(state)
    }

    private fun reduceDown(event: PadEvent.Down, state: PadState): PadState {
        val closed = closeInProgress(state)
        val fresh = SignatureStroke(
            listOf(event.point),
            DEFAULT_STROKE_WIDTH_PX,
            DEFAULT_STROKE_COLOR,
        )
        return PadState(closed.finishedStrokes, fresh)
    }

    private fun reduceMove(event: PadEvent.Move, state: PadState): PadState {
        val current = state.inProgress ?: return state
        val points = current.points
        val last = points[points.size - 1]
        if (!passesDistanceFilter(last, event.point)) return state
        val appended = if (smoothing) midpoint(last, event.point) else event.point
        val newPoints = ArrayList<Point>(points.size + 1)
        newPoints.addAll(points)
        newPoints.add(appended)
        return PadState(
            state.finishedStrokes,
            SignatureStroke(newPoints, current.strokeWidthPx, current.colorArgb),
        )
    }

    private fun reduceUp(state: PadState): PadState {
        val current = state.inProgress ?: return state
        return PadState(state.finishedStrokes + current, null)
    }

    private fun closeInProgress(state: PadState): PadState {
        val current = state.inProgress ?: return state
        return PadState(state.finishedStrokes + current, null)
    }

    /** Squared-Euclidean gate: dx*dx + dy*dy >= minDistancePx * minDistancePx. */
    private fun passesDistanceFilter(last: Point, candidate: Point): Boolean {
        val threshold = minDistancePx * minDistancePx
        val dx = candidate.x - last.x
        val dy = candidate.y - last.y
        return dx * dx + dy * dy >= threshold
    }

    /** Integer floor midpoint: documented deterministic smoothing point. */
    private fun midpoint(a: Point, b: Point): Point =
        Point((a.x + b.x) / 2, (a.y + b.y) / 2)

    companion object {
        const val DEFAULT_STROKE_WIDTH_PX: Int = 3
        const val DEFAULT_STROKE_COLOR: Long = 0xFF000000L
    }
}
