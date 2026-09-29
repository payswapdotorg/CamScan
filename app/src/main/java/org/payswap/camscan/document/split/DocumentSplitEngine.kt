package org.payswap.camscan.document.split

import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.document.persistence.IdGenerator

/**
 * The split/extract value object (CAMSCAN-PROD-008 §6.4): the new extracted
 * [Document] with its re-minted [extractedPages], plus the compacted source
 * ([compactedSourceDocument] keeps its id and title, bumps
 * updatedAtMillis; [compactedSourcePages] re-index 0..K-1 with relative
 * order preserved).
 *
 * Semantics: extract = MOVE — the selected pages LEAVE the source (plain
 * page deletion stays PROD-006's document_page_delete). Refs are PRESERVED
 * and ownership TRANSFERS: extracted pages keep their source refs with new
 * ids + the new document id; complement pages keep ids, refs and
 * timestamps, only their indices compact. The SplitExecutor's order —
 * extracted upsert FIRST, compacted source SECOND — is load-bearing (see
 * its KDoc).
 */
data class SplitPlan(
    val extractedDocument: Document,
    val extractedPages: List<Page>,
    val compactedSourceDocument: Document,
    val compactedSourcePages: List<Page>,
)

/**
 * PURE split/extract core (CAMSCAN-PROD-008 §6.4) — no I/O, no store puts,
 * fully JVM-testable.
 *
 * Rules (documented):
 * - the extracted document gets a FRESH id; title = source title + " (extracted)";
 *   provenance (sourceType) carries over from the source;
 * - selected pages are re-minted (new ids, documentId = the fresh extracted
 *   id), indices 0..M-1 in SOURCE order, refs preserved (ownership
 *   transfers), timestamps = the split time;
 * - the complement stays in the source with ids/refs/timestamps untouched,
 *   indices compacted 0..K-1, relative order preserved; the source
 *   document's updatedAtMillis is bumped;
 * - ALL pages are selectable — the source becomes an honest EMPTY document
 *   (PROD-006's empty state + delete affordance apply);
 *
 * API contract (documented): an empty [selectedPageIds], duplicate
 * selections, or ids unknown to [pages] throw [IllegalArgumentException].
 */
class DocumentSplitEngine {

    fun extract(
        document: Document,
        pages: List<Page>,
        selectedPageIds: List<String>,
        idGenerator: IdGenerator,
        timeSource: TimeSource,
    ): SplitPlan {
        require(selectedPageIds.isNotEmpty()) {
            "extract requires a non-empty page selection (documented API contract)"
        }
        require(selectedPageIds.toSet().size == selectedPageIds.size) {
            "extract page selection must not contain duplicates (documented API contract)"
        }
        val pagesById = pages.associateBy { it.id }
        require(selectedPageIds.all { it in pagesById }) {
            "extract page selection references unknown page ids (documented API contract)"
        }

        val extractedDocumentId = idGenerator.newId()
        val now = timeSource.nowMillis()

        val orderedPages = pages.sortedBy { it.index }
        val selected = orderedPages.filter { it.id in selectedPageIds }
        val complement = orderedPages.filter { it.id !in selectedPageIds }

        val extractedPages = selected.mapIndexed { index, sourcePage ->
            sourcePage.copy(
                id = idGenerator.newId(),
                documentId = extractedDocumentId,
                index = index,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        }

        val extractedDocument = Document(
            id = extractedDocumentId,
            title = document.title + TITLE_SUFFIX,
            pageIds = extractedPages.map { it.id },
            sourceType = document.sourceType,
            createdAtMillis = now,
            updatedAtMillis = now,
        )

        val compactedSourcePages = complement.mapIndexed { index, page ->
            page.copy(index = index)
        }
        val compactedSourceDocument = document.copy(
            pageIds = compactedSourcePages.map { it.id },
            updatedAtMillis = now,
        )

        return SplitPlan(
            extractedDocument = extractedDocument,
            extractedPages = extractedPages,
            compactedSourceDocument = compactedSourceDocument,
            compactedSourcePages = compactedSourcePages,
        )
    }

    companion object {
        const val TITLE_SUFFIX = " (extracted)"
    }
}

/**
 * Thin executor applying a [SplitPlan] (CAMSCAN-PROD-008 §6.4). ZERO
 * ContentStore puts — refs are preserved and ownership transfers. The ORDER
 * IS LOAD-BEARING against PROD-006's orphan sweep
 * ([org.payswap.camscan.document.persistence.PersistentDocumentRepository]):
 * the sweep deletes only refs unreferenced by the CURRENT index, so the
 * extracted document — the transferred refs' NEW owner — must be upserted
 * FIRST. Reversed (compact the source first), the source's sweep deletes
 * the transferred refs before the extracted document records them.
 */
class SplitExecutor(
    private val repository: DocumentRepository,
) {

    suspend fun apply(plan: SplitPlan) {
        repository.upsertDocument(plan.extractedDocument, plan.extractedPages)
        repository.upsertDocument(plan.compactedSourceDocument, plan.compactedSourcePages)
    }
}
