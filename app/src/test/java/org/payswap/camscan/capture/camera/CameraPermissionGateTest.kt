package org.payswap.camscan.capture.camera


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * CAMSCAN-PROD-001 §6.7 — exhaustive transition-table tests for the pure
 * permission gate. Pure JUnit 4: no Android classes, so it compiles and runs
 * under `./gradlew :app:testDebugUnitTest` on any JVM.
 */
class CameraPermissionGateTest {

    private val allStates = CameraPermissionState.values().toList()

    private fun gate(
        rationale: Boolean,
        initial: CameraPermissionState = CameraPermissionState.NOT_REQUESTED,
    ): CameraPermissionGate = CameraPermissionGate(RationaleChecker { rationale }, initial)

    // ------------------------------------------------------------------ defaults

    @Test
    fun defaultStateIsNotRequested() {
        assertEquals(CameraPermissionState.NOT_REQUESTED, gate(rationale = true).state)
    }

    @Test
    fun initialStatesAreHonored() {
        for (initial in allStates) {
            assertEquals("initial=$initial", initial, gate(rationale = true, initial = initial).state)
        }
    }

    // ------------------------------------------------------------ requestStarted

    @Test
    fun requestStartedFromNotRequestedIsLegal() {
        val gate = gate(rationale = true)
        assertTrue(gate.requestStarted())
        assertEquals(CameraPermissionState.REQUESTED, gate.state)
    }

    @Test
    fun requestStartedFromDeniedSoftIsLegal() {
        val gate = gate(rationale = true)
        gate.requestStarted()
        gate.onPermissionResult(granted = false) // rationale true → DENIED_SOFT
        assertEquals(CameraPermissionState.DENIED_SOFT, gate.state)

        assertTrue(gate.requestStarted())
        assertEquals(CameraPermissionState.REQUESTED, gate.state)
    }

    @Test
    fun requestStartedIsRejectedFromRequestedGrantedDeniedSoftPermanentAndRecordsDiagnostics() {
        val rejectedFrom = listOf(
            CameraPermissionState.REQUESTED,
            CameraPermissionState.GRANTED,
            CameraPermissionState.DENIED_PERMANENT,
        )
        for (initial in rejectedFrom) {
            val gate = gate(rationale = true, initial = initial)
            val diagnosticsBefore = gate.diagnostics.size
            assertFalse("requestStarted must be rejected from $initial", gate.requestStarted())
            assertEquals("state must be unchanged after rejection from $initial", initial, gate.state)
            assertEquals(
                "one diagnostic must be recorded for the rejection from $initial",
                diagnosticsBefore + 1,
                gate.diagnostics.size,
            )
        }
    }

    @Test
    fun rejectionDiagnosticsNameTheEventAndState() {
        val gate = gate(rationale = true, initial = CameraPermissionState.GRANTED)
        gate.requestStarted()
        val last = gate.diagnostics.last()
        assertTrue(last.contains("requestStarted"))
        assertTrue(last.contains("GRANTED"))
    }

    // ------------------------------------------------------- onPermissionResult

    @Test
    fun grantedResultAlwaysYieldsGrantedFromEveryState() {
        for (initial in allStates) {
            for (rationale in listOf(true, false)) {
                val gate = gate(rationale = rationale, initial = initial)
                assertTrue(
                    "from $initial with rationale=$rationale",
                    gate.onPermissionResult(granted = true),
                )
                assertEquals(
                    "from $initial with rationale=$rationale",
                    CameraPermissionState.GRANTED,
                    gate.state,
                )
            }
        }
    }

    @Test
    fun deniedResultWithRationaleYieldsDeniedSoftFromEveryState() {
        for (initial in allStates) {
            val gate = gate(rationale = true, initial = initial)
            assertTrue("from $initial", gate.onPermissionResult(granted = false))
            assertEquals("from $initial", CameraPermissionState.DENIED_SOFT, gate.state)
        }
    }

    @Test
    fun deniedResultWithoutRationaleYieldsDeniedPermanentFromEveryState() {
        for (initial in allStates) {
            val gate = gate(rationale = false, initial = initial)
            assertTrue("from $initial", gate.onPermissionResult(granted = false))
            assertEquals("from $initial", CameraPermissionState.DENIED_PERMANENT, gate.state)
        }
    }

