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

In-memory [DocumentRepository] backing the CAMSCAN-PROD-005 shell until

durable persistence lands in PROD-006. Starts honestly empty — no demo or

seed data anywhere.

Semantics (documented here because the contract is frozen):

[upsertDocument] stores the caller's [Document] verbatim and atomically

replaces all fields (pages included) keyed by id; the caller owns

timestamps. A blank id is rejected (returns false).

[updateTitle] stamps [Document.updatedAtMillis] from the injected

[TimeSource] and preserves [Document.createdAtMillis].

[observeDocuments] emits sorted by updatedAtMillis descending (stable).
[getPages] returns pages ordered by [Page.index] ascending.
Unknown ids behave benignly: null / false / emptyList().

Thread-safety: one lock guards the map (no suspension inside it); observers

read an immutable snapshot published through [MutableStateFlow]. Free of

android.* imports so the whole implementation runs on the JVM.
*/
class InMemoryDocumentRepository(
private val timeSource: TimeSource,
) : DocumentRepository {

private val lock = Any()
private val documentsById = LinkedHashMap<String, Document>()
private val documentsState = MutableStateFlow<List<Document>>(emptyList())

override fun observeDocuments(): Flow<List<Document>> =
documentsState
.map { list -> list.sortedByDescending { it.updatedAtMillis } }
.distinctUntilChanged()
override fun observeDocument(documentId: String): Flow<Document?> =
documentsState
.map { list -> list.firstOrNull { it.id == documentId } }
.distinctUntilChanged()
override suspend fun getDocument(documentId: String): Document? = synchronized(lock) {
documentsById[documentId]
}

override suspend fun upsertDocument(document: Document): Boolean {
if (document.id.isBlank()) return false
synchronized(lock) {
documentsById[document.id] = document
publishSnapshotLocked()
}
return true
}

override suspend fun deleteDocument(documentId: String): Boolean {
var removed = false
synchronized(lock) {
removed = documentsById.remove(documentId) != null
if (removed) publishSnapshotLocked()
}
return removed
}

override suspend fun getPages(documentId: String): List<Page> = synchronized(lock) {
documentsById[documentId]?.pages.orEmpty().sortedBy { it.index }
}

override suspend fun updateTitle(documentId: String, title: String): Boolean {
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
