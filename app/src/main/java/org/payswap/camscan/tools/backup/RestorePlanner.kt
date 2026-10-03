package org.payswap.camscan.tools.backup

import org.payswap.camscan.core.time.TimeSource

// CAMSCAN-PROD-015 §6.1 — RestorePlanner: pure merge intelligence for
// restoring a backup archive into an existing document index. It NEVER
// throws and never writes: it computes a sealed [RestorePlan] that either
// lists one decision per incoming document id or carries unresolvable
// conflicts. Deterministic ordering everywhere: decisions and conflicts
// are sorted by document id ascending.
//
// Resolution rules (documented):
//   - incoming id absent from the current index  -> AddDocument
//   - present, policy KeepExisting               -> KeepExisting
//   - present, policy KeepIncoming               -> ReplaceDocument
//   - present, policy NewerWins                  -> strictly newer side wins;
//     an EXACT timestamp tie is unresolvable -> RestoreConflict (never
//     silently resolved).
//   - the same id appearing twice on the incoming side, or twice in the
//     current index for an id the restore would touch, is ambiguous ->
//     RestoreConflict. A duplicated current id that no incoming document
//     touches does not block the plan (the restore never writes it).
//
// The NewerWins policy carries an injected [TimeSource] used ONLY to
// stamp the detectedAtMillis of tie conflicts (audit trail); the merge
// rule itself compares only the two lastModified timestamps, so plans
// stay deterministic under an injected fixed clock.

/** One document of the current index, as seen by the planner. */
data class RestoreDocumentRef(
    val documentId: String,
    val lastModifiedMillis: Long,
)

/** Snapshot of the current document index (pure model, no app imports). */
data class RestoreIndexSnapshot(val documents: List<RestoreDocumentRef>)

/** One document on the incoming (backup) side, as seen by the planner. */
data class RestoreIncomingDocument(
    val documentId: String,
    val lastModifiedMillis: Long,
)

/** Merge policy deciding between the existing and the incoming document. */
sealed class RestorePolicy {

    /** Conflicts always resolve toward the document already in the index. */
    object KeepExisting : RestorePolicy()

    /** Conflicts always resolve toward the backup copy. */
    object KeepIncoming : RestorePolicy()

    /** The strictly newer lastModified wins; exact ties are unresolvable conflicts. The injected clock only stamps tie-conflict detection. */
    data class NewerWins(val timeSource: TimeSource) : RestorePolicy()
}

/** What the restore will do with one incoming document id. */
sealed class RestoreDecision {

    /** The document id this decision applies to. */
    abstract val documentId: String

    /** Copy the document in; nothing exists at that id today. */
    class AddDocument(override val documentId: String) : RestoreDecision()

    /** Overwrite the existing document with the backup copy. */
    class ReplaceDocument(override val documentId: String) : RestoreDecision()

    /** Leave the existing document untouched. */
    class KeepExisting(override val documentId: String) : RestoreDecision()
}

/** One unresolvable restore conflict — carried, never silently resolved. A -1 in a timestamp field means "not applicable to this conflict kind". */
data class RestoreConflict(
    val documentId: String,
    val existingLastModifiedMillis: Long,
    val incomingLastModifiedMillis: Long,
    val detectedAtMillis: Long,
    val reason: String,
)

/** Sealed result of planning a restore: a full decision list, or conflicts. */
sealed class RestorePlan {

    /** Every incoming document resolved; sorted by document id ascending. */
    class Planned(val decisions: List<RestoreDecision>) : RestorePlan()

    /** At least one unresolvable conflict; nothing may be applied. */
    class Conflicts(val conflicts: List<RestoreConflict>) : RestorePlan()
}

/** Pure planner that merges an incoming backup into the current index. */
object RestorePlanner {

    /** Derives the incoming document list from a backup manifest: every DOCUMENT_INDEX entry at a canonical "documents/<id>/index.json" path contributes one document whose lastModifiedMillis is the manifest's createdAtMillis (the backup snapshot time stands in for the per-document timestamp — the engine is deliberately decoupled from the app's index codec). Non-canonical or non-index entries are ignored by this derivation. */
    fun incomingFrom(manifest: BackupManifest): List<RestoreIncomingDocument> {
        val result = mutableListOf<RestoreIncomingDocument>()
        for (entry in manifest.entries) {
            if (entry.kind != BackupEntryKind.DOCUMENT_INDEX) continue
            val documentId = BackupPaths.documentIdFromIndexPath(entry.pathInArchive)
            if (documentId == null) continue
            result.add(RestoreIncomingDocument(documentId, manifest.createdAtMillis))
        }
        return result
    }

