package org.payswap.camscan.export.print

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.payswap.camscan.R

// CAMSCAN-VERIFY-002 — the Android print-framework glue. The whole job
// is pre-rendered off the main thread by [PrintSheetRenderer] (driven
// by the delivered PrintJobPlanner's plan); this adapter then serves
// the finished PDF bytes to the framework — onLayout reports the
// planned sheet count, onWrite streams the bytes into the framework's
// file descriptor. This is the standard PrintDocumentAdapter pattern
// (the androidx PrintHelper is NOT a dependency of this app and is not
// added): the adapter never re-renders, so the framework's preview and
// the printed output come from the same planned bytes.

/** Serves a pre-rendered print PDF to the Android print framework. */
class RenderedPrintDocumentAdapter(
    private val jobName: String,
    private val pdfBytes: ByteArray,
    private val sheetCount: Int,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (sheetCount < 1) {
            callback.onLayoutFailed("empty print job")
            return
        }
        val info = PrintDocumentInfo.Builder(jobName + PDF_EXTENSION)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(sheetCount)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            ByteArrayInputStream(pdfBytes).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
            if (cancellationSignal?.isCanceled == true) {
                callback.onWriteCancelled()
            } else {
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }
        } catch (expected: IOException) {
            callback.onWriteFailed(expected.message)
        }
    }

    companion object {
        private const val PDF_EXTENSION = ".pdf"
    }
}

/** Launch helper: hands a rendered job to the system print dialog. */
object PrintLauncher {

    /**
     * Opens the system print dialog for the pre-rendered job. Returns
     * false when the print service is unavailable on this device.
     */
    fun print(
        context: Context,
        jobName: String,
        pdfBytes: ByteArray,
        sheetCount: Int,
    ): Boolean {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: return false
        val adapter = RenderedPrintDocumentAdapter(
            jobName = jobName,
            pdfBytes = pdfBytes,
            sheetCount = sheetCount,
        )
        printManager.print(
            context.getString(R.string.workspace_print_job_name, jobName),
            adapter,
            PrintAttributes.Builder().build(),
        )
        return true
    }
}
