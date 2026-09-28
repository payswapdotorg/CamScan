package org.payswap.camscan.capture.session

import org.payswap.camscan.capture.detect.Corner
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.processing.ImageBuffer
import org.payswap.camscan.processing.ProcessingPipeline
import org.payswap.camscan.processing.QuadF
import java.io.File

/*
 * CAMSCAN-PROD-004 §6.6 (logic half) — the UI-facing session coordinator,
 * pure Kotlin / android-free (JVM-testable: decode + persist are injected).
 *
 * Owns the capture -> page flow and the retake hand-off:
 *  - submitCapture: decodes the still file (injected decoder), maps the
 *    PROD-002 stable detection's frame-space corners onto the decoded
 *    capture (QuadF.fromFrameQuad — per-axis proportional, documented
 *    same-region assumption), mints a page id, and either RETAKES the
 *    pending index (a retake replaces the page at the same index) or
 *    APPENDS at the end. Returns the page's index for review, or null on
 *    decode/pipeline failure (flag discipline).
 *  - markRetake: arms the pending retake index (the review UI's Retake
 *    button). The OLD page stays until a new capture actually lands; if
 *    the user never captures again, the old page finishes intact.
 *  - finish: freezes the session and hands the payload to the injected
 *    persist function (the android adapter, or a fake in tests). Returns
 *    the document id — null for zero pages, or when persistence is not
 *    wired / failed (the shell then observes onScanFinished(null), the
 *    honestly preserved placeholder behavior).
 */
class ScanSessionController(

    private val pipeline: ProcessingPipeline = ProcessingPipeline(),
    private val idGenerator: () -> String,
    private val decodeCapture: (File) -> ImageBuffer? = { null },
    private val persistResult: suspend (SessionResult) -> String? = { null },
) {

    private val session = ScanSession(pipeline)

    private var pendingRetakeIndex: Int? = null

    private var finishedDocumentId: String? = null

    private var finished = false

    val pageCount: Int
        get() = session.pageCount

    val isFinished: Boolean
        get() = finished

    fun pageAt(index: Int): SessionPage? = session.pageAt(index)

    fun pages(): List<SessionPage> = session.pages()

    /** The armed retake index, or null when the next capture appends. */
    fun pendingRetake(): Int? = pendingRetakeIndex

    /**
     * Arms a retake of the page at [index] (the review Retake button).
     * Returns false after finish or for a bad index.
     */
    fun markRetake(index: Int): Boolean {
        if (finished) return false
        if (session.pageAt(index) == null) return false
        pendingRetakeIndex = index
        return true
    }

    /**
     * Processes one captured still. [detectedCorners] are the PROD-002
     * stable detection corners in FRAME pixel space (TL,TR,BR,BL) with
     * [frameWidth]/[frameHeight]; null/empty means full-frame capture.
     *
     * Returns the reviewed page's index, or null when the decode or the
     * pipeline rejected the capture.
     */
    fun submitCapture(
        file: File,
        detectedCorners: List<Corner>?,
        frameWidth: Int,
        frameHeight: Int,
    ): Int? {
        if (finished) return null
        val source = decodeCapture(file) ?: return null
        if (!source.isWellFormed) return null

        val quad = detectedCorners
            ?.takeIf { it.size == CORNER_COUNT }
            ?.let { corners ->
                QuadF.fromFrameQuad(
                    corners[0].x, corners[0].y,
                    corners[1].x, corners[1].y,
                    corners[2].x, corners[2].y,
                    corners[3].x, corners[3].y,
                    frameWidth, frameHeight,
                    source.width, source.height,
                )
            }

        val id = idGenerator()
        val page = SessionPage.fromCapture(id, source, file, quad, pipeline) ?: return null

        val retakeIndex = pendingRetakeIndex
        if (retakeIndex != null && session.retake(retakeIndex, page)) {
            pendingRetakeIndex = null
            return retakeIndex
        }
        // No armed retake (or the armed index vanished — e.g. the page was
        // removed meanwhile): fall back to an append, documented.
        pendingRetakeIndex = null
        return if (session.addPage(page)) session.pageCount - 1 else null
    }

    fun setEnhancement(index: Int, mode: PageEnhancementMode): Boolean =
        session.setEnhancement(index, mode)

    fun setRotation(index: Int, degrees: Int): Boolean =
        session.setRotation(index, degrees)

    fun adjustCrop(index: Int, adjustedQuad: QuadF): Boolean =
        session.adjustCrop(index, adjustedQuad)

    /**
     * Freezes the session and persists it exactly ONCE (repeat calls return
     * the cached document id without re-persisting — no duplicate documents).
     * Returns the document id, or null for zero pages / unwired-or-failed
     * persistence.
     */
    suspend fun finish(): String? {
        if (finished) return finishedDocumentId
        val result = session.finish()
        finished = true
        finishedDocumentId = result?.let { persistResult(it) }
        return finishedDocumentId
    }

    private companion object {
        const val CORNER_COUNT = 4
    }
}

/*
 * Token -> controller registry: the bridge fragments use to reach the same
 * session across the scan -> review -> scan loop WITHOUT parceling live
 * objects (fragment arguments only carry the token + page index).
 *
 * Main-thread confined by the fragment flow; the methods are synchronized
 * anyway so JVM suites may poke it from test threads safely. A token is
 * removed when its session ends (finish/abandon) — a lost token (process
 * death) degrades honestly: the review surface pops itself back.
 */
object ScanSessionRegistry {

    private val controllers = mutableMapOf<String, ScanSessionController>()

    @Synchronized
    fun put(token: String, controller: ScanSessionController) {
        controllers[token] = controller
    }

    @Synchronized
    fun get(token: String?): ScanSessionController? =
        token?.let { controllers[it] }

    @Synchronized
    fun remove(token: String?) {
        token?.let { controllers.remove(it) }
    }

    @Synchronized
    fun clear() {
        controllers.clear()
    }
}
