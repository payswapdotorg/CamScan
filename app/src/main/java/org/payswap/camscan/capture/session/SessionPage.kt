package org.payswap.camscan.capture.session

import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.processing.ImageBuffer
import org.payswap.camscan.processing.ProcessedImage
import org.payswap.camscan.processing.ProcessingPipeline
import org.payswap.camscan.processing.QuadF
import java.io.File

/*
 * CAMSCAN-PROD-004 §6.2 — one in-memory page under review, pure Kotlin.
 *
 * A page keeps EVERYTHING needed for non-destructive re-editing
 * (docs/SCAN-ENGINE-CONTRACT.md: "Keep the original capture for
 * non-destructive editing/reprocessing"):
 *  - [source]      the decoded capture (packed ARGB);
 *  - [sourceFile]  the original still file when the page came from a real
 *                  capture (null for injected/synthetic pages) — retained so
 *                  the persistence adapter can store the ORIGINAL capture
 *                  bytes verbatim;
 *  - [sourceQuad]  the quad that drove the warp (source-image pixel space),
 *                  null = full-frame capture (no warp);
 *  - [processed]   the current warp + enhancement result (PROD-003 pipeline).
 *
 * State changes go through [ScanSession], which re-runs the pipeline from
 * the SOURCE (never from the processed buffer) — deterministic and
 * non-destructive by construction.
 *
 * Retake lineage: [supersedesId] records the page id this page REPLACED
 * (null for a fresh append). Ids are minted once at capture time and stay
 * stable after finish; a retake swaps in a NEW id and retires the old one
 * (superseded ids never reach the durable payload).
 */
class SessionPage(

    /** Stable page id (minted once; kept verbatim across re-processing). */
    val id: String,

    /** The original decoded capture (re-processing source of truth). */
    val source: ImageBuffer,

    /** Original still file when captured for real; null when injected. */
    val sourceFile: File?,

    /** Quad driving the warp in source pixel space; null = full frame. */
    val sourceQuad: QuadF?,

    /** Current processed (warped + enhanced) result. */
    val processed: ProcessedImage,

    /** Enhancement currently applied to [processed]. */
    val enhancement: PageEnhancementMode,

    /** Right-angle rotation metadata on top of [processed] (0/90/180/270). */
    val rotationDegrees: Int,

    /** Retake lineage: id of the page this one replaced, else null. */
    val supersedesId: String? = null,
) {

    /**
     * Structural validity (PROD-002/003 flag discipline): blank ids, malformed
     * buffers, or non-right-angle rotations make a page un-shippable; the
     * session rejects such pages instead of throwing.
     */
    val isValid: Boolean
        get() = id.isNotBlank() &&
            source.isWellFormed &&
            processed.buffer.isWellFormed &&
            rotationDegrees in ROTATION_STEPS

    /**
     * Builds a re-processed copy from the SOURCE capture — used by
     * setEnhancement (new mode) and adjustCrop (new quad). Deterministic:
     * same (source, quad, mode) through the same pipeline gives byte-identical
     * results. Returns null when the pipeline rejects the input (degenerate
     * quad / malformed source).
     */
    internal fun reprocessed(
        pipeline: ProcessingPipeline,
        mode: PageEnhancementMode = enhancement,
        quad: QuadF? = sourceQuad,
    ): SessionPage? {
        val processedNext = pipeline.processCapture(source, quad, mode) ?: return null
        return SessionPage(
            id = id,
            source = source,
            sourceFile = sourceFile,
            sourceQuad = quad,
            processed = processedNext,
            enhancement = mode,
            rotationDegrees = rotationDegrees,
            supersedesId = supersedesId,
        )
    }

    /** Copy with a new rotation — METADATA ONLY (see ScanSession.setRotation). */
    internal fun withRotation(degrees: Int): SessionPage = SessionPage(
        id = id,
        source = source,
        sourceFile = sourceFile,
        sourceQuad = sourceQuad,
        processed = processed,
        enhancement = enhancement,
        rotationDegrees = degrees,
        supersedesId = supersedesId,
    )

    /** Copy recording the page this one superseded (retake lineage). */
    internal fun withSupersedes(supersededId: String): SessionPage = SessionPage(
        id = id,
        source = source,
        sourceFile = sourceFile,
        sourceQuad = sourceQuad,
        processed = processed,
        enhancement = enhancement,
        rotationDegrees = rotationDegrees,
        supersedesId = supersededId,
    )

    /** Freezes this page into its durable payload slice (see [SessionResult]). */
    internal fun toResult(): SessionPageResult = SessionPageResult(
        pageId = id,
        supersedesId = supersedesId,
        source = source,
        sourceFile = sourceFile,
        processed = processed,
        cropQuad = sourceQuad?.toNormalizedCorners(source.width, source.height),
        rotationDegrees = rotationDegrees,
        enhancement = enhancement,
    )

    companion object {

        /** Right-angle rotations only (the contract's rotate vocabulary). */
        val ROTATION_STEPS = listOf(0, 90, 180, 270)

        /**
         * Builds a page from a fresh capture: runs the PROD-003 pipeline
         * (warp + ORIGINAL enhancement) on [source]. Returns null when the
         * pipeline rejects the input (malformed source / degenerate quad).
         */
        fun fromCapture(
            id: String,
            source: ImageBuffer,
            sourceFile: File?,
            quad: QuadF?,
            pipeline: ProcessingPipeline,
            mode: PageEnhancementMode = PageEnhancementMode.ORIGINAL,
            supersedesId: String? = null,
            rotationDegrees: Int = 0,
        ): SessionPage? {
            if (rotationDegrees !in ROTATION_STEPS) return null
            val processed = pipeline.processCapture(source, quad, mode) ?: return null
            return SessionPage(
                id = id,
                source = source,
                sourceFile = sourceFile,
                sourceQuad = quad,
                processed = processed,
                enhancement = mode,
                rotationDegrees = rotationDegrees,
                supersedesId = supersedesId,
            )
        }
    }
}
