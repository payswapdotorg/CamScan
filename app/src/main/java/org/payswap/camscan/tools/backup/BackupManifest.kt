package org.payswap.camscan.tools.backup

// CAMSCAN-PROD-015 §6.1 — BackupManifest: the header record of a local
// backup archive. Deterministic line-based serialized form (ModeNote
// discipline), documented FROZEN shape — lines joined by LF, no trailing
// newline:
//     line 1  : header   = "camscan-backup" | schemaVersion | createdAtMillis | appVersionHint
//     line 2  : count    = "entries" | N
//     line 3..: N entry lines (BackupEntry.toLine, manifest order)
//     last    : footer   = "end" | N
// schemaVersion is 1 (the only supported version). appVersionHint may be
// empty. [toText] throws IllegalArgumentException on invalid content;
// [parse] returns null on ANY malformed text (including a trailing LF).
// Round-trip is byte-identical for every valid manifest.

import org.payswap.camscan.core.time.TimeSource

/** Header record of one local backup archive. */
data class BackupManifest(
    val schemaVersion: Int,
    val createdAtMillis: Long,
    val appVersionHint: String,
    val entries: List<BackupEntry>,
) {

    /** Number of entries declared in this manifest. */
    val entryCount: Int
        get() = entries.size

    /** Sum of the declared member sizes in bytes. */
    val totalSizeBytes: Long
        get() = entries.fold(0L) { acc, entry -> acc + entry.sizeBytes }

    /** Deterministic serialized text (documented shape above; byte-stable). Throws IllegalArgumentException when any field or entry is invalid. */
    fun toText(): String {
        if (schemaVersion != SCHEMA_VERSION_1) {
            throw IllegalArgumentException(
                "unsupported schemaVersion: " + schemaVersion +
                    " (only " + SCHEMA_VERSION_1 + " is supported)",
            )
        }
        if (!isValidVersionHint(appVersionHint)) {
            throw IllegalArgumentException(
                "appVersionHint contains a forbidden character (pipe, tab, CR, LF)",
            )
        }
        // Force entry validation (path, size, sha) before serializing.
        for (entry in entries) {
            entry.toLine()
        }
        val lines = mutableListOf<String>()
        lines.add(
            HEADER_TAG + BackupEntry.SEPARATOR + schemaVersion.toString() +
                BackupEntry.SEPARATOR + createdAtMillis.toString() +
                BackupEntry.SEPARATOR + appVersionHint,
        )
        lines.add(COUNT_TAG + BackupEntry.SEPARATOR + entries.size.toString())
        for (entry in entries) {
            lines.add(entry.toLine())
        }
        lines.add(END_TAG + BackupEntry.SEPARATOR + entries.size.toString())
        return lines.joinToString(LINE_SEPARATOR)
    }

    companion object {

        /** Fixed schema version of this engine generation. */
        const val SCHEMA_VERSION_1 = 1

        /** Header tag of the first serialized line. */
        const val HEADER_TAG = "camscan-backup"

        /** Tag of the entry-count line. */
        const val COUNT_TAG = "entries"

        /** Tag of the footer line. */
        const val END_TAG = "end"

        /** Line separator of the serialized form (LF only, never platform). */
        const val LINE_SEPARATOR = "\n"

        /** Builds a fresh manifest stamped through an injected [TimeSource] (deterministic time seam; the engine never reads the wall clock directly). Throws IllegalArgumentException on invalid entries or an invalid version hint. */
        fun create(
            entries: List<BackupEntry>,
            appVersionHint: String,
            timeSource: TimeSource,
        ): BackupManifest {
            val manifest = BackupManifest(
                schemaVersion = SCHEMA_VERSION_1,
                createdAtMillis = timeSource.nowMillis(),
                appVersionHint = appVersionHint,
                entries = entries.toList(),
            )
            manifest.toText()
            return manifest
        }

        /** Parses serialized manifest text back into a [BackupManifest]. Returns null (never throws) on any malformed input: empty text, embedded carriage returns, wrong line count, wrong tags, an unsupported schema version, non-numeric fields, count mismatch between the count line, the actual entry lines and the footer, or any malformed entry line. */
        fun parse(text: String): BackupManifest? {
            if (text.isEmpty()) return null
            // Whole-text guard: CR anywhere is injection (LF is the only
            // legal line separator; a trailing LF would create a phantom
            // empty line and is rejected by the line-count check below).
            if (text.contains('\r')) return null
            val lines = text.split(LINE_SEPARATOR)
            if (lines.size < 3) return null
            val header = lines[0].split(BackupEntry.SEPARATOR)
            if (header.size != 4) return null
            if (header[0] != HEADER_TAG) return null
            if (header[1] != SCHEMA_VERSION_1.toString()) return null
            val createdAtMillis = header[2].toLongOrNull() ?: return null
            val appVersionHint = header[3]
            if (!isValidVersionHint(appVersionHint)) return null
            val countLine = lines[1].split(BackupEntry.SEPARATOR)
            if (countLine.size != 2) return null
            if (countLine[0] != COUNT_TAG) return null
            val declaredCount = countLine[1].toIntOrNull() ?: return null
            if (declaredCount < 0) return null
            val footer = lines[lines.size - 1].split(BackupEntry.SEPARATOR)
            if (footer.size != 2) return null
            if (footer[0] != END_TAG) return null
            val footerCount = footer[1].toIntOrNull() ?: return null
            if (footerCount != declaredCount) return null
            // Lines between count and footer must be EXACTLY the entries.
            if (lines.size != declaredCount + 3) return null
            val entries = mutableListOf<BackupEntry>()
            for (index in 0 until declaredCount) {
                val parsed = BackupEntry.parse(lines[index + 2]) ?: return null
                entries.add(parsed)
            }
            return BackupManifest(
                schemaVersion = SCHEMA_VERSION_1,
                createdAtMillis = createdAtMillis,
                appVersionHint = appVersionHint,
                entries = entries,
            )
        }

        /** True when the version hint is empty or forbidden-char free. */
        fun isValidVersionHint(hint: String): Boolean {
            for (ch in hint) {
                if (ch == '|') return false
                if (ch == '\t') return false
                if (ch == '\r') return false
                if (ch == '\n') return false
            }
            return true
        }
    }
}
