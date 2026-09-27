package org.payswap.camscan.capture.camera


import java.io.File


/**
 * CAMSCAN-PROD-001 §6.5 — one successful still capture kept in memory by the
 * scan surface.
 *
 * Persistence into the durable Document model is NOT this work order's
 * concern (the scan-session/persistence work order owns it); the shell and
 * session flow consume these values through [ScanFragment.onCaptureResult].
 *
 * @param file the saved still image.
 * @param capturedAtMillis completion time in epoch millis, read through the
 *   [org.payswap.camscan.core.time.TimeSource] seam (never System directly).
 * @param lensFacing lens the shot was taken with.
 * @param flash flash policy in effect for the shot.
 */
data class CapturedShot(
    val file: File,
    val capturedAtMillis: Long,
    val lensFacing: LensFacing,
    val flash: FlashMode,
)
