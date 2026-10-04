package org.payswap.camscan.tools.ui

import org.payswap.camscan.tools.protection.DocumentProtectionPolicy
import org.payswap.camscan.tools.protection.PinProtectionRegistry

// ViewerGate (CAMSCAN-VERIFY-001): the pure unlock gate for one viewer
// instance. The gate is instance-scoped: it starts locked whenever the
// document's policy is PinRequired, unlocks only through a verified PIN,
// survives rotation via an explicit snapshot/restore pair (the fragment
// saves it in onSaveInstanceState), and re-locks on a fresh viewer instance
// (leaving the viewer pops the fragment WITHOUT saving state). Android-free.

/** Instance-scoped lock state for one document viewer. */
class ViewerGate(
    private val protectedNow: () -> Boolean,
    private val verifyPin: (String) -> Boolean,
) {

    private var unlocked = false

    /** True while pages must stay hidden: protected and not yet unlocked. */
    fun isLocked(): Boolean = protectedNow() && !unlocked

    /** True when the document currently carries a PIN policy. */
    fun isProtected(): Boolean = protectedNow()

    /**
     * Attempts [pin]; on success the gate unlocks. Returns the verification
     * outcome (a wrong PIN leaves the gate locked).
     */
    fun attemptUnlock(pin: String): Boolean {
        val verified = verifyPin(pin)
        if (verified) unlocked = true
        return verified
    }

    /** Snapshot for onSaveInstanceState (rotation survival). */
    fun snapshotUnlocked(): Boolean = unlocked

    /** Restores the snapshot: an unlocked viewer stays unlocked after rotation. */
    fun restoreUnlocked(value: Boolean) {
        unlocked = value
    }

    /** Reveals content after protection was removed (PIN cleared). */
    fun onProtectionRemoved() {
        unlocked = true
    }

    /**
     * Locks the gate again (used right after a PIN is set on the currently
     * open viewer so the user sees the gating take effect immediately).
     */
    fun relock() {
        unlocked = false
    }
}

// PinSetFlow (CAMSCAN-VERIFY-001): pure validation of the twice-entry PIN
// confirmation dialog. Digits only, PIN_SET_MIN_LENGTH..PIN_SET_MAX_LENGTH
// digits, and the two entries must match. Android-free.

/** One-shot validator for the set-PIN dialog. */
class PinSetFlow(
    private val minLength: Int = PIN_SET_MIN_LENGTH,
    private val maxLength: Int = PIN_SET_MAX_LENGTH,
) {

    /** Outcome of submitting the two entries. */
    sealed class Result {
        class InvalidLength(val pin: String) : Result()
        object Mismatch : Result()
        class Ready(val pin: String) : Result()
    }

    /** True when the entry is a decimal digit string of valid length. */
    fun validEntry(entry: String): Boolean {
        if (entry.length < minLength || entry.length > maxLength) return false
        for (ch in entry) {
            if (ch < '0' || ch > '9') return false
        }
        return true
    }

    /** Validates and compares the two dialog entries. */
    fun submit(first: String, confirm: String): Result {
        if (!validEntry(first)) return Result.InvalidLength(first)
        if (first != confirm) return Result.Mismatch
        return Result.Ready(first)
    }

    companion object {
        const val PIN_SET_MIN_LENGTH: Int = 4
        const val PIN_SET_MAX_LENGTH: Int = 8
    }
}

// ViewerToolHost (CAMSCAN-VERIFY-001): process-scoped home of the tool
// engines the viewer consumes. MainActivity initializes it in onCreate
// BEFORE super.onCreate (fragment restoration), so factory-instantiated
// fragments can read it at onViewCreated time. The viewer itself cannot
// take constructor injection for these because the read-only HomeFragment
// constructs DocumentViewerFragment directly with the two legacy
// parameters; the NEW tool fragments use proper constructor injection via
// WorkspaceFragmentFactory instead.

/** Process-scoped tool services shared by the viewer tree. */
object ViewerToolHost {

    lateinit var signatureStore: org.payswap.camscan.tools.signature.SignatureStore
        private set

    lateinit var pinRegistry: PinProtectionRegistry
        private set

    lateinit var annotationStore: org.payswap.camscan.tools.annotation.AnnotationStore
        private set

    /** True once [initialize] has run (guards early fragment access). */
    val initialized: Boolean
        get() = ::signatureStore.isInitialized && ::pinRegistry.isInitialized &&
            ::annotationStore.isInitialized

    /** Wires the process-scoped engines; idempotent per process. */
    fun initialize(
        store: org.payswap.camscan.tools.signature.SignatureStore,
        registry: PinProtectionRegistry,
        annotations: org.payswap.camscan.tools.annotation.AnnotationStore,
    ) {
        signatureStore = store
        pinRegistry = registry
        annotationStore = annotations
    }

    /** Current policy for the document (Unprotected when the host is unset). */
    fun policyFor(documentId: String): DocumentProtectionPolicy {
        if (!initialized) return DocumentProtectionPolicy.Unprotected
        return pinRegistry.policyFor(documentId)
    }
}
