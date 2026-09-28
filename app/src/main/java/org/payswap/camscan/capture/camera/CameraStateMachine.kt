package org.payswap.camscan.capture.camera


/**
 * CAMSCAN-PROD-001 §6.2 — capture lifecycle state machine, pure Kotlin.
 *
 * Owns the camera open/ready/capture/error lifecycle for the scan surface.
 * Pure JVM logic: no android.* imports — CameraX glue feeds events in
 * (CameraController) so the table is exhaustively unit-testable on the JVM.
 *
 * States:
 *  - [CameraState.IDLE]      — constructed, nothing bound yet.
 *  - [CameraState.OPENING]   — a bind (or rebind) is in flight.
 *  - [CameraState.READY]     — camera bound, still capture possible.
 *  - [CameraState.CAPTURING] — a still capture is in flight.
 *  - [CameraState.ERROR]     — permanent failure; terminal until close().
 *  - [CameraState.CLOSED]    — released; terminal.
 *
 * Transition table (exhaustive; see CameraStateMachineTest):
 *  - bindStarted(): IDLE → OPENING; OPENING → OPENING (rebind, idempotent);
 *    READY → OPENING (lens switch / rebind). Rejected in CAPTURING, ERROR, CLOSED.
 *  - cameraReady(): OPENING → READY. Rejected everywhere else.
 *  - captureStarted(): READY → CAPTURING. Rejected everywhere else.
 *  - captureComplete(): CAPTURING → READY (re-arm for the next shot).
 *    Rejected everywhere else.
 *  - captureFailed(): CAPTURING → READY (re-arm after a failed shot).
 *    Rejected everywhere else.
 *  - cameraError(recoverable = true): OPENING → OPENING, READY → OPENING,
 *    CAPTURING → OPENING — the recoverable path rebinds. Rejected in IDLE
 *    (nothing bound can fail), ERROR and CLOSED.
 *  - cameraError(recoverable = false): OPENING/READY/CAPTURING → ERROR; ERROR →
 *    ERROR (idempotent: real camera stacks emit several error callbacks);
 *    rejected in IDLE and CLOSED.
 *  - close(): any state → CLOSED; idempotent from CLOSED. Terminal cleanup is
 *    always permitted.
 *
 * Rejected events return false, append a diagnostic string and never throw.
 * Deterministic: same event sequence → same states and diagnostics.
 */
enum class CameraState { IDLE, OPENING, READY, CAPTURING, ERROR, CLOSED }


class CameraStateMachine(initial: CameraState = CameraState.IDLE) {
    var state: CameraState = initial
        private set

    private val diagnosticsInternal = mutableListOf<String>()

    /** Chronological record of rejected events; diagnostics only, never a crash path. */
    val diagnostics: List<String>
        get() = diagnosticsInternal.toList()

    /** A bind (or rebind) of the camera use cases has started. */
    fun bindStarted(): Boolean = when (state) {
        CameraState.IDLE,
        CameraState.OPENING,
        CameraState.READY,
        -> {
            state = CameraState.OPENING
            true
        }

        else -> reject("bindStarted")
    }

    /** The bind completed and the camera is usable. */
    fun cameraReady(): Boolean = when (state) {
        CameraState.OPENING -> {
            state = CameraState.READY
            true
        }

        else -> reject("cameraReady")
    }

    /** A still capture has started. */
    fun captureStarted(): Boolean = when (state) {
        CameraState.READY -> {
            state = CameraState.CAPTURING
            true
        }

        else -> reject("captureStarted")
    }

    /** A still capture completed successfully; the surface re-arms for the next shot. */
    fun captureComplete(): Boolean = when (state) {
        CameraState.CAPTURING -> {
            state = CameraState.READY
            true
        }

        else -> reject("captureComplete")
    }

    /** A still capture failed; the surface re-arms for the next shot. */
    fun captureFailed(): Boolean = when (state) {
        CameraState.CAPTURING -> {
            state = CameraState.READY
            true
        }

        else -> reject("captureFailed")
    }

    /**
     * The camera reported an error. A recoverable error returns to OPENING
     * (rebind path); a permanent one goes to ERROR, terminal until [close].
     */
    fun cameraError(recoverable: Boolean): Boolean = when (state) {
        CameraState.OPENING,
        CameraState.READY,
        CameraState.CAPTURING,
        -> {
            state = if (recoverable) CameraState.OPENING else CameraState.ERROR
            true
        }

        CameraState.ERROR -> if (recoverable) {
            reject("cameraError(recoverable=true)")
        } else {
            // Permanent error re-asserted while already terminal: idempotent no-op
            // (real camera stacks emit more than one error callback).
            true
        }

        else -> reject("cameraError(recoverable=$recoverable)")
    }

    /** Releases the camera; legal from any state and idempotent. */
    fun close(): Boolean {
        if (state != CameraState.CLOSED) {
            state = CameraState.CLOSED
        }
        return true
    }

    private fun reject(event: String): Boolean {
        diagnosticsInternal.add("CameraStateMachine: event $event rejected in state $state")
        return false
    }
}
