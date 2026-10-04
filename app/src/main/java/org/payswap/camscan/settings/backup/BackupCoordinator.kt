package org.payswap.camscan.settings.backup

import kotlinx.coroutines.flow.first
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.IndexJsonCodec
import org.payswap.camscan.tools.backup.BackupArchiveWriter
import org.payswap.camscan.tools.backup.BackupEntry
import org.payswap.camscan.tools.backup.BackupEntryKind
import org.payswap.camscan.tools.backup.BackupManifest
import org.payswap.camscan.tools.backup.BackupPaths
import org.payswap.camscan.tools.backup.Sha256Text

// CAMSCAN-VERIFY-002 — the backup coordinator: builds a deterministic
// backup archive from the LIVE repository state through the delivered
// backup engines. Archive layout (per the engine's documented
// conventions): one subtree per document —
//   documents/<docId>/index.json          DOCUMENT_INDEX
//   documents/<docId>/pages/<pageId>.png  PAGE_IMAGE
// The per-document index member carries the SAME codec the live
// persistence uses ([IndexJsonCodec], one document + its pages), so a
// restore can rebuild the document through the public repository API.
// HONEST SCOPE (also shown in the UI): processed page images + document
// indexes only — source captures are NOT archived (non-destructive
// originals stay on the device), and OCR text is NOT archived (the app
// persists no OCR results today). Pages whose image is missing from
// the store are skipped and counted in the report. Documents are
// serialized in id order (deterministic); pages in index order.
// Determinism: the manifest timestamp comes from the injected
// TimeSource; two runs at the same stamp with the same state produce
// byte-identical archives (the writer's fixed zip timestamps).

/** Honest tally of one backup build. */
data class BackupReport(
    val documents: Int,
    val pages: Int,
    val skippedPages: Int,
    val entries: Int,
    val totalBytes: Long,
)

/** Sealed outcome of building a backup. */
sealed class BackupBuildResult {

    /** Nothing to back up (the honest empty-library answer). */
    object NoDocuments : BackupBuildResult()

    /** The archive bytes plus the honest tally. */
    class Built(val report: BackupReport, val bytes: ByteArray) : BackupBuildResult()
}

/** Builds backup archives from the live repository through the engines. */
class BackupCoordinator(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
    private val timeSource: TimeSource,
) {

    /**
     * Builds the full backup archive. Never throws: an unreadable page
     * image is skipped (counted), and an empty library yields
     * [BackupBuildResult.NoDocuments].
     */
    suspend fun build(appVersionHint: String): BackupBuildResult {
        val documents = repository.observeDocuments().first()
        if (documents.isEmpty()) {
            return BackupBuildResult.NoDocuments
        }
        val entries = ArrayList<BackupEntry>()
        val contents = HashMap<String, ByteArray>()
        var pages = 0
        var skippedPages = 0
        for (document in documents.sortedBy { it.id }) {
            val allPages = repository.getPages(document.id).sortedBy { it.index }
            val readablePages = ArrayList<Pair<Page, ByteArray>>()
            for (page in allPages) {
                val ref = page.processedImageRef
                if (ref == null) {
                    skippedPages += 1
                    continue
                }
                val bytes = contentStore.open(ref)
                if (bytes == null) {
                    skippedPages += 1
                    continue
                }
                readablePages.add(page to bytes)
            }

            val indexText = IndexJsonCodec.serialize(
                documents = listOf(document),
                pagesByDocumentId = mapOf(
                    document.id to readablePages.map { it.first },
                ),
            )
            val indexBytes = indexText.toByteArray(Charsets.UTF_8)
            val indexPath = BackupPaths.documentIndexPath(document.id)
            entries.add(
                BackupEntry(
                    pathInArchive = indexPath,
                    kind = BackupEntryKind.DOCUMENT_INDEX,
                    sizeBytes = indexBytes.size.toLong(),
                    sha256Hex = Sha256Text.sha256Hex(indexBytes),
                ),
            )
            contents[indexPath] = indexBytes

            for ((page, bytes) in readablePages) {
                val memberPath = pageMemberPath(document.id, page.id)
                entries.add(
                    BackupEntry(
                        pathInArchive = memberPath,
                        kind = BackupEntryKind.PAGE_IMAGE,
                        sizeBytes = bytes.size.toLong(),
                        sha256Hex = Sha256Text.sha256Hex(bytes),
                    ),
                )
                contents[memberPath] = bytes
                pages += 1
            }
        }
        val manifest = BackupManifest.create(
            entries = entries,
            appVersionHint = appVersionHint,
            timeSource = timeSource,
        )
        val archiveBytes = BackupArchiveWriter.buildBytes(manifest, contents)
        return BackupBuildResult.Built(
            report = BackupReport(
                documents = documents.size,
                pages = pages,
                skippedPages = skippedPages,
                entries = entries.size,
                totalBytes = archiveBytes.size.toLong(),
            ),
            bytes = archiveBytes,
        )
    }

    /** Canonical member path of one page image. */
    fun pageMemberPath(documentId: String, pageId: String): String {
        return BackupPaths.documentPrefix(documentId) + PAGES_DIR + "/" + pageId + PNG_EXTENSION
    }

    /** Default archive file name (deterministic from the timestamp). */
    fun archiveFileName(timestampMillis: Long): String {
        return "camscan-backup-" + timestampMillis + ZIP_EXTENSION
    }

    companion object {
        private const val PAGES_DIR = "pages"
        private const val PNG_EXTENSION = ".png"
        private const val ZIP_EXTENSION = ".zip"
    }
}
