package org.payswap.camscan.tools.conversion

// CAMSCAN-PROD-015 §6.2 — deterministic file-name sanitation shared by
// the Office adapters. Documented rules (tested):
//   1. trim leading/trailing whitespace;
//   2. replace every forbidden character with a hyphen "-" — the
//      forbidden set is: slash, backslash, colon, star, question mark,
//      double quote, less-than, greater-than, pipe, and every character
//      with code < 32;
//   3. if the result is empty, fall back to the documented default
//      "document".
// Spaces are PRESERVED (they are legal in export file names).

/** Deterministic export file-name sanitation for the Office adapters. */
object ConversionNames {

    /** Fallback base name when a title sanitizes to nothing. */
    const val DEFAULT_BASE_NAME = "document"

    /** Applies the documented three-rule sanitizer to a document title. */
    fun sanitizeFileName(title: String): String {
        var result = title.trim()
        if (result.isEmpty()) return DEFAULT_BASE_NAME
        val sb = StringBuilder(result.length)
        for (ch in result) {
            if (isForbiddenFileNameChar(ch)) {
                sb.append('-')
            } else {
                sb.append(ch)
            }
        }
        result = sb.toString()
        if (result.isEmpty()) return DEFAULT_BASE_NAME
        return result
    }

    /** True for the documented forbidden set (see object comment). */
    fun isForbiddenFileNameChar(ch: Char): Boolean {
        if (ch.code < 32) return true
        if (ch == '/') return true
        if (ch.code == 92) return true
        if (ch == ':') return true
        if (ch == '*') return true
        if (ch == '?') return true
        if (ch == '"') return true
        if (ch == '<') return true
        if (ch == '>') return true
        if (ch == '|') return true
        return false
    }
}
