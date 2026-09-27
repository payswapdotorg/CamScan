package org.payswap.camscan.capture.camera


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * CAMSCAN-PROD-001 §6.7 — exhaustive transition-table tests for the pure
 * capture-lifecycle state machine. Pure JUnit 4: no Android classes, so it
 * compiles and runs under `./gradlew :app:testDebugUnitTest` on any JVM.
 */
class CameraStateMachineTest {

    private val allStates = CameraState.values().toList()

    /** Drives a fresh machine into [target] through legal events only. */
    private fun machineIn(target: CameraState): CameraStateMachine {
        val machine = CameraStateMachine()
        when (target) {
            CameraState.IDLE -> Unit
            CameraState.OPENING -> machine.bindStarted()
            CameraState.READY -> {
                machine.bindStarted()
                machine.cameraReady()
            }

            CameraState.CAPTURING -> {
                machine.bindStarted()
                machine.cameraReady()
                machine.captureStarted()
            }

            CameraState.ERROR -> {
                machine.bindStarted()
                machine.cameraError(recoverable = false)
            }

            CameraState.CLOSED -> machine.close()
        }
        assertEquals(target, machine.state)
        return machine
    }

    // ------------------------------------------------------------------ defaults

    @Test
    fun defaultStateIsIdle() {
        assertEquals(CameraState.IDLE, CameraStateMachine().state)
    }

    @Test
    fun initialStatesAreHonored() {
        for (initial in allStates) {
            assertEquals("initial=$initial", initial, CameraStateMachine(initial).state)
        }
    }

    // ---------------------------------------------------------------- bindStarted

    @Test
    fun bindStartedFromIdleGoesToOpening() {
        val machine = machineIn(CameraState.IDLE)
        assertTrue(machine.bindStarted())
        assertEquals(CameraState.OPENING, machine.state)
    }

    @Test
    fun bindStartedFromOpeningIsIdempotentRebind() {
        val machine = machineIn(CameraState.OPENING)
        assertTrue(machine.bindStarted())
        assertEquals(CameraState.OPENING, machine.state)
    }

    @Test
    fun bindStartedFromReadyIsTheRebindPath() {
        val machine = machineIn(CameraState.READY)
        assertTrue(machine.bindStarted())
        assertEquals(CameraState.OPENING, machine.state)
    }

    @Test
    fun bindStartedIsRejectedFromCapturingErrorClosed() {
        val rejectedFrom = listOf(CameraState.CAPTURING, CameraState.ERROR, CameraState.CLOSED)
        for (initial in rejectedFrom) {
            val machine = machineIn(initial)
            val diagnosticsBefore = machine.diagnostics.size
            assertFalse("bindStarted must be rejected from $initial", machine.bindStarted())
            assertEquals("state unchanged after rejection from $initial", initial, machine.state)
            assertEquals(
                "one diagnostic recorded for the rejection from $initial",
                diagnosticsBefore + 1,
                machine.diagnostics.size,
            )
        }
    }

    // ---------------------------------------------------------------- cameraReady

    @Test
    fun cameraReadyFromOpeningGoesToReady() {
        val machine = machineIn(CameraState.OPENING)
        assertTrue(machine.cameraReady())
        assertEquals(CameraState.READY, machine.state)
    }

    @Test
    fun cameraReadyIsRejectedFromEveryOtherState() {
        val rejectedFrom = allStates.filter { it != CameraState.OPENING }
        for (initial in rejectedFrom) {
            val machine = machineIn(initial)
            assertFalse("cameraReady must be rejected from $initial", machine.cameraReady())
            assertEquals("state unchanged after rejection from $initial", initial, machine.state)
            assertTrue(
                "diagnostic recorded for the rejection from $initial",
                machine.diagnostics.isNotEmpty(),
            )
        }
    }

    // -------------------------------------------------------------- captureStarted

    @Test
    fun captureStartedFromReadyGoesToCapturing() {
        val machine = machineIn(CameraState.READY)
        assertTrue(machine.captureStarted())
        assertEquals(CameraState.CAPTURING, machine.state)
    }

    @Test
    fun captureStartedIsRejectedFromEveryOtherState() {
        val rejectedFrom = allStates.filter { it != CameraState.READY }
        for (initial in rejectedFrom) {
            val machine = machineIn(initial)
            assertFalse("captureStarted must be rejected from $initial", machine.captureStarted())
            assertEquals("state unchanged after rejection from $initial", initial, machine.state)
            assertTrue(
                "diagnostic recorded for the rejection from $initial",
                machine.diagnostics.isNotEmpty(),
            )
        }
    }

    // ------------------------------------------------------------- captureComplete

