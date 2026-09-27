package org.payswap.camscan.core.repository


import kotlinx.coroutines.flow.Flow
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page


/**
 * Domain persistence contract for documents and pages — lead-owned
 * (PRODUCT-ARCHITECTURE-LOCK §9). Implementations may be in-memory (tests,
 * early UI) or Room-backed (a later work order); callers depend only on this
 * interface.
 */
interface DocumentRepository {
    /** Emits the current document list (most recently updated first) on every change. */
    fun observeDocuments(): Flow<List<Document>>


    suspend fun getDocument(id: String): Document?


    /** Pages of a document in index order; empty when the document is unknown. */
    suspend fun getPages(documentId: String): List<Page>


    /** Creates or updates a document together with its full ordered page list. */
    suspend fun upsertDocument(document: Document, pages: List<Page>)


    suspend fun deleteDocument(id: String)
}
