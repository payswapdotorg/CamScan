package org.payswap.camscan.capture.camera


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test


/**
 * CAMSCAN-PROD-001 §6.7 — exhaustive transition-table tests for the pure
 * camera-settings reducer. Pure JUnit 4: no Android classes, so it compiles
 * and runs under `./gradlew :app:testDebugUnitTest` on any JVM.
 *
 * Rejection convention (§6.3): a rejected event returns the input state
 * unchanged — the same instance — so rejections are asserted with
 * [assertSame] and applied transitions with [assertNotSame]/[assertEquals].
 */
class StableCameraStateTest {

    // ------------------------------------------------------------------ defaults

    @Test
    fun defaultsAreBackLensAutoFlashAvailable() {
        val state = StableCameraState()
        assertEquals(LensFacing.BACK, state.lensFacing)
        assertEquals(FlashMode.AUTO, state.flash)
        assertEquals(true, state.available)
    }

    // ------------------------------------------------------------------ ToggleFlash

    @Test
    fun toggleFlashCyclesAutoToOnToOffToAuto() {
        var state = StableCameraState()
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        assertEquals(FlashMode.ON, state.flash)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        assertEquals(FlashMode.OFF, state.flash)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        assertEquals(FlashMode.AUTO, state.flash)
    }

    @Test
    fun toggleFlashIsAppliedFromBothFlashLenses() {
        for (lens in LensFacing.values()) {
            var state = StableCameraState(lensFacing = lens)
            state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
            assertEquals("from $lens", FlashMode.ON, state.flash)
            assertEquals("lens preserved from $lens", lens, state.lensFacing)
        }
    }

    @Test
    fun toggleFlashIsAppliedWhileCameraIsUnavailable() {
        // Flash is sticky user intent: it applies while the camera is gone and
        // takes effect when the camera returns.
        var state = StableCameraState(available = false)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        assertEquals(FlashMode.ON, state.flash)
        assertEquals(false, state.available)
    }

    @Test
    fun toggleFlashNeverTouchesLensOrAvailability() {
        var state = StableCameraState(lensFacing = LensFacing.FRONT, available = false)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        assertEquals(LensFacing.FRONT, state.lensFacing)
        assertEquals(false, state.available)
    }

    // ------------------------------------------------------------------ SwitchLens

