package org.payswap.camscan.capture.session

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.payswap.camscan.processing.BitmapImageAdapter
import org.payswap.camscan.processing.ImageBuffer
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * CAMSCAN-PROD-004 §6.4 (android half) — persists a finished session through
 * the core contracts, the ONLY session file with android.* graphics imports.
 *
 * Flow (all suspend, dispatched on the injected [dispatcher]; the caller owns
 * the lifecycle — the scan surface launches this from its coroutine scope):
 *  1. PNG-encode each page's processed ImageBuffer
 *     (Bitmap.compress(PNG, 100) — PNG is lossless and the quality argument
 *     is ignored; the fixed setting keeps the call deterministic in shape.
 *     Byte-stability holds per platform, as the contract allows);
 *  2. store the original capture: the still file's bytes VERBATIM when it
 *     exists (smallest + exactly what the camera produced), else a PNG of
 *     the decoded source (injected/synthetic pages);
 *  3. contentStore.put("pages/<pageId>.png") / ("sources/<pageId>.jpg|.png")
 *     -> refs (opaque strings per the core contract);
 *  4. assemble the core Document + Pages through the pure
 *     [SessionDocumentBuilder] (title from TimeSource via the INJECTED
 *     zone, never the device default);
 *  5. repository.upsertDocument(document, pages) -> document id.
 *
 * Honest degradation, never a crash: UNAVAILABLE persistence (checked via
 * [SessionPersistence.isAvailable]) or any failure returns null — refs
 * already written are swept best-effort (delete) so a failed persist does
 * not strand binaries. A null result surfaces to the user as
 * onScanFinished(null), the documented placeholder behavior.
 */
class SessionPersistAdapter(
    private val persistence: SessionPersistence,
    zone: ZoneId = ZoneOffset.UTC,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val builder = SessionDocumentBuilder(zone)

    suspend fun persist(result: SessionResult): String? {
        if (!persistence.isAvailable) return null
        return withContext(dispatcher) {
            try {
                persistInternal(result)
            } catch (t: Throwable) {
                // Includes coroutine cancellation racing a back-out; the
                // session flow treats null as "not persisted" and the
                // abandonment path reports onScanFinished(null).
                Log.w(TAG, "session persistence failed", t)
                null
            }
        }
    }

    private suspend fun persistInternal(result: SessionResult): String? {
        val documentId = persistence.idGenerator()
        val nowMillis = persistence.timeSource.nowMillis()

        val writtenRefs = mutableListOf<String>()
        try {
            val processedRefs = ArrayList<String?>(result.pageCount)
            val sourceRefs = ArrayList<String?>(result.pageCount)

            for (page in result.pages) {
                val processedBytes = encodePng(page.processed.buffer) ?: return null
                val processedRef = persistence.contentStore.put(builder.processedKey(page.pageId), processedBytes)
                writtenRefs.add(processedRef)
                processedRefs.add(processedRef)

                val sourceRef = storeSourceCapture(page)
                if (sourceRef != null) {
                    writtenRefs.add(sourceRef)
                }
                sourceRefs.add(sourceRef)
            }

            val (document, pages) = builder.build(
                result = result,
                documentId = documentId,
                processedRefs = processedRefs,
                sourceRefs = sourceRefs,
                nowMillis = nowMillis,
            ) ?: return null

            persistence.repository.upsertDocument(document, pages)
            return documentId
        } catch (t: Throwable) {
            sweepRefs(writtenRefs)
            throw t
        }
    }

    /**
     * Stores the original capture: the still file's bytes verbatim (exact
     * camera output, smallest footprint) when available, else a PNG of the
     * decoded source buffer. Null when neither is storable.
     */
    private suspend fun storeSourceCapture(page: SessionPageResult): String? {
        val file = page.sourceFile
        if (file != null && file.isFile) {
            val bytes = file.readBytes()
            return persistence.contentStore.put(builder.sourceJpegKey(page.pageId), bytes)
        }
        val bytes = encodePng(page.source) ?: return null
        return persistence.contentStore.put(builder.sourcePngKey(page.pageId), bytes)
    }

    /** PNG-encodes a well-formed buffer; null for malformed input, never throws. */
    private fun encodePng(buffer: ImageBuffer): ByteArray? {
        if (!buffer.isWellFormed) return null
        return try {
            val bitmap = BitmapImageAdapter.toBitmap(buffer)
            val out = ByteArrayOutputStream()
            val wrote = bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            bitmap.recycle()
            if (wrote) out.toByteArray() else null
        } catch (t: Throwable) {
            Log.w(TAG, "PNG encode failed for ${buffer.width}x${buffer.height} page", t)
            null
        }
    }

    /** Best-effort sweep of refs written before a failure (never throws). */
    private suspend fun sweepRefs(refs: List<String>) {
        for (ref in refs) {
            try {
                persistence.contentStore.delete(ref)
            } catch (t: Throwable) {
                Log.w(TAG, "failed sweeping ref during aborted persist", t)
            }
        }
    }

    companion object {
        private const val TAG = "SessionPersistAdapter"

        /** Fixed, documented: PNG is lossless; the argument is ignored. */
        private const val PNG_QUALITY = 100
    }
}
