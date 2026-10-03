package org.payswap.camscan.tools.conversion

// CAMSCAN-PROD-015 §6.2 — minimal XML text escaping for the transit
// protocol used by both OOXML adapters. Documented rule: ONLY the three
// XML-mandatory characters are escaped — ampersand, less-than and
// greater-than. Quotes and apostrophes are never escaped (they appear
// only inside attribute values the adapters build themselves, never
// inside injected text). XML is built with EXPLICIT string
// concatenation; no string templates exist anywhere in this tree.

/** Minimal XML text escaping for adapter-generated OOXML. */
object ConversionXml {

    /** Escapes the three mandatory characters: ampersand, less, greater. */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
