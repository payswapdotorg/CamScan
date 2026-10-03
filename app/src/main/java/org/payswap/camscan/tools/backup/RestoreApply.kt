package org.payswap.camscan.tools.backup

// CAMSCAN-PROD-015 §6.1 — RestoreApply: the plan-executing half of the
// restore engine. It writes ONLY what a [RestorePlan.Planned] authorizes,
// through the same [BackupFileIo] seam the archive uses: AddDocument and
// ReplaceDocument copy the document's whole archive subtree
// ("documents/<id>/...") into the target root; KeepExisting writes
// nothing; a [RestorePlan.Conflicts] writes NOTHING at all. Dry-run is
// simply planning without calling apply. Deterministic: files are written
// in plan order, then manifest order.

/** Sealed outcome of applying a restore plan. */
sealed class RestoreApplyResult {

    /** What apply wrote, per decision kind, with write statistics. */
    class Applied(
        val added: List<String>,
        val replaced: List<String>,
        val kept: List<String>,
        val filesWritten: Int,
        val filesOverwritten: Int,
        val bytesWritten: Long,
    ) : RestoreApplyResult()

    /** Conflicts blocked the apply; nothing was written. */
    class Blocked(val conflicts: List<RestoreConflict>) : RestoreApplyResult()
}

/** Executes a planned restore through the backup IO seam. */
object RestoreApply {

    /** Applies the plan: writes exactly the authorized members into targetRoot (joined as targetRoot + "/" + memberPath). A Conflicts plan writes nothing and returns [RestoreApplyResult.Blocked]. Throws IllegalArgumentException for an invalid target root (blank, or containing pipe/tab/CR/LF). */
    fun apply(
        plan: RestorePlan,
        archive: BackupArchiveContents,
        targetRoot: String,
        io: BackupFileIo,
    ): RestoreApplyResult {
        val root = normalizeRoot(targetRoot)
        if (plan is RestorePlan.Conflicts) {
            return RestoreApplyResult.Blocked(plan.conflicts)
        }
        val planned = plan as RestorePlan.Planned
        val existingBefore = HashSet(io.list(root + "/"))
        val added = mutableListOf<String>()
        val replaced = mutableListOf<String>()
        val kept = mutableListOf<String>()
        var filesWritten = 0
        var filesOverwritten = 0
        var bytesWritten = 0L
        for (decision in planned.decisions) {
            when (decision) {
                is RestoreDecision.AddDocument -> {
                    added.add(decision.documentId)
                    val stats = copyDocument(decision.documentId, archive, root, io, existingBefore)
                    filesWritten += stats.first
                    filesOverwritten += stats.second
                    bytesWritten += stats.third
                }
                is RestoreDecision.ReplaceDocument -> {
                    replaced.add(decision.documentId)
                    val stats = copyDocument(decision.documentId, archive, root, io, existingBefore)
                    filesWritten += stats.first
                    filesOverwritten += stats.second
                    bytesWritten += stats.third
                }
                is RestoreDecision.KeepExisting -> kept.add(decision.documentId)
            }
        }
        return RestoreApplyResult.Applied(
            added = added,
            replaced = replaced,
            kept = kept,
            filesWritten = filesWritten,
            filesOverwritten = filesOverwritten,
            bytesWritten = bytesWritten,
        )
    }

    private fun copyDocument(
        documentId: String,
        archive: BackupArchiveContents,
        root: String,
        io: BackupFileIo,
        existingBefore: Set<String>,
    ): Triple<Int, Int, Long> {
        var filesWritten = 0
        var filesOverwritten = 0
        var bytesWritten = 0L
        val entries = archive.membersUnder(BackupPaths.documentPrefix(documentId))
        for (entry in entries) {
            val bytes = archive.member(entry.pathInArchive)
            if (bytes == null) continue
            val targetPath = root + "/" + entry.pathInArchive
            if (existingBefore.contains(targetPath)) {
                filesOverwritten++
            }
            io.write(targetPath, bytes)
            filesWritten++
            bytesWritten += bytes.size
        }
        return Triple(filesWritten, filesOverwritten, bytesWritten)
    }

    private fun normalizeRoot(targetRoot: String): String {
        var root = targetRoot.trim()
        if (root.isEmpty()) {
            throw IllegalArgumentException("targetRoot must not be blank")
        }
        for (ch in root) {
            if (ch == '|') throw IllegalArgumentException("targetRoot contains a forbidden character: pipe")
            if (ch == '\t') throw IllegalArgumentException("targetRoot contains a forbidden character: tab")
            if (ch == '\r') throw IllegalArgumentException("targetRoot contains a forbidden character: CR")
            if (ch == '\n') throw IllegalArgumentException("targetRoot contains a forbidden character: LF")
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length - 1)
        }
        if (root.isEmpty()) {
            throw IllegalArgumentException("targetRoot must not be only slashes")
        }
        return root
    }
}
