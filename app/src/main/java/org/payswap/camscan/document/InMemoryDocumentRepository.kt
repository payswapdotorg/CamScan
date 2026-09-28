package org.payswap.camscan.document

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.time.TimeSource

/**

In-memory [DocumentRepository] (CAMSCAN-PROD-005; retained for tests and

shell history — the live shell wires the durable implementation).

Aligned to the FROZEN contract: [Document] carries no pages, so pages are

stored per document id; [upsertDocument] takes BOTH the document and its

pages and returns Unit (atomic full replace of both); [deleteDocument]

returns Unit and removes both. Documents are stored verbatim — a blank

document id is ignored (the Unit contract cannot signal rejection; the

durable implementation mints ids via IdGenerator). [observeDocuments]

emits sorted by updatedAtMillis descending (stable); [getPages] returns

pages ordered by index ascending.

[renameDocument] is an implementation-level helper OUTSIDE the frozen

contract (the contract defines no title mutation) providing the stamped

rename the viewer flows need; deterministic under a fixed TimeSource.

Thread-safety: one lock guards both maps (no suspension inside it); free

of android.* imports.
*/
class InMemoryDocumentRepository(
private val timeSource: TimeSource,
) : DocumentRepository {

private val lock = Any()
private val documentsById = LinkedHashMap<String, Document>()
private val pagesById = HashMap<String, List<Page>>()
private val documentsState = MutableStateFlow<List<Document>>(emptyList())

override fun observeDocuments(): Flow<List<Document>> =
documentsState
.map { list -> list.sortedByDescending { it.updatedAtMillis } }
.distinctUntilChanged()
override suspend fun getDocument(documentId: String): Document? = synchronized(lock) {
documentsById[documentId]
}

override suspend fun getPages(documentId: String): List<Page> = synchronized(lock) {
pagesById[documentId].orEmpty().sortedBy { it.index }
}

override suspend fun upsertDocument(document: Document, pages: List<Page>) {
if (document.id.isBlank()) return
synchronized(lock) {
documentsById[document.id] = document
pagesById[document.id] = pages.toList()
publishSnapshotLocked()
}
}

override suspend fun deleteDocument(documentId: String) {
synchronized(lock) {
val removedDocument = documentsById.remove(documentId) != null
val removedPages = pagesById.remove(documentId) != null
if (removedDocument || removedPages) {
publishSnapshotLocked()
}
}
}

/** Non-contract helper: stamped rename; false when absent or title blank. */
fun renameDocument(documentId: String, title: String): Boolean {
if (title.isBlank()) return false
synchronized(lock) {
val current = documentsById[documentId] ?: return false
documentsById[documentId] =
current.copy(title = title, updatedAtMillis = timeSource.nowMillis())
publishSnapshotLocked()
}
return true
}

private fun publishSnapshotLocked() {
documentsState.value = documentsById.values.toList()
}

}
