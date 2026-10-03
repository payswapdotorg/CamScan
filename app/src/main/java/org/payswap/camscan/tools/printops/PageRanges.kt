package org.payswap.camscan.tools.printops

// CAMSCAN-PROD-015 §6.3 — PageRanges: the STRICT page-range expression
// parser of the print planner. Accepted forms (documented):
//   "all"          — the entire string only; expands to 1..pageCount;
//   "3"            — a single page number (digits only, 1-based);
//   "1-3,5"        — comma list of singles and inclusive a-b ranges.
// Strictness rules: the expression is non-blank; each comma token is
// TRIMMED but must contain no internal whitespace; numbers are digits
// only (no sign, no "+", no leading spaces); a range's start must be <=
// its end; every referenced page must satisfy 1 <= page <= pageCount;
// "all" may not be combined with anything. Duplicates ARE allowed and
// preserved; the output is sorted ASCENDING. Anything else is invalid
// (null). Digit strings longer than 9 characters are rejected (overflow
// guard).

/** Strict parser of print page-range expressions. */
object PageRanges {

    /** The whole-document keyword. */
    const val ALL_KEYWORD = "all"

    /** Parses a page-range expression against pageCount pages. Returns the selected page numbers (1-based, ascending, duplicates preserved) or null when the expression is invalid. */
    fun parse(expression: String, pageCount: Int): List<Int>? {
        if (pageCount < 1) return null
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == ALL_KEYWORD) {
            return (1..pageCount).toList()
        }
        if (trimmed.contains(ALL_KEYWORD)) return null
        val selected = mutableListOf<Int>()
        val tokens = trimmed.split(",")
        for (token in tokens) {
            val trimmedToken = token.trim()
            if (trimmedToken.isEmpty()) return null
            val rangeSplit = trimmedToken.split("-")
            if (rangeSplit.size == 1) {
                val page = parsePageNumber(rangeSplit[0]) ?: return null
                if (page < 1 || page > pageCount) return null
                selected.add(page)
            } else if (rangeSplit.size == 2) {
                val start = parsePageNumber(rangeSplit[0]) ?: return null
                val end = parsePageNumber(rangeSplit[1]) ?: return null
                if (start > end) return null
                if (start < 1 || end > pageCount) return null
                for (page in start..end) {
                    selected.add(page)
                }
            } else {
                return null
            }
        }
        return selected.sorted()
    }

    private fun parsePageNumber(text: String): Int? {
        if (text.isEmpty()) return null
        if (text.length > 9) return null
        for (ch in text) {
            if (ch < '0' || ch > '9') return null
        }
        return text.toIntOrNull()
    }
}
