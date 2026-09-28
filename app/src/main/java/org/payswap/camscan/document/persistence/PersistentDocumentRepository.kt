package org.payswap.camscan.document.persistence

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.time.TimeSource

/**

Durable [DocumentRepository] (CAMSCAN-PROD-006): a canonical JSON index in

[contentDir] (index.json, previous good copy kept as index.json.bak)

plus binary page assets stored via [FileContentStore] refs recorded in the

index. Free of android.* imports — JVM-testable with a temp dir.

Write path (crash-safe order, every mutation):

mutate the in-memory snapshot,
rotate the previous GOOD primary onto the .bak (never over a corrupt
primary — a corrupt primary must not clobber a good backup),
atomically rewrite the primary (tmp + rename),
orphan sweep: delete only .bin files unreferenced by the CURRENT
index (plus stale `*.tmp`). A crash at any point can never lose live
refs: before step 3 the old index stands; after step 3 the index
already references the new state.

Read path: missing index → healthy empty start; corrupt primary → restore

the .bak ([IndexHealth.RESTORED_FROM_BACKUP], primary immediately

re-written from the restored snapshot); no recovery possible →

[IndexHealth.RECOVERED_EMPTY]. Never throws to the UI.
*/
class PersistentDocumentRepository(
val contentDir: File,
private val timeSource: TimeSource,
private val idGenerator: IdGenerator,
private val contentStore: FileContentStore = FileContentStore(contentDir),
) : DocumentRepository {

enum class IndexHealth { HEALTHY, RESTORED_FROM_BACKUP, RECOVERED_EMPTY }

val indexHealth: IndexHealth
get() = synchronized(lock) { health }
private val lock = Any()
private val documentsById = LinkedHashMap<String, Document>()
private val pagesByDocumentId = LinkedHashMap<String, List<Page>>()
private val documentsState = MutableStateFlow<List<Document>>(emptyList())
private var health: IndexHealth = IndexHealth.HEALTHY
private var primaryWasHealthyAtLoad: Boolean = false

init {
synchronized(lock) {
loadLocked()
health = if (documentsById.isEmpty() && !primaryWasHealthyAtLoad) {
IndexHealth.RECOVERED_EMPTY
} else {
health
}
}
}

// ------------------------------------------------------------- contract

override fun observeDocuments(): Flow<List<Document>> =
documentsState
.map { list -> list.sortedByDescending { it.updatedAtMillis } }
.distinctUntilChanged()

override suspend fun getDocument(documentId: String): Document? = synchronized(lock) {
documentsById[documentId]
}

override suspend fun upsertDocument(document: Document, pages: List<Page>) {
val effective = if (document.id.isBlank()) {
document.copy(id = idGenerator.newId())
} else {
document
}
val normalizedPages = pages.mapIndexed { index, page ->
if (page.id.isBlank()) page.copy(id = idGenerator.newId(), index = index)
else page
}
synchronized(lock) {
documentsById[effective.id] = effective
pagesByDocumentId[effective.id] = normalizedPages
publishSnapshotLocked()
persistLocked()
}
}

override suspend fun deleteDocument(documentId: String) {
var removed = false
synchronized(lock) {
removed = documentsById.remove(documentId) != null
pagesByDocumentId.remove(documentId)
if (removed) {
publishSnapshotLocked()
persistLocked()
}
}
}

override suspend fun getPages(documentId: String): List<Page> = synchronized(lock) {
pagesByDocumentId[documentId].orEmpty().sortedBy { it.index }
}

// ------------------------------------------------------------ internals

/** Diagnostic hook: re-serializes the current state unchanged (byte-stability pin). */
fun rewriteIndexNow(): Boolean = synchronized(lock) {
persistLocked()
true
}

private fun loadLocked() {
val primary = File(contentDir, INDEX_FILE)
val backup = File(contentDir, BACKUP_FILE)
if (!primary.isFile) {
// Missing index (fresh install or wiped dir): honest empty start.
primaryWasHealthyAtLoad = true
publishSnapshotLocked()
return
}
val parsed = try {
IndexJsonCodec.deserialize(primary.readText())
} catch (e: Exception) {
null
}
if (parsed != null) {
primaryWasHealthyAtLoad = true
replaceStateLocked(parsed)
return
}
primaryWasHealthyAtLoad = false
val restored = try {
if (backup.isFile) IndexJsonCodec.deserialize(backup.readText()) else null
} catch (e: Exception) {
null
}
if (restored != null) {
health = IndexHealth.RESTORED_FROM_BACKUP
replaceStateLocked(restored)
// Re-write the primary from the restored snapshot WITHOUT rotating
// the (still good) backup.
writeIndexAtomicLocked()
} else {
// Both unreadable: start empty, never throw to the UI. The next
// successful write rebuilds a healthy primary + backup.
documentsById.clear()
pagesByDocumentId.clear()
publishSnapshotLocked()
}
}

private fun replaceStateLocked(snapshot: IndexJsonCodec.IndexSnapshot) {
documentsById.clear()
snapshot.documents.forEach { documentsById[it.id] = it }
pagesByDocumentId.clear()
pagesByDocumentId.putAll(snapshot.pagesByDocumentId)
publishSnapshotLocked()
}

private fun publishSnapshotLocked() {
documentsState.value = documentsById.values.toList()
}

private fun persistLocked() {
val primary = File(contentDir, INDEX_FILE)
val backup = File(contentDir, BACKUP_FILE)
if (!primary.isFile || health == IndexHealth.HEALTHY || primaryWasHealthyAtLoad) {
if (primary.isFile) {
primary.copyTo(backup, overwrite = true)
}
}
writeIndexAtomicLocked()
sweepOrphansLocked()
if (health == IndexHealth.RESTORED_FROM_BACKUP || health == IndexHealth.RECOVERED_EMPTY) {
// Primary successfully re-written from the current snapshot: the
// repository is healthy again and the backup is rebuilt from it.
primary.copyTo(backup, overwrite = true)
health = IndexHealth.HEALTHY
}
}

private fun writeIndexAtomicLocked() {
if (!contentDir.exists() && !contentDir.mkdirs()) {
throw java.io.IOException("content dir not creatable: $contentDir")

}
val primary = File(contentDir, INDEX_FILE)
val tmp = File(contentDir, "$INDEX_FILE.${java.util.UUID.randomUUID()}.tmp")

try {
tmp.writeText(IndexJsonCodec.serialize(documentsById.values.toList(), pagesByDocumentId))
if (!tmp.renameTo(primary)) {
tmp.copyTo(primary, overwrite = true)
tmp.delete()
}
} finally {
if (tmp.exists()) tmp.delete()
}
primaryWasHealthyAtLoad = true
}

/**

Deletes only .bin files unreferenced by the CURRENT in-memory index
(the index was already re-written above), plus stale tmp files. The
index, its backup, and any non-blob file are never touched.
*/
private fun sweepOrphansLocked() {
val liveRefs = HashSet<String>()
pagesByDocumentId.values.forEach { pages ->
pages.forEach { page ->
page.processedImageRef?.let(liveRefs::add)
page.sourceCaptureRef?.let(liveRefs::add)
}
}
contentDir.listFiles()?.forEach { file ->
val name = file.name
when {
name == INDEX_FILE || name == BACKUP_FILE -> Unit
name.endsWith(".tmp") -> file.delete()
name.endsWith(".bin") && name !in liveRefs -> file.delete()
else -> Unit
}
}
}

companion object {
const val INDEX_FILE = "index.json"
const val BACKUP_FILE = "index.json.bak"
}

}