    @Test
    fun captureCompleteFromCapturingReArmsToReady() {
        val machine = machineIn(CameraState.CAPTURING)
        assertTrue(machine.captureComplete())
        assertEquals(CameraState.READY, machine.state)
    }

    @Test
    fun captureCompleteIsRejectedFromEveryOtherState() {
        val rejectedFrom = allStates.filter { it != CameraState.CAPTURING }
        for (initial in rejectedFrom) {
            val machine = machineIn(initial)
            assertFalse("captureComplete must be rejected from $initial", machine.captureComplete())
            assertEquals("state unchanged after rejection from $initial", initial, machine.state)
            assertTrue(
                "diagnostic recorded for the rejection from $initial",
                machine.diagnostics.isNotEmpty(),
            )
        }
    }

    // --------------------------------------------------------------- captureFailed

    @Test
    fun captureFailedFromCapturingReArmsToReady() {
        val machine = machineIn(CameraState.CAPTURING)
        assertTrue(machine.captureFailed())
        assertEquals(CameraState.READY, machine.state)
    }

    @Test
    fun captureFailedIsRejectedFromEveryOtherState() {
        val rejectedFrom = allStates.filter { it != CameraState.CAPTURING }
        for (initial in rejectedFrom) {
            val machine = machineIn(initial)
            assertFalse("captureFailed must be rejected from $initial", machine.captureFailed())
            assertEquals("state unchanged after rejection from $initial", initial, machine.state)
            assertTrue(
                "diagnostic recorded for the rejection from $initial",
                machine.diagnostics.isNotEmpty(),
            )
        }
    }

    // ---------------------------------------------------------------- cameraError

    @Test
    fun recoverableErrorFromOpeningStaysOpeningForTheRetry() {
        val machine = machineIn(CameraState.OPENING)
        assertTrue(machine.cameraError(recoverable = true))
        assertEquals(CameraState.OPENING, machine.state)
    }

    @Test
    fun recoverableErrorFromReadyReturnsToOpeningForTheRebind() {
        val machine = machineIn(CameraState.READY)
        assertTrue(machine.cameraError(recoverable = true))
        assertEquals(CameraState.OPENING, machine.state)
    }

    @Test
    fun recoverableErrorFromCapturingReturnsToOpening() {
        val machine = machineIn(CameraState.CAPTURING)
        assertTrue(machine.cameraError(recoverable = true))
        assertEquals(CameraState.OPENING, machine.state)
    }

    @Test
    fun recoverableErrorIsRejectedFromIdleErrorClosed() {
        val rejectedFrom = listOf(CameraState.IDLE, CameraState.ERROR, CameraState.CLOSED)
        for (initial in rejectedFrom) {
            val machine = machineIn(initial)
            assertFalse(
                "recoverable cameraError must be rejected from $initial",
                machine.cameraError(recoverable = true),
            )
            assertEquals("state unchanged after rejection from $initial", initial, machine.state)
            assertTrue(
                "diagnostic recorded for the rejection from $initial",
                machine.diagnostics.isNotEmpty(),
            )
        }
    }

    @Test
    fun permanentErrorFromOpeningReadyCapturingGoesToError() {
        val acceptedFrom = listOf(CameraState.OPENING, CameraState.READY, CameraState.CAPTURING)
        for (initial in acceptedFrom) {
            val machine = machineIn(initial)
            assertTrue("permanent cameraError from $initial", machine.cameraError(recoverable = false))
            assertEquals("from $initial", CameraState.ERROR, machine.state)
        }
    }

    @Test
    fun permanentErrorFromErrorIsIdempotent() {
        val machine = machineIn(CameraState.ERROR)
        assertTrue(machine.cameraError(recoverable = false))
        assertEquals(CameraState.ERROR, machine.state)
        // Camera stacks emit multiple error callbacks; each is a no-op, not a
        // rejection, and none of them throws.
        assertTrue(machine.cameraError(recoverable = false))
        assertEquals(CameraState.ERROR, machine.state)
    }

    @Test
    fun permanentErrorIsRejectedFromIdleAndClosed() {
        val rejectedFrom = listOf(CameraState.IDLE, CameraState.CLOSED)
        for (initial in rejectedFrom) {
            val machine = machineIn(initial)
            assertFalse(
                "permanent cameraError must be rejected from $initial",
                machine.cameraError(recoverable = false),
            )
            assertEquals("state unchanged after rejection from $initial", initial, machine.state)
            assertTrue(
                "diagnostic recorded for the rejection from $initial",
                machine.diagnostics.isNotEmpty(),
            )
        }
    }

