package org.payswap.camscan.capture.camera


import java.io.File


/**
 * CAMSCAN-PROD-001 §6.5 — one successful still capture.
 *
 * CAMSCAN-PROD-004 note: the scan session flow supersedes the raw-shot
 * handoff — captures now land in [org.payswap.camscan.capture.session.ScanSessionController]
 * pages and finish through the session persistence adapter. This value type
 * is retained for source compatibility within the capture tree (tests and
 * diagnostics); nothing on the live path constructs it anymore.
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