    @Test
    fun permanentDenialIsReachedOnlyWhenDeniedAndRationaleIsFalse() {
        // The dialog path: request → denied.
        val permanent = gate(rationale = false)
        permanent.requestStarted()
        permanent.onPermissionResult(granted = false)
        assertEquals(CameraPermissionState.DENIED_PERMANENT, permanent.state)

        // The same path with a still-showable rationale stays soft.
        val soft = gate(rationale = true)
        soft.requestStarted()
        soft.onPermissionResult(granted = false)
        assertEquals(CameraPermissionState.DENIED_SOFT, soft.state)

        // A grant never produces a denial state, whatever the rationale probe says.
        val granted = gate(rationale = false)
        granted.requestStarted()
        granted.onPermissionResult(granted = true)
        assertEquals(CameraPermissionState.GRANTED, granted.state)
    }

    @Test
    fun permanentDenialDeEscalatesWhenRationaleBecomesAvailableAgain() {
        val gate = gate(rationale = false)
        gate.onPermissionResult(granted = false)
        assertEquals(CameraPermissionState.DENIED_PERMANENT, gate.state)

        // The system can reset "don't ask again"; a later denial with a
        // showable rationale then re-classifies as soft.
        var rationaleVisible = false
        val resettableGate = CameraPermissionGate(
            rationaleChecker = RationaleChecker { rationaleVisible },
            initial = CameraPermissionState.DENIED_PERMANENT,
        )
        rationaleVisible = true
        resettableGate.onPermissionResult(granted = false)
        assertEquals(CameraPermissionState.DENIED_SOFT, resettableGate.state)
    }

    @Test
    fun onPermissionResultNeverRecordsRejections() {
        for (initial in allStates) {
            for (rationale in listOf(true, false)) {
                val gate = gate(rationale = rationale, initial = initial)
                gate.onPermissionResult(granted = false)
                gate.onPermissionResult(granted = true)
                assertTrue(
                    "OS answers are facts, never rejections (from $initial, rationale=$rationale)",
                    gate.diagnostics.isEmpty(),
                )
            }
        }
    }

    // ----------------------------------------------------------------- canRequest

    @Test
    fun canRequestIsTrueOnlyFromNotRequestedAndDeniedSoft() {
        val expectations = mapOf(
            CameraPermissionState.NOT_REQUESTED to true,
            CameraPermissionState.REQUESTED to false,
            CameraPermissionState.GRANTED to false,
            CameraPermissionState.DENIED_SOFT to true,
            CameraPermissionState.DENIED_PERMANENT to false,
        )
        for ((state, expected) in expectations) {
            assertEquals("canRequest from $state", expected, gate(rationale = true, initial = state).canRequest)
        }
    }

    // ------------------------------------------------------- full dialog journeys

    @Test
    fun fullGrantJourneyNotRequestedToGranted() {
        val gate = gate(rationale = true)
        assertTrue(gate.canRequest)
        assertTrue(gate.requestStarted())
        assertTrue(gate.onPermissionResult(granted = true))
        assertEquals(CameraPermissionState.GRANTED, gate.state)
        assertFalse(gate.canRequest)
    }

    @Test
    fun softDenialThenRetryThenGrantJourney() {
        val gate = gate(rationale = true)
        gate.requestStarted()
        gate.onPermissionResult(granted = false)
        assertEquals(CameraPermissionState.DENIED_SOFT, gate.state)
        assertTrue(gate.canRequest)

        assertTrue(gate.requestStarted())
        assertTrue(gate.onPermissionResult(granted = true))
        assertEquals(CameraPermissionState.GRANTED, gate.state)
    }

    @Test
    fun permanentDenialBlocksFurtherRequests() {
        val gate = gate(rationale = false)
        gate.requestStarted()
        gate.onPermissionResult(granted = false)
        assertEquals(CameraPermissionState.DENIED_PERMANENT, gate.state)
        assertFalse(gate.canRequest)
        assertFalse(gate.requestStarted())
        assertEquals(CameraPermissionState.DENIED_PERMANENT, gate.state)

        // The recovery path out of DENIED_PERMANENT is a real OS grant
        // (settings), not another request.
        assertTrue(gate.onPermissionResult(granted = true))
        assertEquals(CameraPermissionState.GRANTED, gate.state)
    }

    // -------------------------------------------------------------- determinism

    @Test
    fun sameInputSequenceProducesSameOutputSequence() {
        fun run(): Pair<CameraPermissionState, List<String>> {
            val gate = gate(rationale = true)
            gate.requestStarted()
            gate.onPermissionResult(granted = false)
            gate.requestStarted()
            gate.onPermissionResult(granted = false)
            gate.onPermissionResult(granted = true)
            gate.requestStarted() // rejected from GRANTED
            return gate.state to gate.diagnostics
        }

        assertEquals(run(), run())
    }
}