    /** Plans the restore of the given incoming documents into the current snapshot under the given policy. Never throws; deterministic order. */
    fun plan(
        current: RestoreIndexSnapshot,
        incoming: List<RestoreIncomingDocument>,
        policy: RestorePolicy,
    ): RestorePlan {
        val currentById = HashMap<String, MutableList<RestoreDocumentRef>>()
        for (document in current.documents) {
            currentById.getOrPut(document.documentId) { mutableListOf() }.add(document)
        }
        val incomingById = LinkedHashMap<String, MutableList<RestoreIncomingDocument>>()
        for (document in incoming) {
            incomingById.getOrPut(document.documentId) { mutableListOf() }.add(document)
        }
        val conflicts = mutableListOf<RestoreConflict>()
        for (id in incomingById.keys) {
            val incomingList = incomingById.getValue(id)
            if (incomingList.size > 1) {
                conflicts.add(
                    RestoreConflict(
                        documentId = id,
                        existingLastModifiedMillis = existingStamp(currentById, id),
                        incomingLastModifiedMillis = incomingList[0].lastModifiedMillis,
                        detectedAtMillis = -1L,
                        reason = "duplicate incoming document id (" +
                            incomingList.size.toString() + " copies)",
                    ),
                )
            }
            val currentList = currentById[id]
            if (currentList != null && currentList.size > 1) {
                conflicts.add(
                    RestoreConflict(
                        documentId = id,
                        existingLastModifiedMillis = currentList[0].lastModifiedMillis,
                        incomingLastModifiedMillis = -1L,
                        detectedAtMillis = -1L,
                        reason = "duplicate current document id (" +
                            currentList.size.toString() + " copies)",
                    ),
                )
            }
        }
        if (conflicts.isNotEmpty()) {
            return RestorePlan.Conflicts(conflicts.sortedBy { it.documentId })
        }
        val decisions = mutableListOf<RestoreDecision>()
        for (id in incomingById.keys.sorted()) {
            val existing = currentById[id]?.firstOrNull()
            val incomingDocument = incomingById.getValue(id)[0]
            val decision = resolve(existing, incomingDocument, policy, conflicts)
            if (decision != null) {
                decisions.add(decision)
            }
        }
        if (conflicts.isNotEmpty()) {
            return RestorePlan.Conflicts(conflicts.sortedBy { it.documentId })
        }
        return RestorePlan.Planned(decisions)
    }

    /** Convenience combination of [incomingFrom] and [plan] for a backup manifest. Same semantics; never throws. */
    fun planFromManifest(
        current: RestoreIndexSnapshot,
        manifest: BackupManifest,
        policy: RestorePolicy,
    ): RestorePlan {
        return plan(current, incomingFrom(manifest), policy)
    }

    private fun resolve(
        existing: RestoreDocumentRef?,
        incoming: RestoreIncomingDocument,
        policy: RestorePolicy,
        conflicts: MutableList<RestoreConflict>,
    ): RestoreDecision? {
        if (existing == null) {
            return RestoreDecision.AddDocument(incoming.documentId)
        }
        when (policy) {
            is RestorePolicy.KeepExisting -> return RestoreDecision.KeepExisting(incoming.documentId)
            is RestorePolicy.KeepIncoming -> return RestoreDecision.ReplaceDocument(incoming.documentId)
            is RestorePolicy.NewerWins -> {
                if (incoming.lastModifiedMillis > existing.lastModifiedMillis) {
                    return RestoreDecision.ReplaceDocument(incoming.documentId)
                }
                if (incoming.lastModifiedMillis < existing.lastModifiedMillis) {
                    return RestoreDecision.KeepExisting(incoming.documentId)
                }
                conflicts.add(
                    RestoreConflict(
                        documentId = incoming.documentId,
                        existingLastModifiedMillis = existing.lastModifiedMillis,
                        incomingLastModifiedMillis = incoming.lastModifiedMillis,
                        detectedAtMillis = policy.timeSource.nowMillis(),
                        reason = "exact lastModified tie under NewerWins is unresolvable",
                    ),
                )
                return null
            }
        }
    }

    private fun existingStamp(
        currentById: Map<String, List<RestoreDocumentRef>>,
        id: String,
    ): Long {
        val list = currentById[id]
        if (list == null || list.isEmpty()) return -1L
        return list[0].lastModifiedMillis
    }
}
