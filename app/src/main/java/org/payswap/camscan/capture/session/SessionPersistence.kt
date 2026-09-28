package org.payswap.camscan.capture.session

import org.payswap.camscan.core.repository.DocumentRepository
import org.payswap.camscan.core.storage.ContentStore
import org.payswap.camscan.core.time.TimeSource

/*
 * CAMSCAN-PROD-004 §6.1 — the session's persistence seam, Worker-1 owned.
 *
 * The scan session consumes the lead-owned core contracts (repository,
 * content store, time source) READ-ONLY through this bundle; no core file
 * is ever touched. The SHELL (Worker 2 / integration station) wires a real
 * implementation when durable documents exist; until then the session runs
 * with [UNAVAILABLE] (or a null reference) and every finish degrades
 * honestly to onScanFinished(null) — exactly the placeholder behavior the
 * shell observes today.
 *
 * ids come from [idGenerator] (never UUID.randomUUID scattered through the
 * product path), time from [timeSource] (never System.currentTimeMillis).
 */
interface SessionPersistence {

    /** Lead-owned document repository contract (read-only use). */
    val repository: DocumentRepository

    /** Lead-owned binary-asset store contract (read-only use). */
    val contentStore: ContentStore

    /** Deterministic time seam — all session timestamps flow through here. */
    val timeSource: TimeSource

    /** Mint station for document/page ids (inject deterministic fakes in tests). */
    val idGenerator: () -> String

    /**
     * False only for [UNAVAILABLE]: persistence is not wired, adapters must
     * short-circuit and sessions finish with null document ids.
     */
    val isAvailable: Boolean
        get() = true

    companion object {

        /**
         * The null-object used when the shell has not wired persistence yet.
         * Every member is a loud programmer-error guard — correct flows check
         * [isAvailable] (or identity against this object) FIRST and never
         * touch the members; a member hit means a wiring bug, not a runtime
         * condition to swallow.
         */
        val UNAVAILABLE: SessionPersistence = UnavailableSessionPersistence
    }
}

private object UnavailableSessionPersistence : SessionPersistence {

    override val repository: DocumentRepository
        get() = unavailable("repository")

    override val contentStore: ContentStore
        get() = unavailable("contentStore")

    override val timeSource: TimeSource
        get() = unavailable("timeSource")

    override val idGenerator: () -> String
        get() = unavailable("idGenerator")

    override val isAvailable: Boolean
        get() = false

    private fun unavailable(member: String): Nothing = error(
        "SessionPersistence.UNAVAILABLE.$member must never be used: wire a real " +
            "SessionPersistence at the integration station, or check isAvailable first.",
    )
}
