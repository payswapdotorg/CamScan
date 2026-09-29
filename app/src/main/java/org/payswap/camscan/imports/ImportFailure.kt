package org.payswap.camscan.imports

import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page

/**
 * Honest import failure taxonomy (CAMSCAN-PROD-008 §6.1) — sealed VALUES,
 * never exceptions: every failure echoes the numbers that violated a cap so
 * the UI can report the truth. Shared by [ImportEngine] (image imports) and
 * [org.payswap.camscan.imports.pdf.PdfImporter] (PDF imports).
 */
sealed class ImportFailure {

    /** The picker produced no selection. */
    object EmptySelection : ImportFailure()

    /** The content URI could not be opened or read. */
    data class UriUnreadable(val uri: String) : ImportFailure()

    /** The image bytes have no decodable pixel geometry. */
    data class ImageUndecodable(val uri: String) : ImportFailure()

    /**
     * The image violates the documented caps — echoes BOTH the violating
     * numbers and both caps (whichever limit tripped, all four values are
     * known at decision time: bounds decode runs before the cap checks).
     */
    data class ImageTooLarge(
        val uri: String,
        val pixelCount: Long,
        val maxPixels: Long,
        val byteCount: Long,
        val maxBytes: Long,
    ) : ImportFailure()

    /** The PDF has more pages than the import cap allows; echoes found + cap. */
    data class PdfTooManyPages(val foundPages: Int, val maxPages: Int) : ImportFailure()

    /** The PDF could not be opened (corrupt or password-protected). */
    object PdfUnreadable : ImportFailure()

    /** Rendering one PDF page failed; echoes the 0-based [pageIndex]. */
    data class PdfPageRenderFailed(val pageIndex: Int) : ImportFailure()
}

/**
 * Result of an import flow: the upserted [Document] and its ordered [Page]s
 * on success, or an honest [ImportFailure] value. A failure never leaves a
 * partial document behind (all-or-nothing, documented per flow).
 */
sealed class ImportResult {

    data class Success(val document: Document, val pages: List<Page>) : ImportResult()

    data class Failure(val failure: ImportFailure) : ImportResult()
}
