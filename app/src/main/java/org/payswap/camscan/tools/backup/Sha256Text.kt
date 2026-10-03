package org.payswap.camscan.tools.backup

import java.security.MessageDigest

// CAMSCAN-PROD-015 §6.1 — Sha256Text: the content hashing + hex-validation
// helpers shared by the backup engines. Lowercase hex only; built with an
// explicit character table (no string templates anywhere in this tree).

/** SHA-256 hashing of archive member content, as lowercase hex text. */
object Sha256Text {

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'a', 'b', 'c', 'd', 'e', 'f',
    )

    /** Computes the SHA-256 of the given bytes as 64 lowercase hex chars. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val value = b.toInt() and 0xFF
            sb.append(HEX_CHARS[value ushr 4])
            sb.append(HEX_CHARS[value and 0x0F])
        }
        return sb.toString()
    }

    /** True when the text is exactly 64 characters of lowercase hex. */
    fun isValidHex(text: String): Boolean {
        if (text.length != 64) return false
        for (ch in text) {
            if (ch < '0' || ch > '9') {
                if (ch < 'a' || ch > 'f') return false
            }
        }
        return true
    }
}
