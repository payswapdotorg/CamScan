package org.payswap.camscan.core.model


/**
 * CamScan canonical document model — lead-owned contract
 * (docs/PRODUCT-ARCHITECTURE-LOCK.md §4). Workers consume; changes require a
 * lead contract revision. All timestamps are epoch milliseconds read through
 * [org.payswap.camscan.core.time.TimeSource]; all large binaries live behind
 * ContentStore refs (opaque strings), never inline.
 */


/** Ordered corner in normalized source-image coordinates (0.0..1.0). */
data class Corner(val x: Float, val y: Float)


/** Enhancement transform applied to a page. Deterministic for fixed input+mode. */
enum class PageEnhancementMode { ORIGINAL, GRAYSCALE, BLACK_AND_WHITE, CONTRAST, SHARPEN, LOW_LIGHT }


/** How the document entered the app. */
enum class DocumentSource { SCAN, IMPORTED }


/**
 * A single scanned page. Editing is non-destructive: [sourceCaptureRef] keeps
 * the original capture available for reprocessing until explicitly deleted.
 */
data class Page(
    val id: String,
    val documentId: String,
    /** 0-based position inside the document's page order. */
    val index: Int,
    /** ContentStore ref to the original capture (non-destructive editing). */
    val sourceCaptureRef: String? = null,
    /** ContentStore ref to the current processed page image. */
    val processedImageRef: String? = null,
    /** Source-space crop quad when perspective metadata exists, else null. */
    val cropQuad: List<Corner>? = null,
    val enhancement: PageEnhancementMode = PageEnhancementMode.ORIGINAL,
    /** Right-angle rotation applied on top of the processed image. */
    val rotationDegrees: Int = 0,
    /** Optional OCR result attached to this page (indexed later by Worker 3). */
    val ocrResultId: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)


/** A durable document containing ordered pages. */
data class Document(
    val id: String,
    val title: String,
    /** Ordered page ids; pages resolve through the repository. */
    val pageIds: List<String> = emptyList(),
    val sourceType: DocumentSource = DocumentSource.SCAN,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
