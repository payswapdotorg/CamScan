package org.payswap.camscan.imports.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Android [PdfDocumentOpener] on the platform `android.graphics.pdf.PdfRenderer`
 * (CAMSCAN-PROD-008 §6.2 — platform API 26+, NO new dependency).
 *
 * PdfRenderer opens a seekable file descriptor, so [open] spills [bytes] to a
 * private temp file under `cacheDir/pdf-import/` and deletes it on close.
 * Unopenable/corrupt input (IOException) and password-protected PDFs
 * (SecurityException) both return null — the honest [ImportFailure.PdfUnreadable]
 * mapping. Per-page render failures return null from [PdfRendererPages.renderPageJpeg]
 * — the [ImportFailure.PdfPageRenderFailed] mapping.
 *
 * RENDERED BYTES ARE PLATFORM-RENDERED: they vary across Android versions and
 * devices (see [PdfImporter]'s determinism-limits note). The pure pipeline is
 * deterministic given fixed rendered bytes; this adapter is exercised at the
 * lead's integration station only.
 */
class PdfRendererOpener(
    private val context: Context,
) : PdfDocumentOpener {

    override fun open(bytes: ByteArray): PdfDocumentPages? {
        if (bytes.isEmpty()) return null
        return try {
            val tempDir = File(context.cacheDir, TEMP_DIR)
            if (!tempDir.exists() && !tempDir.mkdirs()) return null
            val tempFile = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, tempDir)
            FileOutputStream(tempFile).use { out -> out.write(bytes) }
            val descriptor = ParcelFileDescriptor.open(
                tempFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            PdfRendererPages(PdfRenderer(descriptor), tempFile)
        } catch (expected: IOException) {
            null
        } catch (expected: SecurityException) {
            null
        }
    }

    /** Adapter: one PdfRenderer over one spilled temp file. */
    private class PdfRendererPages(
        private val renderer: PdfRenderer,
        private val tempFile: File,
    ) : PdfDocumentPages {

        override val pageCount: Int
            get() = renderer.pageCount

        override fun pageSizePoints(pageIndex: Int): PdfPageSize? = try {
            renderer.openPage(pageIndex).use { page ->
                PdfPageSize(widthPoints = page.width, heightPoints = page.height)
            }
        } catch (expected: Exception) {
            null
        }

        override fun renderPageJpeg(
            pageIndex: Int,
            widthPx: Int,
            heightPx: Int,
            quality: Int,
        ): ByteArray? {
            if (widthPx <= 0 || heightPx <= 0) return null
            return try {
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        val wrote = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        if (wrote) out.toByteArray() else null
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (expected: Exception) {
                null
            }
        }

        override fun close() {
            try {
                renderer.close()
            } finally {
                tempFile.delete()
            }
        }
    }

    private companion object {
        const val TEMP_DIR = "pdf-import"
        const val TEMP_PREFIX = "import-"
        const val TEMP_SUFFIX = ".pdf"
    }
}
