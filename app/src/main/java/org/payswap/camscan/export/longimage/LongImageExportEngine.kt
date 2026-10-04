package org.payswap.camscan.export.longimage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.export.ExportArtifact
import org.payswap.camscan.export.sanitizeFileStem
import org.payswap.camscan.imports.BoundsDecoder
import org.payswap.camscan.tools.exportops.HorizontalAlignment
import org.payswap.camscan.tools.exportops.LongImagePage
import org.payswap.camscan.tools.exportops.LongImagePlanner
import org.payswap.camscan.tools.exportops.LongImageResult
import org.payswap.camscan.tools.exportops.SeamPolicy

// CAMSCAN-VERIFY-002 — the long-image FLOW engine: stored document ->
// decoded page bounds -> the delivered LongImagePlanner (the contract)
// -> the injected [StripRasterizer] seam -> a PNG artifact in the app's
// export location. Key vocabulary follows the PROD-007 discipline:
// "exports/<docId>-<ts>-<n>p-long.png". Rotation handling (documented):
// a page's right-angle rotation is folded to 0..359; 90/270 SWAP the
// decoded bounds before planning so the strip geometry matches the
// rotated shape the rasterizer draws. Alignment/seam defaults: Center
// alignment (a no-op under the planner's uniform width normalization)
// and NO separator bands — one continuous strip is what "long image"
// means here. Null is returned — never a throw — for unknown/empty
// documents, missing/unreadable/undecodable pages, planner failures
// and rasterizer failures. [TimeSource.nowMillis] is read exactly once
// per successful run, after pages load.

/** Exports a document's ordered pages as one tall PNG artifact. */
class LongImageExportEngine(
    private val contentStore: ContentStore,
    private val boundsDecoder: BoundsDecoder,
    private val rasterizer: StripRasterizer,
    private val timeSource: TimeSource,
    private val dispatcher: CoroutineDispatcher,
) {

    /** Exports the whole document as one long-image PNG artifact. */
    suspend fun export(
        repository: DocumentRepository,
        documentId: String,
    ): ExportArtifact? {
        val document = repository.getDocument(documentId) ?: return null
        val pages = repository.getPages(documentId).sortedBy { it.index }
        if (pages.isEmpty()) return null

        val pageInputs = ArrayList<LongImagePageBytes>(pages.size)
        val plannerPages = ArrayList<LongImagePage>(pages.size)
        for (page in pages) {
            val ref = page.processedImageRef ?: return null
            val bytes = contentStore.open(ref) ?: return null
            val bounds = withContext(dispatcher) { boundsDecoder.decodeBounds(bytes) }
                ?: return null
            val rotation = ((page.rotationDegrees % 360) + 360) % 360
            val swapSides = rotation == 90 || rotation == 270
            val width = if (swapSides) bounds.heightPx else bounds.widthPx
            val height = if (swapSides) bounds.widthPx else bounds.heightPx
            plannerPages.add(LongImagePage(pageId = page.id, widthPx = width, heightPx = height))
            pageInputs.add(
                LongImagePageBytes(
                    pageId = page.id,
                    bytes = bytes,
                    rotationDegrees = rotation,
                ),
            )
        }

        val plan = when (
            val result = LongImagePlanner.plan(
                pages = plannerPages,
                alignment = HorizontalAlignment.Center,
                seamPolicy = SeamPolicy.None,
            )
        ) {
            is LongImageResult.Error -> return null
            is LongImageResult.Ok -> result.plan
        }
        val pngBytes = withContext(dispatcher) { rasterizer.render(plan, pageInputs) }
            ?: return null

        val timestamp = timeSource.nowMillis()
        val key = "exports/" + documentId + "-" + timestamp + "-" +
            pages.size + "p-long.png"
        val storedRef = contentStore.put(key, pngBytes)
        return ExportArtifact(
            ref = storedRef,
            mime = ExportArtifact.MIME_PNG,
            displayName = sanitizeFileStem(document.title) + "_long.png",
            sizeBytes = pngBytes.size,
            pageCount = pages.size,
        )
    }
}
