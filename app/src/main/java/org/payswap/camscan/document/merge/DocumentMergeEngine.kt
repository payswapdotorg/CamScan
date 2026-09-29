package org.payswap.camscan.document.merge

import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.DocumentSource
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.IdGenerator

/** Whether a merge consumes its sources or retains them. */
enum class MergeMode {
    /**
     * DEFAULT: the source documents are DELETED after the merge; their
     * ContentStore refs are PRESERVED (not byte-copied) — the merged
     * document takes ownership. Executor order: upsert the merged document
     * FIRST, then delete the sources (load-bearing — see [MergeExecutor]).
     */
    CONSUME,

    /**
     * The source documents are RETAINED; every distinct source ref is re-put
     * under a new key BEFORE the merged upsert, so a later source deletion
     * can never sweep a ref the merged document lives on.
     */
    COPY,
}

/** One COPY-mode byte re-put: read [sourceRef], store under [targetKey]. */
data class RefCopy(val sourceRef: String, val targetKey: String)

/**
 * The merge value object (CAMSCAN-PROD-008 §6.3): the merged [Document],
 * its re-minted [mergedPages], the ordered [sourceDocumentIds], and — COPY
 * mode only — the ordered [refCopies] the executor must perform BEFORE the
 * merged upsert. [refCopies] is empty in CONSUME mode (refs preserved).
 *
 * [mergedPages] carry the SOURCE refs verbatim (correct for CONSUME); in
 * COPY mode the executor resolves them through the refs the puts return via
 * [pagesWithResolvedRefs] — the pure plan never guesses opaque store refs.
 */
data class MergePlan(
    val mode: MergeMode,
    val mergedDocument: Document,
    val mergedPages: List<Page>,
    val sourceDocumentIds: List<String>,
    val refCopies: List<RefCopy>,
) {

    /**
     * COPY-mode ref resolution: every source ref present in [resolved] is
     * swapped for its re-put ref; unknown refs pass through unchanged.
     * Identity in CONSUME mode (the mapping is empty by construction).
     */
    fun pagesWithResolvedRefs(resolved: Map<String, String>): List<Page> =
        mergedPages.map { page ->
            page.copy(
                sourceCaptureRef = page.sourceCaptureRef?.let { resolved[it] ?: it },
                processedImageRef = page.processedImageRef?.let { resolved[it] ?: it },
            )
        }
}

/**
 * PURE merge core (CAMSCAN-PROD-008 §6.3) — no I/O, fully JVM-testable.
 *
 * Order law (documented): the merged pages follow SOURCE-DOCUMENT order (the
 * order of [documents]), stable within each source (pages sorted by index).
 * Indices 0..N-1 across the whole merged page list.
 *
 * Title rule (documented): the FIRST source's title + " + " + (n-1) + " more".
 *
 * Ids: the merged document id and EVERY page id are freshly minted —
 * page.documentId = the fresh merged id (the frozen model's single-valued
 * documentId requires it; unique ids keep deletion safe; no id is shared
 * with any source page). Re-minted pages carry the merge timestamp as
 * createdAt/updatedAt.
 *
 * Provenance rule: sourceType = SCAN only when EVERY source is SCAN, else
 * IMPORTED.
 *
 * API contract (documented, mirroring PROD-007's PdfWriter malformed-input
 * rule): fewer than two sources — or duplicate document ids — throw
 * [IllegalArgumentException].
 */
class DocumentMergeEngine {

