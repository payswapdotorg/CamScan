package org.payswap.camscan.tools.ui

import org.payswap.camscan.tools.signature.PadEvent
import org.payswap.camscan.tools.signature.PadState
import org.payswap.camscan.tools.signature.SignaturePadReducer
import org.payswap.camscan.tools.signature.SignatureSketch
import org.payswap.camscan.tools.signature.SignatureStroke
import org.payswap.camscan.tools.render.Point

// SignaturePadUi (CAMSCAN-VERIFY-001): pure controller behind the signature
// pad overlay. Touch events are translated 1:1 into PadEvents for the frozen
// SignaturePadReducer; save gating (empty sketch rejected) and signature
// naming rules live here so the fragment stays thin. Android-free.

/** Mutable controller over one live signature pad session. */
class SignaturePadUi(
    private val reducer: SignaturePadReducer = SignaturePadReducer(),
    private var padState: PadState = PadState.CLEAN,
) {

    /** Current reducer state (finished strokes plus in-progress stroke). */
    val state: PadState get() = padState

    /** Begins a stroke at (x, y); closes any in-progress stroke first. */
    fun penDown(x: Int, y: Int) {
        padState = reducer.reduce(PadEvent.Down(Point(x, y)), padState)
    }

    /** Extends the in-progress stroke; ignored when no stroke is active. */
    fun penMove(x: Int, y: Int) {
        padState = reducer.reduce(PadEvent.Move(Point(x, y)), padState)
    }

    /** Ends the in-progress stroke (a dot stroke survives as one point). */
    fun penUp() {
        padState = reducer.reduce(PadEvent.Up, padState)
    }

    /** Clears all ink; the pad returns to the clean state. */
    fun clear() {
        padState = PadState.CLEAN
    }

    /** True when at least one point of ink exists (finished or in progress). */
    fun hasInk(): Boolean = !padState.isClean

    /**
     * The sketch to save: finished strokes plus the in-progress stroke
     * (closed), in stroke order. Empty when the pad is clean.
     */
    fun sketch(): SignatureSketch {
        val strokes = ArrayList<SignatureStroke>(padState.finishedStrokes.size + 1)
        strokes.addAll(padState.finishedStrokes)
        padState.inProgress?.let { strokes.add(it) }
        return SignatureSketch(strokes)
    }

    companion object {
        /** Signature names: 1..MAX_NAME_LENGTH chars after trimming. */
        const val MAX_NAME_LENGTH: Int = 40

        /** A store name is valid when the trimmed form is non-empty and short. */
        fun validName(name: String): Boolean {
            val trimmed = name.trim()
            return trimmed.isNotEmpty() && trimmed.length <= MAX_NAME_LENGTH
        }
    }
}
