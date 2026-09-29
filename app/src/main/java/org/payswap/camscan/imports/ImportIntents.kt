package org.payswap.camscan.imports

/**
 * Pure import pick-spec seam (CAMSCAN-PROD-008 §6.1 — the PROD-007 ShareIntents
 * pattern): android-free construction of "what the user should be allowed to
 * pick" so the spec is JVM-testable; platform launchers materialize from it at
 * the UI edge ([org.payswap.camscan.document.LibraryFragment] and the viewer's
 * append-import affordance).
 *
 * Primary flow: [openDocumentPick] — the system document picker
 * (ACTION_OPEN_DOCUMENT) over images and PDFs, single pick. Fallback flow:
 * [getContentFallbackPick] — ACTION_GET_CONTENT with the wildcard mime for
 * pickers that do not implement the storage-access contract; the engine still
 * sniffs real bytes, so a wildcard pick cannot smuggle an unsupported type
 * past the caps.
 */
object ImportIntents {

    /** Storage-access pick action (spelled out, pinned to the platform value). */
    const val ACTION_OPEN_DOCUMENT: String = "android.intent.action.OPEN_DOCUMENT"

    /** Legacy broad picker action used as the documented fallback. */
    const val ACTION_GET_CONTENT: String = "android.intent.action.GET_CONTENT"

    /** Everything CamScan imports starts as an image or a PDF. */
    const val MIME_IMAGE_WILDCARD: String = "image/*"

    /** PDF import path (bounded, rendered through PdfRenderer downstream). */
    const val MIME_APPLICATION_PDF: String = "application/pdf"

    /** The exact accept list for the primary pick flow. */
    val IMPORT_MIME_TYPES: List<String> = listOf(MIME_IMAGE_WILDCARD, MIME_APPLICATION_PDF)

    /** Fallback pickers get the wildcard; [ImportEngine] sniffs real bytes anyway. */
    const val GET_CONTENT_FALLBACK_MIME: String = "*/*"

    /**
     * A pure pick specification: which [action], which [mimeTypes], and whether
     * multiple picks are allowed. CamScan imports are single-pick by design —
     * appending is an explicit repeat action, never a surprise multi-select.
     */
    data class PickSpec(
        val action: String,
        val mimeTypes: List<String>,
        val allowMultiple: Boolean,
    )

    /** The primary system-pick spec (images + PDFs, single selection). */
    fun openDocumentPick(): PickSpec =
        PickSpec(
            action = ACTION_OPEN_DOCUMENT,
            mimeTypes = IMPORT_MIME_TYPES,
            allowMultiple = false,
        )

    /** The documented fallback spec for ACTION_GET_CONTENT-only pickers. */
    fun getContentFallbackPick(): PickSpec =
        PickSpec(
            action = ACTION_GET_CONTENT,
            mimeTypes = listOf(GET_CONTENT_FALLBACK_MIME),
            allowMultiple = false,
        )
}
