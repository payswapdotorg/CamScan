package org.payswap.camscan.capture.camera


/** Lens selection for the scan camera. */
enum class LensFacing { BACK, FRONT }


/**
 * Still-capture flash policy. AUTO is the default — it matches the reference
 * scanner behavior of auto-flash in dim conditions (CAMSCAN-PROD-001 §6.3).
 */
enum class FlashMode { AUTO, ON, OFF }


/**
 * CAMSCAN-PROD-001 §6.3 — immutable camera settings, pure Kotlin.
 *
 * @param lensFacing currently selected lens (BACK by default).
 * @param flash flash policy applied to still captures (AUTO by default).
 * @param available true while a usable camera is available for [lensFacing];
 *   false models the no-camera / permanent-failure surface on which the scan
 *   UI must degrade gracefully (scan_unavailable_state). Switching lenses is
 *   rejected while this is false.
 */
data class StableCameraState(
    val lensFacing: LensFacing = LensFacing.BACK,
    val flash: FlashMode = FlashMode.AUTO,
    val available: Boolean = true,
)


/** Setting events understood by [StableCameraReducer]. */
sealed interface CameraSettingEvent {

    /** Cycles the flash policy AUTO → ON → OFF → AUTO. Always applied. */
    data object ToggleFlash : CameraSettingEvent

    /**
     * Flips BACK ⇄ FRONT. Rejected (input state returned unchanged) while
     * [StableCameraState.available] is false — switching to an unavailable lens
     * is not permitted (§6.3).
     */
    data object SwitchLens : CameraSettingEvent

    /** Marks camera availability. Always applied. */
    data class SetAvailable(val available: Boolean) : CameraSettingEvent
}


/**
 * CAMSCAN-PROD-001 §6.3 — pure reducer over [StableCameraState].
 *
 * Rejected events return the input state unchanged (same instance). There is
 * no hidden state, no time and no randomness: same input → same output.
 */
object StableCameraReducer {

    fun reduce(state: StableCameraState, event: CameraSettingEvent): StableCameraState = when (event) {
        CameraSettingEvent.ToggleFlash -> state.copy(flash = nextFlash(state.flash))

        CameraSettingEvent.SwitchLens -> when {
            !state.available -> state // rejected: no usable camera to switch to
            else -> state.copy(lensFacing = flip(state.lensFacing))
        }

        is CameraSettingEvent.SetAvailable -> state.copy(available = event.available)
    }

    private fun nextFlash(flash: FlashMode): FlashMode = when (flash) {
        FlashMode.AUTO -> FlashMode.ON
        FlashMode.ON -> FlashMode.OFF
        FlashMode.OFF -> FlashMode.AUTO
    }

    private fun flip(lens: LensFacing): LensFacing = when (lens) {
        LensFacing.BACK -> LensFacing.FRONT
        LensFacing.FRONT -> LensFacing.BACK
    }
}
