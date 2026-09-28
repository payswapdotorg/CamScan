package org.payswap.camscan.document.persistence

import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.time.TimeSource

/**

Viewer-facing page operations, expressed as extensions on the FROZEN
[DocumentRepository] interface — each is a read-modify-upsert composite, so
one call equals one atomic index rewrite on the persistent implementation
and works identically over the in-memory implementation. No contract file
was changed; promoting these into the contract is a lead decision
(CAMSCAN-PROD-006 report, open questions).
All operations re-index remaining/ordered pages 0..n-1 and stamp
[Document.updatedAtMillis] via the passed [TimeSource].
*/

/** Removes [pageId]; returns the updated [Document] or null when absent/invalid. */
suspend fun DocumentRepository.removePage(
documentId: String,
pageId: String,
timeSource: TimeSource,
): Document? {
val document = getDocument(documentId) ?: return null
val pages = getPages(documentId)
if (pages.none { it.id == pageId }) return null
val remaining = pages
.filter { it.id != pageId }
.mapIndexed { index, page -> page.copy(index = index) }
val updated = document.copy(pageIds = remaining.map { it.id }, updatedAtMillis = timeSource.nowMillis())
upsertDocument(updated, remaining)
return updated
}

/**

Reorders pages to exactly [orderedPageIds]. Returns the updated [Document],
or null when the document is missing, an id is unknown, the size mismatches,
or [orderedPageIds] contains duplicates.
*/
suspend fun DocumentRepository.reorderPages(
documentId: String,
orderedPageIds: List<String>,
timeSource: TimeSource,
): Document? {
val document = getDocument(documentId) ?: return null
val pages = getPages(documentId)
val currentIds = pages.map { it.id }
if (orderedPageIds.size != currentIds.size) return null
if (orderedPageIds.toSet() != currentIds.toSet()) return null
val byId = pages.associateBy { it.id }
val reordered = orderedPageIds.mapIndexed { index, id -> byId.getValue(id).copy(index = index) }
val updated = document.copy(pageIds = reordered.map { it.id }, updatedAtMillis = timeSource.nowMillis())
upsertDocument(updated, reordered)
return updated
}

/** Moves [pageId] by [offset] positions (negative = earlier); null when blocked. */
suspend fun DocumentRepository.movePage(
documentId: String,
pageId: String,
offset: Int,
timeSource: TimeSource,
): Document? {
if (offset == 0) return null
val document = getDocument(documentId) ?: return null
val ids = getPages(documentId).sortedBy { it.index }.map { it.id }
val from = ids.indexOf(pageId)
if (from < 0) return null
val to = from + offset
if (to < 0 || to >= ids.size) return null
val mutable = ids.toMutableList()
val moved = mutable.removeAt(from)
mutable.add(to, moved)
return reorderPages(documentId, mutable, timeSource)
}

/** Stamps [documentId] with the new [title]; false when absent or title blank. */
suspend fun DocumentRepository.updateTitle(
documentId: String,
title: String,
timeSource: TimeSource,
): Boolean {
if (title.isBlank()) return false
val current = getDocument(documentId) ?: return false
upsertDocument(current.copy(title = title, updatedAtMillis = timeSource.nowMillis()), getPages(documentId))
return true
}