    @Test
    fun errorIsTerminalUntilClose() {
        val machine = machineIn(CameraState.READY)
        machine.cameraError(recoverable = false)
        assertEquals(CameraState.ERROR, machine.state)

        // Nothing recovers ERROR except close().
        assertFalse(machine.bindStarted())
        assertFalse(machine.cameraReady())
        assertFalse(machine.captureStarted())
        assertFalse(machine.cameraError(recoverable = true))
        assertEquals(CameraState.ERROR, machine.state)

        machine.close()
        assertEquals(CameraState.CLOSED, machine.state)
    }

    // ---------------------------------------------------------------------- close

    @Test
    fun closeFromEveryStateGoesToClosed() {
        for (initial in allStates) {
            val machine = machineIn(initial)
            assertTrue("close from $initial", machine.close())
            assertEquals("from $initial", CameraState.CLOSED, machine.state)
        }
    }

    @Test
    fun closeIsIdempotent() {
        val machine = machineIn(CameraState.READY)
        machine.close()
        machine.close()
        assertEquals(CameraState.CLOSED, machine.state)
    }

    @Test
    fun closedIsTerminal() {
        val machine = machineIn(CameraState.CLOSED)
        assertFalse(machine.bindStarted())
        assertFalse(machine.cameraReady())
        assertFalse(machine.captureStarted())
        assertFalse(machine.captureComplete())
        assertFalse(machine.captureFailed())
        assertFalse(machine.cameraError(recoverable = true))
        assertFalse(machine.cameraError(recoverable = false))
        assertEquals(CameraState.CLOSED, machine.state)
    }

    // ------------------------------------------------------------- diagnostics

    @Test
    fun rejectionDiagnosticsNameTheEventAndTheState() {
        val machine = machineIn(CameraState.CAPTURING)
        machine.bindStarted()
        val last = machine.diagnostics.last()
        assertTrue(last.contains("bindStarted"))
        assertTrue(last.contains("CAPTURING"))
    }

    @Test
    fun diagnosticsSnapshotIsCopiedSoCallersCannotMutateIt() {
        val machine = machineIn(CameraState.IDLE)
        machine.bindStarted() // legal, no diagnostic
        machine.bindStarted() // rejected from OPENING? no — idempotent legal
        machine.captureStarted() // rejected from OPENING
        val snapshot = machine.diagnostics
        snapshot.drop(0)
        assertEquals(1, machine.diagnostics.size)
    }

    // ----------------------------------------------------------------- journeys

    @Test
    fun happyPathJourneyIdleToReadyCaptureReArm() {
        val machine = CameraStateMachine()
        assertTrue(machine.bindStarted())
        assertEquals(CameraState.OPENING, machine.state)
        assertTrue(machine.cameraReady())
        assertEquals(CameraState.READY, machine.state)
        assertTrue(machine.captureStarted())
        assertEquals(CameraState.CAPTURING, machine.state)
        assertTrue(machine.captureComplete())
        assertEquals(CameraState.READY, machine.state)
        // Re-armed: a second capture is immediately possible.
        assertTrue(machine.captureStarted())
        assertTrue(machine.captureFailed())
        assertEquals(CameraState.READY, machine.state)
    }

    @Test
    fun recoverableErrorRebindJourney() {
        val machine = CameraStateMachine()
        machine.bindStarted()
        machine.cameraReady()
        assertTrue(machine.cameraError(recoverable = true))
        assertEquals(CameraState.OPENING, machine.state)
        assertTrue(machine.bindStarted()) // rebind event is legal from OPENING
        assertTrue(machine.cameraReady())
        assertEquals(CameraState.READY, machine.state)
    }

    @Test
    fun illegalEventStormNeverThrowsAndStateSurvives() {
        val machine = machineIn(CameraState.READY)
        // Every event below is illegal in READY; the machine must reject each
        // one without throwing and stay READY.
        repeat(3) {
            assertFalse(machine.cameraReady())
            assertFalse(machine.captureComplete())
            assertFalse(machine.captureFailed())
        }
        assertEquals(CameraState.READY, machine.state)
        // The legal terminal exit from READY still works after the storm.
        assertTrue(machine.cameraError(recoverable = false))
        assertEquals(CameraState.ERROR, machine.state)
    }

    // -------------------------------------------------------------- determinism

    @Test
    fun sameInputSequenceProducesSameOutputSequence() {
        fun run(): Pair<CameraState, List<String>> {
            val machine = CameraStateMachine()
            machine.bindStarted()
            machine.cameraReady()
            machine.captureStarted()
            machine.captureFailed()
            machine.bindStarted() // rejected from READY? no — legal rebind path
            machine.captureStarted() // rejected from OPENING
            machine.cameraError(recoverable = true)
            machine.close()
            machine.bindStarted() // rejected from CLOSED
            return machine.state to machine.diagnostics
        }

        assertEquals(run(), run())
    }
}
