package org.payswap.camscan.document.persistence

import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.time.TimeSource

// RepositoryToolOps (CAMSCAN-VERIFY-001): viewer-tool raster persistence.
// The tool surfaces (signature apply, annotation, watermark) bake their
// engine output into a NEW page raster; this extension is the single
// read-modify-upsert that swaps the page's processed image ref and resets
// the page's display rotation to zero (the edited raster has the rotation
// baked in, matching exactly what the tool surface previewed). Expressed as
// an extension on the FROZEN DocumentRepository contract like the
// RepositoryPageOps operations; promoting it into the contract is a lead
// decision.

/**
 * Replaces the processed raster of [pageId] with [newRasterRef]. Returns the
 * updated [Page] (rotation zeroed — the new raster carries the rotation),
 * or null when the document or page is unknown. Stamps the page and the
 * document with [timeSource]; every other page is untouched.
 */
suspend fun DocumentRepository.replacePageRaster(
    documentId: String,
    pageId: String,
    newRasterRef: String,
    timeSource: TimeSource,
): Page? {
    val document = getDocument(documentId) ?: return null
    val pages = getPages(documentId)
    val page = pages.firstOrNull { it.id == pageId } ?: return null
    if (newRasterRef.isEmpty()) return null
    val stamp = timeSource.nowMillis()
    val updatedPage = page.copy(
        processedImageRef = newRasterRef,
        rotationDegrees = 0,
        updatedAtMillis = stamp,
    )
    val updatedPages = pages.map { current ->
        if (current.id == pageId) updatedPage else current
    }
    upsertDocument(document.copy(updatedAtMillis = stamp), updatedPages)
    return updatedPage
}
