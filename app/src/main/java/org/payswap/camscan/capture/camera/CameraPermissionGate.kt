package org.payswap.camscan.capture.camera


/**
 * CAMSCAN-PROD-001 §6.1 — camera permission state machine, pure Kotlin.
 *
 * Models the runtime CAMERA-permission UX lifecycle for the scan surface. Pure
 * JVM logic: no android.* imports — the shell feeds OS facts in via
 * [onPermissionResult] and the injected [RationaleChecker] so the whole table
 * is exhaustively unit-testable on the JVM.
 *
 * States:
 *  - [CameraPermissionState.NOT_REQUESTED]    — no in-session request yet.
 *  - [CameraPermissionState.REQUESTED]        — a system dialog is in flight.
 *  - [CameraPermissionState.GRANTED]          — camera usable.
 *  - [CameraPermissionState.DENIED_SOFT]      — denied, system still shows the dialog.
 *  - [CameraPermissionState.DENIED_PERMANENT] — denied AND "don't ask again".
 *
 * Transition table (exhaustive; see CameraPermissionGateTest):
 *  - requestStarted(): legal from NOT_REQUESTED and DENIED_SOFT → REQUESTED
 *    (re-request). Rejected in REQUESTED, GRANTED and DENIED_PERMANENT.
 *  - onPermissionResult(granted = true): accepted from any state → GRANTED.
 *    Covers the system-dialog grant, the pre-granted session (permission
 *    already granted when the surface opens) and a grant made via system
 *    settings while the surface is showing.
 *  - onPermissionResult(granted = false): accepted from any state; the target
 *    is DENIED_SOFT when the rationale is still showable, otherwise
 *    DENIED_PERMANENT. DENIED_PERMANENT is therefore reached ONLY on a denial
 *    combined with rationale == false; a rationale-visible denial always
 *    (de-)escalates to DENIED_SOFT because the system remains willing to ask.
 *
 * Rejected events return false, append a diagnostic string and never throw.
 * The gate is deterministic: same inputs → same outputs, no time, no
 * randomness.
 */
enum class CameraPermissionState { NOT_REQUESTED, REQUESTED, GRANTED, DENIED_SOFT, DENIED_PERMANENT }


/**
 * Probe for the OS "should we still show the request/rationale dialog" fact.
 * Implemented by the shell with the platform API; injected so the gate stays
 * JVM-pure.
 */
fun interface RationaleChecker {
    /** True when the system would still show the permission dialog/rationale. */
    fun shouldShowRationale(): Boolean
}


class CameraPermissionGate(
    private val rationaleChecker: RationaleChecker,
    initial: CameraPermissionState = CameraPermissionState.NOT_REQUESTED,
) {
    var state: CameraPermissionState = initial
        private set

    /**
     * Re-requesting the permission is allowed from NOT_REQUESTED and DENIED_SOFT
     * only (CAMSCAN-PROD-001 §6.1).
     */
    val canRequest: Boolean
        get() = state == CameraPermissionState.NOT_REQUESTED || state == CameraPermissionState.DENIED_SOFT

    private val diagnosticsInternal = mutableListOf<String>()

    /** Chronological record of rejected events; diagnostics only, never a crash path. */
    val diagnostics: List<String>
        get() = diagnosticsInternal.toList()

    /**
     * The shell starts a permission request (system dialog about to appear).
     * Legal from NOT_REQUESTED and DENIED_SOFT; rejected otherwise.
     */
    fun requestStarted(): Boolean = when (state) {
        CameraPermissionState.NOT_REQUESTED,
        CameraPermissionState.DENIED_SOFT,
        -> {
            state = CameraPermissionState.REQUESTED
            true
        }

        else -> {
            reject("requestStarted", state)
            false
        }
    }

    /**
     * A permission answer arrived (system dialog result, an OS-state re-check on
     * resume, or a return from system settings). Always applied — an OS answer is
     * a fact, not a request — and returns true.
     */
    fun onPermissionResult(granted: Boolean): Boolean {
        state = when {
            granted -> CameraPermissionState.GRANTED
            rationaleChecker.shouldShowRationale() -> CameraPermissionState.DENIED_SOFT
            else -> CameraPermissionState.DENIED_PERMANENT
        }
        return true
    }

    private fun reject(event: String, from: CameraPermissionState) {
        diagnosticsInternal.add("CameraPermissionGate: event $event rejected in state $from")
    }
}
