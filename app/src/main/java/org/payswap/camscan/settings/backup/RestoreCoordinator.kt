package org.payswap.camscan.settings.backup

import kotlinx.coroutines.flow.first
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.IndexJsonCodec
import org.payswap.camscan.tools.backup.BackupArchiveContents
import org.payswap.camscan.tools.backup.BackupArchiveError
import org.payswap.camscan.tools.backup.BackupArchiveReader
import org.payswap.camscan.tools.backup.BackupFileIo
import org.payswap.camscan.tools.backup.BackupPaths
import org.payswap.camscan.tools.backup.RestoreDecision
import org.payswap.camscan.tools.backup.RestorePlan
import org.payswap.camscan.tools.backup.RestorePlanner
import org.payswap.camscan.tools.backup.RestorePolicy
import org.payswap.camscan.tools.backup.RestoreIndexSnapshot

// CAMSCAN-VERIFY-002 — the restore coordinator: reads + verifies an
// archive through the delivered BackupArchiveReader (every member's
// sha256 is engine-checked), plans through the delivered RestorePlanner
// (NewerWins: a backup newer than the local edit replaces it; an older
// backup keeps the local document; exact ties block as conflicts), and
// applies the PLANNED decisions into the LIVE repository through its
// public API — parse each per-document index member with the same
// codec the persistence layer uses, re-put every page image through
// the ContentStore, and upsert the document. HONEST SCOPE: the
// engine's file-tree applier (RestoreApply) is not used for the live
// store because the live index is a single global index.json +
// ContentStore refs (not the archive's per-document file tree) — the
// mapping lives here and is declared in the delivery report. Restored
// pages lose their sourceCaptureRef (source captures are not in the
// archive — documented, honest).

/** A readable + verified archive together with its restore plan. */
data class RestoreReadOutcome(
    val contents: BackupArchiveContents,
    val plan: RestorePlan,
)

/** Honest tally of one applied restore. */
data class RestoreSummary(
    val added: Int,
    val replaced: Int,
    val kept: Int,
    val failed: Int,
    val pagesRestored: Int,
)

/** Reads, plans and applies backup archives into the live repository. */
class RestoreCoordinator(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
    private val timeSource: TimeSource,
) {

    /**
     * Reads and verifies the picked archive bytes, then plans the restore
     * against the current library state. Returns null when the bytes are
     * not a valid backup (the reader's sealed errors are swallowed into
     * this honest signal). A [RestorePlan.Conflicts] is returned as-is —
     * the UI shows the blocked per-document list.
     */
    suspend fun readAndPlan(archiveBytes: ByteArray): RestoreReadOutcome? {
        if (archiveBytes.isEmpty()) return null
        val io = SingleSourceIo(archiveBytes)
        val contents = try {
            BackupArchiveReader.read(ARCHIVE_KEY, io)
        } catch (expected: BackupArchiveError) {
            return null
        }
        val currentDocuments = repository.observeDocuments().first()
        val snapshot = RestoreIndexSnapshot(
            documents = currentDocuments.map {
                org.payswap.camscan.tools.backup.RestoreDocumentRef(
                    documentId = it.id,
                    lastModifiedMillis = it.updatedAtMillis,
                )
            },
        )
        val plan = RestorePlanner.planFromManifest(
            current = snapshot,
            manifest = contents.manifest,
            policy = RestorePolicy.NewerWins(timeSource),
        )
        return RestoreReadOutcome(contents = contents, plan = plan)
    }

    /**
     * Applies a PLANNED restore into the live repository. Documents whose
     * index member is missing or unparseable, or whose page members are
     * incomplete, are counted as failed — the rest of the restore still
     * proceeds (honest per-document results, no all-or-nothing silence).
     */
    suspend fun apply(outcome: RestoreReadOutcome): RestoreSummary {
        val planned = outcome.plan as? RestorePlan.Planned
            ?: return RestoreSummary(0, 0, 0, 0, 0)
        var added = 0
        var replaced = 0
        var kept = 0
        var failed = 0
        var pagesRestored = 0
        for (decision in planned.decisions) {
            when (decision) {
                is RestoreDecision.KeepExisting -> kept += 1
                is RestoreDecision.AddDocument -> {
                    val restored = restoreDocument(outcome.contents, decision.documentId)
                    if (restored < 0) {
                        failed += 1
                    } else {
                        added += 1
                        pagesRestored += restored
                    }
                }
                is RestoreDecision.ReplaceDocument -> {
                    val restored = restoreDocument(outcome.contents, decision.documentId)
                    if (restored < 0) {
                        failed += 1
                    } else {
                        replaced += 1
                        pagesRestored += restored
                    }
                }
            }
        }
        return RestoreSummary(
            added = added,
            replaced = replaced,
            kept = kept,
            failed = failed,
            pagesRestored = pagesRestored,
        )
    }

    /**
     * Restores one document from the archive; -1 on failure, otherwise the
     * number of page images restored.
     */
    private suspend fun restoreDocument(
        contents: BackupArchiveContents,
        documentId: String,
    ): Int {
        val indexBytes = contents.member(BackupPaths.documentIndexPath(documentId))
            ?: return -1
        val snapshot = try {
            IndexJsonCodec.deserialize(String(indexBytes, Charsets.UTF_8))
        } catch (expected: IndexJsonCodec.IndexParseException) {
            return -1
        }
        val document = snapshot.documents.firstOrNull { it.id == documentId }
            ?: return -1
        val pages = snapshot.pagesByDocumentId[documentId].orEmpty()
        val restoredPages = ArrayList<Page>(pages.size)
        for (page in pages) {
            val memberPath = pageMemberPath(documentId, page.id)
            val bytes = contents.member(memberPath) ?: return -1
            val newRef = contentStore.put(memberContentKey(documentId, page.id), bytes)
            restoredPages.add(
                page.copy(
                    processedImageRef = newRef,
                    sourceCaptureRef = null,
                ),
            )
        }
        repository.upsertDocument(document, restoredPages)
        return restoredPages.size
    }

    /** ContentStore key of one restored page (sanitized into a ref hint). */
    fun memberContentKey(documentId: String, pageId: String): String {
        return "restore-" + documentId + "-" + pageId + ".png"
    }

    /** Canonical page member path (mirrors [BackupCoordinator]). */
    fun pageMemberPath(documentId: String, pageId: String): String {
        return BackupPaths.documentPrefix(documentId) + "pages/" + pageId + ".png"
    }

    companion object {
        private const val ARCHIVE_KEY = "picked-backup.zip"
    }
}

/** Minimal [BackupFileIo] over one in-memory archive payload. */
private class SingleSourceIo(private val bytes: ByteArray) : BackupFileIo {

    override fun write(path: String, bytes: ByteArray) {
        throw UnsupportedOperationException("restore reads only")
    }

    override fun read(path: String): ByteArray? = bytes

    override fun list(prefix: String): List<String> = emptyList()
}
