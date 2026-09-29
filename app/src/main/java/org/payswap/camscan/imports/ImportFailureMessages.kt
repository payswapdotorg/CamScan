package org.payswap.camscan.imports

import android.content.Context
import org.payswap.camscan.R
import org.payswap.camscan.export.formatSizeBytes

/**
 * Honest failure taxonomy → user-facing message mapping
 * (CAMSCAN-PROD-008 §6.6): every [ImportFailure] value renders its echoed
 * numbers; no failure is paraphrased into a vague "something went wrong".
 */
object ImportFailureMessages {

    fun describe(context: Context, failure: ImportFailure): String = when (failure) {
        ImportFailure.EmptySelection ->
            context.getString(R.string.workspace_import_failed_reason_empty)

        is ImportFailure.UriUnreadable ->
            context.getString(R.string.workspace_import_failed_reason_unreadable)

        is ImportFailure.ImageUndecodable ->
            context.getString(R.string.workspace_import_failed_reason_undecodable)

        is ImportFailure.ImageTooLarge -> context.getString(
            R.string.workspace_import_failed_reason_too_large,
            failure.pixelCount,
            formatSizeBytes(failure.byteCount),
            failure.maxPixels,
            formatSizeBytes(failure.maxBytes),
        )

        is ImportFailure.PdfTooManyPages -> context.getString(
            R.string.workspace_import_failed_reason_pdf_pages,
            failure.foundPages,
            failure.maxPages,
        )

        ImportFailure.PdfUnreadable ->
            context.getString(R.string.workspace_import_failed_reason_pdf_unreadable)

        is ImportFailure.PdfPageRenderFailed -> context.getString(
            R.string.workspace_import_failed_reason_pdf_page,
            failure.pageIndex + 1,
        )
    }
}