    @Test
    fun switchLensFlipsBackToFrontWhileAvailable() {
        val state = StableCameraState(lensFacing = LensFacing.BACK, available = true)
        val next = StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens)
        assertEquals(LensFacing.FRONT, next.lensFacing)
    }

    @Test
    fun switchLensFlipsFrontToBackWhileAvailable() {
        val state = StableCameraState(lensFacing = LensFacing.FRONT, available = true)
        val next = StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens)
        assertEquals(LensFacing.BACK, next.lensFacing)
    }

    @Test
    fun switchLensPreservesFlashAndAvailability() {
        for (flash in FlashMode.values()) {
            val state = StableCameraState(lensFacing = LensFacing.BACK, flash = flash, available = true)
            val next = StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens)
            assertEquals("flash preserved with $flash", flash, next.flash)
            assertEquals("availability preserved with $flash", true, next.available)
        }
    }

    @Test
    fun switchLensIsRejectedWhileUnavailableFromBothLenses() {
        for (lens in LensFacing.values()) {
            val state = StableCameraState(lensFacing = lens, available = false)
            val next = StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens)
            assertSame(
                "switching to an unavailable lens is rejected from $lens (same instance returned)",
                state,
                next,
            )
            assertEquals("state untouched after rejection from $lens", lens, next.lensFacing)
        }
    }

    @Test
    fun switchLensRecoversAfterAvailabilityIsRestored() {
        var state = StableCameraState(lensFacing = LensFacing.BACK, available = false)
        assertSame(state, StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens))
        state = StableCameraReducer.reduce(state, CameraSettingEvent.SetAvailable(true))
        val switched = StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens)
        assertEquals(LensFacing.FRONT, switched.lensFacing)
    }

    // ----------------------------------------------------------------- SetAvailable

    @Test
    fun setAvailableTrueToFalseAndBackIsAlwaysApplied() {
        val initial = StableCameraState(available = true)
        val down = StableCameraReducer.reduce(initial, CameraSettingEvent.SetAvailable(false))
        assertEquals(false, down.available)
        val up = StableCameraReducer.reduce(down, CameraSettingEvent.SetAvailable(true))
        assertEquals(true, up.available)
    }

    @Test
    fun setAvailableIsIdempotentForSameValues() {
        for (value in listOf(true, false)) {
            val state = StableCameraState(available = value)
            val next = StableCameraReducer.reduce(state, CameraSettingEvent.SetAvailable(value))
            assertEquals("idempotent set to $value", value, next.available)
        }
    }

    @Test
    fun setAvailableNeverTouchesLensOrFlash() {
        for (lens in LensFacing.values()) {
            for (flash in FlashMode.values()) {
                val state = StableCameraState(lensFacing = lens, flash = flash, available = true)
                val next = StableCameraReducer.reduce(state, CameraSettingEvent.SetAvailable(false))
                assertEquals("lens preserved ($lens/$flash)", lens, next.lensFacing)
                assertEquals("flash preserved ($lens/$flash)", flash, next.flash)
            }
        }
    }

    @Test
    fun setAvailableFalseThenTrueRoundTripsToEqualState() {
        val initial = StableCameraState(lensFacing = LensFacing.FRONT, flash = FlashMode.OFF, available = true)
        val next = StableCameraReducer.reduce(
            StableCameraReducer.reduce(initial, CameraSettingEvent.SetAvailable(false)),
            CameraSettingEvent.SetAvailable(true),
        )
        assertEquals(initial, next)
    }

    // ----------------------------------------------------------------- independence

    @Test
    fun flashAndLensTransitionsCommuteIndependently() {
        // flash cycle applied twice, lens flipped once — the fields never bleed.
        var state = StableCameraState(lensFacing = LensFacing.BACK, flash = FlashMode.AUTO)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
        assertEquals(LensFacing.FRONT, state.lensFacing)
        assertEquals(FlashMode.OFF, state.flash)
    }

    @Test
    fun everyAppliedTransitionProducesANewImmutableInstance() {
        val state = StableCameraState()
        assertNotSame(state, StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash))
        assertNotSame(state, StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens))
        assertNotSame(state, StableCameraReducer.reduce(state, CameraSettingEvent.SetAvailable(false)))
        // The input is never mutated by any event.
        assertEquals(StableCameraState(), state)
    }

    // ----------------------------------------------------------------- determinism

    @Test
    fun sameInputAndEventAlwaysProduceTheSameOutput() {
        for (event in listOf(
            CameraSettingEvent.ToggleFlash,
            CameraSettingEvent.SwitchLens,
            CameraSettingEvent.SetAvailable(true),
            CameraSettingEvent.SetAvailable(false),
        )) {
            val state = StableCameraState(lensFacing = LensFacing.FRONT, flash = FlashMode.ON, available = false)
            assertEquals(
                "deterministic for $event",
                StableCameraReducer.reduce(state, event),
                StableCameraReducer.reduce(state, event),
            )
        }
    }

    @Test
    fun longEventSequencesAreDeterministic() {
        fun run(): StableCameraState {
            var state = StableCameraState()
            repeat(5) {
                state = StableCameraReducer.reduce(state, CameraSettingEvent.ToggleFlash)
                state = StableCameraReducer.reduce(state, CameraSettingEvent.SwitchLens)
                state = StableCameraReducer.reduce(state, CameraSettingEvent.SetAvailable(false))
                state = StableCameraReducer.reduce(state, CameraSettingEvent.SetAvailable(true))
            }
            return state
        }

        assertEquals(run(), run())
    }
}