    fun merge(
        documents: List<Document>,
        pagesByDocument: Map<String, List<Page>>,
        mode: MergeMode,
        idGenerator: IdGenerator,
        timeSource: TimeSource,
    ): MergePlan {
        require(documents.size >= MIN_SOURCES) {
            "merge requires at least $MIN_SOURCES source documents (documented API contract); was ${documents.size}"
        }
        val documentIds = documents.map { it.id }
        require(documentIds.toSet().size == documentIds.size) {
            "merge source documents must have distinct ids (documented API contract)"
        }

        val mergedDocumentId = idGenerator.newId()
        val now = timeSource.nowMillis()

        val mergedPages = ArrayList<Page>(documents.sumOf { pagesByDocument[it.id].orEmpty().size })
        for (source in documents) {
            val sourcePages = pagesByDocument[source.id].orEmpty().sortedBy { it.index }
            for (sourcePage in sourcePages) {
                val pageId = idGenerator.newId()
                mergedPages += sourcePage.copy(
                    id = pageId,
                    documentId = mergedDocumentId,
                    index = mergedPages.size,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                )
            }
        }

        val mergedDocument = Document(
            id = mergedDocumentId,
            title = documents.first().title + TITLE_SUFFIX_PREFIX + (documents.size - 1) + TITLE_SUFFIX_SUFFIX,
            pageIds = mergedPages.map { it.id },
            sourceType = if (documents.all { it.sourceType == DocumentSource.SCAN }) {
                DocumentSource.SCAN
            } else {
                DocumentSource.IMPORTED
            },
            createdAtMillis = now,
            updatedAtMillis = now,
        )

        val refCopies = if (mode == MergeMode.COPY) {
            buildRefCopies(mergedPages, now)
        } else {
            emptyList()
        }

        return MergePlan(
            mode = mode,
            mergedDocument = mergedDocument,
            mergedPages = mergedPages,
            sourceDocumentIds = documentIds,
            refCopies = refCopies,
        )
    }

    /**
     * COPY-mode re-put plan: every DISTINCT live ref (processed first, then
     * source-capture, walking pages in merged order) gets one target key —
     * `merge/<ts>-<pageId>.img` for processed page images, `merge/<ts>-<pageId>.src`
     * for stored originals. A ref shared by several pages is copied once
     * (under the first page that references it) and resolved consistently.
     */
    private fun buildRefCopies(pages: List<Page>, now: Long): List<RefCopy> {
        val copies = ArrayList<RefCopy>()
        val seen = HashSet<String>()
        for (page in pages) {
            page.processedImageRef?.let { ref ->
                if (seen.add(ref)) {
                    copies += RefCopy(ref, "merge/$now-${page.id}.img")
                }
            }
            page.sourceCaptureRef?.let { ref ->
                if (seen.add(ref)) {
                    copies += RefCopy(ref, "merge/$now-${page.id}.src")
                }
            }
        }
        return copies
    }

    companion object {
        const val MIN_SOURCES = 2
        const val TITLE_SUFFIX_PREFIX = " + "
        const val TITLE_SUFFIX_SUFFIX = " more"
    }
}

/**
 * Thin executor applying a [MergePlan] (CAMSCAN-PROD-008 §6.3). The ORDER
 * IS LOAD-BEARING against PROD-006's orphan sweep
 * ([org.payswap.camscan.document.persistence.PersistentDocumentRepository]):
 * the sweep deletes only refs unreferenced by the CURRENT index, so —
 *
 * 1. COPY mode: perform every [RefCopy] put BEFORE the merged upsert. A
 *    later source deletion then sweeps only the SOURCE's own refs, never
 *    the merged document's copies.
 * 2. Upsert the merged document FIRST, so its pages already reference the
 *    preserved (CONSUME) or copied (COPY) refs on the index BEFORE…
 * 3. …any CONSUME-mode source delete runs its own sweep. Reversed (delete
 *    sources first), each delete sweeps the refs the merged document is
 *    about to record — the merge would point at deleted bytes.
 *
 * Throws [IllegalStateException] when a COPY-mode source ref is missing
 * from the store (honest failure before any partial write).
 */
class MergeExecutor(
    private val repository: DocumentRepository,
    private val contentStore: ContentStore,
) {

    suspend fun apply(plan: MergePlan) {
        val resolvedRefs = HashMap<String, String>()

        for (copy in plan.refCopies) {
            val bytes = contentStore.open(copy.sourceRef)
                ?: throw IllegalStateException("merge source ref missing from store: ${copy.sourceRef}")
            resolvedRefs[copy.sourceRef] = contentStore.put(copy.targetKey, bytes)
        }

        val pages = if (plan.mode == MergeMode.COPY) {
            plan.pagesWithResolvedRefs(resolvedRefs)
        } else {
            plan.mergedPages
        }
        repository.upsertDocument(plan.mergedDocument, pages)

        if (plan.mode == MergeMode.CONSUME) {
            plan.sourceDocumentIds.forEach { repository.deleteDocument(it) }
        }
    }
}
