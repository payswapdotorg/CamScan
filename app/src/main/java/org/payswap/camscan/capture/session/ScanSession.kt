package org.payswap.camscan.capture.session

import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.processing.ProcessingPipeline
import org.payswap.camscan.processing.QuadF

/*
 * CAMSCAN-PROD-004 §6.3 — the scan session state machine (the contract seam
 * docs/SCAN-ENGINE-CONTRACT.md: ScanSession with addPage / retake / remove /
 * reorder / finish; exact class names may vary, the seams may not).
 *
 * Pure Kotlin, deterministic, never throws: every illegal transition is
 * REJECTED by returning false/null (the PROD-001/002/003 machine
 * discipline). All heavy work re-runs the PROD-003 pipeline from the SOURCE
 * capture, so edits are non-destructive and byte-stable.
 *
 * Re-processing policy (documented per the work order):
 *  - setEnhancement: EAGER — the processed buffer is rebuilt immediately
 *    from (source, quad, mode); same inputs give byte-identical output.
 *  - setRotation: NO re-processing — rotation is render/persistence
 *    METADATA applied on top of the processed image (core Page KDoc:
 *    "Right-angle rotation applied on top of the processed image"); the
 *    processed bytes are rotation-invariant.
 *  - adjustCrop: EAGER — the new quad replaces the page quad and the
 *    pipeline re-runs from the source.
 *
 * Retake semantics (the contract's "retake/delete state" rule): a retake
 * REPLACES the page at the given index atomically; the replacement carries
 * a fresh id and records the retired id in supersedesId. Once finish()
 * succeeds, the id list is frozen — repeated finish() returns the SAME
 * snapshot object (ids stay stable after finish).
 */
class ScanSession(

    private val pipeline: ProcessingPipeline = ProcessingPipeline(),
) {

    private val pagesInternal = mutableListOf<SessionPage>()

    /** Cached finish snapshot: non-null once finish() ran on a non-empty session. */
    private var finishedResult: SessionResult? = null

    private var finished = false

    val pageCount: Int
        get() = pagesInternal.size

    val isFinished: Boolean
        get() = finished

    /** Snapshot of the live pages in current order. */
    fun pages(): List<SessionPage> = pagesInternal.toList()

    fun pageAt(index: Int): SessionPage? = pagesInternal.getOrNull(index)

    /** Appends [page] at the end. Rejected after finish or for invalid pages. */
    fun addPage(page: SessionPage): Boolean {
        if (finished || !page.isValid) return false
        return pagesInternal.add(page)
    }

    /**
     * Atomic replace at [index]: the new page supersedes the old one's id.
     * Rejected after finish, for out-of-range indices, or invalid pages.
     */
    fun retake(index: Int, newPage: SessionPage): Boolean {
        if (finished || !newPage.isValid) return false
        val old = pagesInternal.getOrNull(index) ?: return false
        pagesInternal[index] = newPage.withSupersedes(old.id)
        return true
    }

    /** Removes the page at [index]; later pages shift down. Rejected after finish. */
    fun remove(index: Int): Boolean {
        if (finished) return false
        if (index !in pagesInternal.indices) return false
        pagesInternal.removeAt(index)
        return true
    }

    /**
     * List-move semantics (documented): the page at [from] is REMOVED and
     * re-INSERTED at [to] interpreted against the post-removal list
     * (to == 0 moves to the front; to == n-1 moves to the end). [to] is
     * clamped into the valid post-removal range. Rejected after finish or
     * for an out-of-range [from].
     */
    fun reorder(from: Int, to: Int): Boolean {
        if (finished) return false
        val n = pagesInternal.size
        if (from !in 0 until n) return false
        val target = to.coerceIn(0, n - 1)
        val page = pagesInternal.removeAt(from)
        pagesInternal.add(target, page)
        return true
    }

    /**
     * Switches the page's enhancement and EAGERLY re-processes from the
     * source (deterministic). Idempotent: re-applying the current mode is a
     * no-op returning true. Rejected after finish, for bad indices, or when
     * the pipeline rejects the (source, quad, mode) input.
     */
    fun setEnhancement(index: Int, mode: PageEnhancementMode): Boolean {
        if (finished) return false
        val page = pagesInternal.getOrNull(index) ?: return false
        if (page.enhancement == mode) return true
        val reprocessed = page.reprocessed(pipeline, mode = mode) ?: return false
        pagesInternal[index] = reprocessed
        return true
    }

    /**
     * Sets right-angle rotation metadata (0/90/180/270). NO re-processing:
     * rotation applies at render/persistence time on top of the processed
     * image (see the class KDoc policy). Rejected after finish, for bad
     * indices, or non-right-angle values.
     */
    fun setRotation(index: Int, degrees: Int): Boolean {
        if (finished) return false
        if (degrees !in SessionPage.ROTATION_STEPS) return false
        val page = pagesInternal.getOrNull(index) ?: return false
        if (page.rotationDegrees == degrees) return true
        pagesInternal[index] = page.withRotation(degrees)
        return true
    }

    /**
     * Crop-adjust: replaces the page's quad with [adjustedQuad] (source-image
     * pixel space) and EAGERLY re-runs the pipeline from the source capture
     * (non-destructive per the contract). Rejected after finish, for bad
     * indices, or when the pipeline rejects the new quad.
     */
    fun adjustCrop(index: Int, adjustedQuad: QuadF): Boolean {
        if (finished) return false
        val page = pagesInternal.getOrNull(index) ?: return false
        val reprocessed = page.reprocessed(pipeline, quad = adjustedQuad) ?: return false
        pagesInternal[index] = reprocessed
        return true
    }

    /**
     * Freezes the session into its durable payload.
     *  - ZERO pages => null (abort semantics; the session is then closed).
     *  - Repeated calls return the SAME snapshot object (ids stable after
     *    finish); after the first finish every mutation is rejected.
     */
    fun finish(): SessionResult? {
        if (finished) return finishedResult
        finished = true
        if (pagesInternal.isEmpty()) {
            finishedResult = null
            return null
        }
        val result = SessionResult(pagesInternal.toList().map { it.toResult() })
        finishedResult = result
        return result
    }
}
