package org.payswap.camscan.tools.modes

import org.payswap.camscan.core.time.TimeSource

// CAMSCAN-PROD-012 §6.5 — ModeNote: the persistence-shaped record of a
// specialist-mode capture. Pure data + a deterministic single-line
// serialized form. Field order (documented, frozen):
//     documentId | modeId | appliedAtMillis | pagesProduced | splitApplied |
//     selectionConfidence
// Separator is the pipe character; six columns, no quoting and no
// escaping scheme — instead, string fields are VALIDATED to contain none
// of the forbidden characters (pipe, tab, carriage return, newline), so
// no field can inject columns or lines. [toLine] throws
// IllegalArgumentException on invalid content; [parse] returns null on
// any malformed line (wrong column count, forbidden characters, bad
// numbers, non-finite confidence, bad boolean). Round-trip is
// byte-identical for every valid note.

/** Record of one specialist-mode capture, shaped for persistence. */
data class ModeNote(
    val documentId: String,
    val modeId: String,
    val appliedAtMillis: Long,
    val pagesProduced: Int,
    val splitApplied: Boolean,
    val selectionConfidence: Double,
) {

    /**
     * Deterministic single-line serialized form (documented field order
     * above; byte-stable). Throws IllegalArgumentException when a string
     * field contains a forbidden character or the confidence is not
     * finite — callers persist only valid notes.
     */
    fun toLine(): String {
        validateTextField(documentId, "documentId")
        validateTextField(modeId, "modeId")
        if (!selectionConfidence.isFinite()) {
            throw IllegalArgumentException(
                "selectionConfidence must be finite: " + selectionConfidence,
            )
        }
        return documentId + SEPARATOR + modeId + SEPARATOR +
            appliedAtMillis.toString() + SEPARATOR +
            pagesProduced.toString() + SEPARATOR +
            (if (splitApplied) "true" else "false") + SEPARATOR +
            selectionConfidence.toString()
    }

    companion object {
        /** Column separator of the serialized form. */
        const val SEPARATOR = "|"

        /** Characters a string field must never contain. */
        const val FORBIDDEN_CHARS = "|\\t\\r\\n"

        /**
         * Builds a note stamped through an injected [TimeSource]
         * (deterministic time seam; product code never reads the wall
         * clock directly). Validates like [toLine] and throws
         * IllegalArgumentException on invalid content.
         */
        fun record(
            documentId: String,
            mode: ScanMode,
            pagesProduced: Int,
            splitApplied: Boolean,
            selectionConfidence: Double,
            timeSource: TimeSource,
        ): ModeNote {
            val note = ModeNote(
                documentId = documentId,
                modeId = mode.modeId,
                appliedAtMillis = timeSource.nowMillis(),
                pagesProduced = pagesProduced,
                splitApplied = splitApplied,
                selectionConfidence = selectionConfidence,
            )
            note.toLine()
            return note
        }

        /**
         * Parses a serialized line back into a [ModeNote]. Returns null
         * (never throws) when the line is malformed: wrong column count,
         * forbidden characters in a string field, a non-numeric timestamp
         * or page count, a boolean other than "true"/"false", or a
         * non-finite confidence.
         */
        fun parse(line: String): ModeNote? {
            if (line.isEmpty()) return null
            // Line-level guard: a serialized note is ONE line — any
            // newline, carriage return or tab anywhere in the raw input
            // is injection and rejected before column parsing (numeric
            // parsers would otherwise silently trim trailing whitespace).
            for (ch in line) {
                if (ch == '\n') return null
                if (ch == '\r') return null
                if (ch == '\t') return null
            }
            val columns = line.split(SEPARATOR)
            if (columns.size != 6) return null
            val documentId = columns[0]
            val modeId = columns[1]
            if (!isValidTextField(documentId)) return null
            if (!isValidTextField(modeId)) return null
            val appliedAtMillis = columns[2].toLongOrNull() ?: return null
            val pagesProduced = columns[3].toIntOrNull() ?: return null
            val splitApplied = when (columns[4]) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val confidence = columns[5].toDoubleOrNull() ?: return null
            if (!confidence.isFinite()) return null
            return ModeNote(
                documentId = documentId,
                modeId = modeId,
                appliedAtMillis = appliedAtMillis,
                pagesProduced = pagesProduced,
                splitApplied = splitApplied,
                selectionConfidence = confidence,
            )
        }

        private fun isValidTextField(value: String): Boolean {
            if (value.isEmpty()) return false
            for (ch in value) {
                if (ch == '|') return false
                if (ch == '\t') return false
                if (ch == '\r') return false
                if (ch == '\n') return false
            }
            return true
        }

        private fun validateTextField(value: String, fieldName: String) {
            if (!isValidTextField(value)) {
                throw IllegalArgumentException(
                    fieldName + " contains a forbidden character " +
                        "(pipe, tab, carriage return or newline) or is empty",
                )
            }
        }
    }
}
