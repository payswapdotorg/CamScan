package org.payswap.camscan.export

/**
 * A finished export artifact (CAMSCAN-PROD-007 §6.3): the ContentStore ref
 * holding the bytes plus everything the UI and share layer need to present
 * or forward it.
 */
data class ExportArtifact(
    /** ContentStore ref of the stored artifact bytes (opaque). */
    val ref: String,
    /** MIME type, e.g. [MIME_PDF] or [MIME_JPEG]. */
    val mime: String,
    /** Human-facing file name, sanitized for filesystem use. */
    val displayName: String,
    /** Size of the stored bytes in bytes. */
    val sizeBytes: Int,
    /** Page count represented by the artifact (1 for JPG exports). */
    val pageCount: Int,
) {
    companion object {
        const val MIME_PDF = "application/pdf"
        const val MIME_JPEG = "image/jpeg"

        // CAMSCAN-VERIFY-002 — additive MIME members of the Office
        // conversion + long-image exports (no existing member changed).
        const val MIME_PNG = "image/png"
        const val MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}

/**
 * Deterministic, Locale-free human size formatting for snackbars
 * ("<name> (<size>)"). Integer arithmetic with half-up rounding at one
 * decimal; negative inputs format as "0 B".
 */
fun formatSizeBytes(sizeBytes: Long): String {
    val bytes = if (sizeBytes < 0) 0L else sizeBytes
    if (bytes < KIB) return "$bytes B"
    val divisor = if (bytes < MIB) KIB else MIB
    val tenths = (bytes * 10 + divisor / 2) / divisor
    return "${tenths / 10}.${tenths % 10} ${if (divisor == KIB) "KB" else "MB"}"
}

private const val KIB = 1024L
private const val MIB = 1024L * 1024L
