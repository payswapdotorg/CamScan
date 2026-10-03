package org.payswap.camscan.tools.backup

// CAMSCAN-PROD-015 §6.1 — the injected IO seam of the backup engines.
// The engines never touch java.io.File or the Android filesystem; every
// byte that moves goes through [BackupFileIo], so tests run on an
// in-memory fake and the lead-owned integration wires a real store.

/** Pure byte-level IO seam used by the backup/restore engines. */
interface BackupFileIo {

    /** Writes the full bytes of one file (overwriting any existing content at the path). Implementations throw java.io.IOException on failure. */
    fun write(path: String, bytes: ByteArray)

    /** Reads the full bytes of one file, or null when nothing exists at the path (absent is a normal state, not an error). Implementations throw java.io.IOException on real IO failure. */
    fun read(path: String): ByteArray?

    /** Lists the stored paths that start with the given prefix, sorted ascending. An empty prefix lists everything. */
    fun list(prefix: String): List<String>
}

// CAMSCAN-PROD-015 §6.1 — BackupPaths: the documented archive-layout
// conventions shared by the writer, the restore planner and the applier.
// Document subtree: "documents/<documentId>/..." with the document index
// at "documents/<documentId>/index.json".

/** Documented path conventions of the backup archive layout. */
object BackupPaths {

    /** Root folder of all per-document members inside an archive. */
    const val DOCUMENTS_ROOT = "documents"

    /** Fixed file name of a document's index member. */
    const val INDEX_FILE_NAME = "index.json"

    /** Builds the canonical index-member path of one document. */
    fun documentIndexPath(documentId: String): String {
        return DOCUMENTS_ROOT + "/" + documentId + "/" + INDEX_FILE_NAME
    }

    /** Prefix of every member belonging to one document. */
    fun documentPrefix(documentId: String): String {
        return DOCUMENTS_ROOT + "/" + documentId + "/"
    }

    /** Extracts the document id from a canonical index path ("documents/<id>/index.json"); null when the path is not canonical. */
    fun documentIdFromIndexPath(path: String): String? {
        val parts = path.split("/")
        if (parts.size != 3) return null
        if (parts[0] != DOCUMENTS_ROOT) return null
        if (parts[2] != INDEX_FILE_NAME) return null
        val documentId = parts[1]
        if (documentId.isEmpty()) return null
        return documentId
    }
}
