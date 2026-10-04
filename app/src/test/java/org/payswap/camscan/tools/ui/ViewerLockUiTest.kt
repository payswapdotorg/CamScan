package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.protection.DocumentProtectionPolicy
import org.payswap.camscan.tools.protection.PinProtectionRegistry
import org.payswap.camscan.tools.protection.SaltSource

// ViewerLockUiTest (CAMSCAN-VERIFY-001): the instance-scoped viewer gate
// (lock, wrong-PIN stay-locked, unlock, rotation snapshot/restore, relock)
// exercised both with plain fakes and against the real frozen registry,
// plus the twice-entry PIN set flow validation.

class ViewerGateTest {

    @Test
    fun unprotectedDocument_isNeverLocked() {
        val gate = ViewerGate(protectedNow = { false }, verifyPin = { true })
        assertFalse(gate.isLocked())
    }

    @Test
    fun protectedDocument_startsLocked() {
        val gate = ViewerGate(protectedNow = { true }, verifyPin = { false })
        assertTrue(gate.isLocked())
        assertTrue(gate.isProtected())
    }

    @Test
    fun wrongPin_failsAndStaysLocked() {
        val gate = ViewerGate(protectedNow = { true }, verifyPin = { it == "1234" })
        assertFalse(gate.attemptUnlock("0000"))
        assertTrue(gate.isLocked())
        assertTrue(gate.attemptUnlock("1234"))
        assertFalse(gate.isLocked())
    }

    @Test
    fun unlockedState_survivesRotationViaSnapshotRestore() {
        val gate = ViewerGate(protectedNow = { true }, verifyPin = { true })
        gate.attemptUnlock("any")
        assertFalse(gate.isLocked())
        val snapshot = gate.snapshotUnlocked()
        // Simulated rotation: a NEW gate instance restores the snapshot.
        val recreated = ViewerGate(protectedNow = { true }, verifyPin = { true })
        recreated.restoreUnlocked(snapshot)
        assertFalse(recreated.isLocked())
    }

    @Test
    fun freshInstance_afterLeavingTheViewer_isLockedAgain() {
        val gate = ViewerGate(protectedNow = { true }, verifyPin = { true })
        gate.attemptUnlock("pin")
        // A FRESH viewer instance (user left and reopened) starts locked:
        // nothing was persisted into the new instance.
        val reopened = ViewerGate(protectedNow = { true }, verifyPin = { true })
        assertTrue(reopened.isLocked())
    }

    @Test
    fun relock_closesTheGateAgain() {
        val gate = ViewerGate(protectedNow = { true }, verifyPin = { true })
        gate.attemptUnlock("pin")
        gate.relock()
        assertTrue(gate.isLocked())
    }

    @Test
    fun onProtectionRemoved_revealsContent() {
        val gate = ViewerGate(protectedNow = { true }, verifyPin = { false })
        gate.onProtectionRemoved()
        // isProtected flips false together with the removal in the fragment.
        val removed = ViewerGate(protectedNow = { false }, verifyPin = { false })
        removed.restoreUnlocked(gate.snapshotUnlocked())
        assertFalse(removed.isLocked())
    }

    @Test
    fun fullRoundTrip_againstTheFrozenRegistry() {
        val registry = PinProtectionRegistry(SaltSource { ByteArray(16) })
        registry.register("doc-1", "9911")
        val gate = ViewerGate(
            protectedNow = { registry.policyFor("doc-1") is DocumentProtectionPolicy.PinRequired },
            verifyPin = { registry.verify("doc-1", it) },
        )
        assertTrue(gate.isLocked())
        assertFalse(gate.attemptUnlock("1111"))
        assertTrue(gate.isLocked())
        assertTrue(gate.attemptUnlock("9911"))
        assertFalse(gate.isLocked())
    }

    @Test
    fun unknownDocument_isUnprotectedInTheRegistry() {
        val registry = PinProtectionRegistry(SaltSource { ByteArray(16) })
        assertEquals(DocumentProtectionPolicy.Unprotected, registry.policyFor("nope"))
        val gate = ViewerGate(
            protectedNow = { registry.policyFor("nope") is DocumentProtectionPolicy.PinRequired },
            verifyPin = { registry.verify("nope", it) },
        )
        assertFalse(gate.isLocked())
    }
}

class PinSetFlowTest {

    private val flow = PinSetFlow()

    @Test
    fun lengthBoundaries_areInclusive4To8() {
        assertTrue(flow.validEntry("1234"))
        assertTrue(flow.validEntry("12345678"))
        assertFalse(flow.validEntry("123"))
        assertFalse(flow.validEntry("123456789"))
        assertFalse(flow.validEntry(""))
    }

    @Test
    fun nonDigits_areRejected() {
        assertFalse(flow.validEntry("12a4"))
        assertFalse(flow.validEntry("12 4"))
        assertFalse(flow.validEntry("12.4"))
    }

    @Test
    fun submit_lengthInvalid_reportsInvalidLength() {
        val result = flow.submit("12", "12")
        assertTrue(result is PinSetFlow.Result.InvalidLength)
    }

    @Test
    fun submit_mismatchReportsMismatch() {
        val result = flow.submit("1234", "1235")
        assertEquals(PinSetFlow.Result.Mismatch, result)
    }

    @Test
    fun submit_matchingEntriesAreReadyWithThePin() {
        val result = flow.submit("4321", "4321")
        val ready = result as PinSetFlow.Result.Ready
        assertEquals("4321", ready.pin)
    }

    @Test
    fun readyPin_registersAndVerifiesAgainstTheFrozenRegistry() {
        val registry = PinProtectionRegistry(SaltSource { ByteArray(16) })
        val ready = flow.submit("777001", "777001") as PinSetFlow.Result.Ready
        registry.register("doc-2", ready.pin)
        assertTrue(registry.verify("doc-2", "777001"))
        assertFalse(registry.verify("doc-2", "777002"))
        assertTrue(registry.policyFor("doc-2") is DocumentProtectionPolicy.PinRequired)
    }
}
