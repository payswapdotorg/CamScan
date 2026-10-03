package org.payswap.camscan.tools.backup

// CAMSCAN-PROD-015 §6.1 — BackupEntry: one archive member's descriptor.
// Deterministic single-line serialized form (ModeNote discipline):
//     pathInArchive | kind | sizeBytes | sha256Hex
// Four columns, pipe-separated, no quoting and no escaping scheme — string
// fields are VALIDATED to contain none of the forbidden characters (pipe,
// tab, carriage return, newline), so no field can inject columns or lines.
// [toLine] throws IllegalArgumentException on invalid content; [parse]
// returns null on any malformed line. Round-trip is byte-identical for
// every valid entry.

/** Kind of content one backup archive member carries. */
enum class BackupEntryKind {
    /** The index file of one document. */
    DOCUMENT_INDEX,

    /** A page image of a document. */
    PAGE_IMAGE,

    /** Recognized OCR text of a page. */
    OCR_TEXT,

    /** A specialist-mode note attached to a document. */
    MODE_NOTE,
}

/** Descriptor of one member inside a local backup archive. */
data class BackupEntry(
    val pathInArchive: String,
    val kind: BackupEntryKind,
    val sizeBytes: Long,
    val sha256Hex: String,
) {

    /** Deterministic single-line serialized form (documented field order above; byte-stable). Throws IllegalArgumentException when a field is invalid — callers persist only valid entries. */
    fun toLine(): String {
        if (!isValidPath(pathInArchive)) {
            throw IllegalArgumentException(
                "pathInArchive must be non-empty, relative (no leading slash) " +
                    "and free of forbidden characters (pipe, tab, CR, LF)",
            )
        }
        if (sizeBytes < 0L) {
            throw IllegalArgumentException("sizeBytes must be non-negative: " + sizeBytes)
        }
        if (!Sha256Text.isValidHex(sha256Hex)) {
            throw IllegalArgumentException(
                "sha256Hex must be exactly 64 lowercase hex characters",
            )
        }
        return pathInArchive + SEPARATOR + kind.name + SEPARATOR +
            sizeBytes.toString() + SEPARATOR + sha256Hex
    }

    companion object {

        /** Column separator of the serialized form. */
        const val SEPARATOR = "|"

        /** Parses a serialized line back into a [BackupEntry]. Returns null (never throws) when the line is malformed: wrong column count, forbidden characters, absolute path, unknown kind, negative or non-numeric size, or a sha256 that is not 64 lowercase hex chars. */
        fun parse(line: String): BackupEntry? {
            if (line.isEmpty()) return null
            // Line-level guard: one serialized entry is ONE line — any
            // newline, carriage return or tab anywhere in the raw input is
            // injection and rejected before column parsing.
            for (ch in line) {
                if (ch == '\n') return null
                if (ch == '\r') return null
                if (ch == '\t') return null
            }
            val columns = line.split(SEPARATOR)
            if (columns.size != 4) return null
            val path = columns[0]
            if (!isValidPath(path)) return null
            val kind = try {
                BackupEntryKind.valueOf(columns[1])
            } catch (e: IllegalArgumentException) {
                return null
            }
            val sizeBytes = columns[2].toLongOrNull() ?: return null
            if (sizeBytes < 0L) return null
            val sha256Hex = columns[3]
            if (!Sha256Text.isValidHex(sha256Hex)) return null
            return BackupEntry(path, kind, sizeBytes, sha256Hex)
        }

        /** True when the path is non-empty, relative and forbidden-char free. */
        fun isValidPath(path: String): Boolean {
            if (path.isEmpty()) return false
            if (path.startsWith("/")) return false
            for (ch in path) {
                if (ch == '|') return false
                if (ch == '\t') return false
                if (ch == '\r') return false
                if (ch == '\n') return false
            }
            return true
        }
    }
}
