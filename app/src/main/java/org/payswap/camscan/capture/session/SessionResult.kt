package org.payswap.camscan.capture.session

import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.PageEnhancementMode
import org.payswap.camscan.processing.ImageBuffer
import org.payswap.camscan.processing.ProcessedImage
import java.io.File

/*
 * CAMSCAN-PROD-004 §6.3 — the immutable durable payload a finished session
 * emits, pure Kotlin. ScanSession.finish() freezes its pages into this shape;
 * afterwards the payload never changes (the contract's "stable final ids").
 *
 * The payload carries ImageBuffers + metadata ONLY: PNG encoding, ContentStore
 * refs, and repository writes are the android-side adapter's job
 * ([SessionPersistAdapter]) — the pure layer never touches the platform.
 */
class SessionResult(

    /** Frozen pages in final scan order (index-aligned with the document). */
    val pages: List<SessionPageResult>,
) {

    val pageCount: Int
        get() = pages.size

    /** True when at least one page carries a re-warpable source quad. */
    val hasPerspectiveMetadata: Boolean
        get() = pages.any { it.cropQuad != null }
}

/**
 * One frozen page: everything the persistence adapter needs to make the
 * durable core `Page` — processed pixels (PNG source), original capture
 * (file or buffer), normalized crop quad, rotation metadata, enhancement.
 */
class SessionPageResult(

    /** Stable final page id (equals the durable Page.id). */
    val pageId: String,

    /** Retake lineage: the retired id this page replaced, else null. */
    val supersedesId: String?,

    /** Original decoded capture (re-processing source of truth). */
    val source: ImageBuffer,

    /** Original still file when captured for real; null when injected. */
    val sourceFile: File?,

    /** Final processed (warped + enhanced) pixels. */
    val processed: ProcessedImage,

    /**
     * Crop quad normalized onto the SOURCE image space
     * (QuadF.toNormalizedCorners — the lead's `Corner` 0..1 convention),
     * or null for full-frame captures (no perspective metadata).
     */
    val cropQuad: List<Corner>?,

    /** Right-angle rotation applied on top of the processed image. */
    val rotationDegrees: Int,

    /** Enhancement applied to [processed]. */
    val enhancement: PageEnhancementMode,
)
