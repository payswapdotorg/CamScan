package org.payswap.camscan.core.storage


/**
 * Contract for durable binary assets (source captures, processed page images,
 * exported artifacts) — lead-owned (PRODUCT-ARCHITECTURE-LOCK §9). Refs are
 * opaque stable strings owned by the implementation; nothing else may parse
 * their structure.
 */
interface ContentStore {
    /** Stores [bytes] under the caller-chosen logical [key]; returns the durable ref. */
    suspend fun put(key: String, bytes: ByteArray): String


    suspend fun open(ref: String): ByteArray?


    suspend fun delete(ref: String): Boolean


    suspend fun exists(ref: String): Boolean
}
