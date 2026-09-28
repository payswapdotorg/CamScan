package org.payswap.camscan.ocr.util

import java.security.MessageDigest

/**

SHA-256 + hex + byte-encoding helpers shared by the deterministic stub

engine and the OCR harness. JDK MessageDigest only — no crypto dependencies.

Every function here is pure and deterministic.
*/
internal object Digests {

/** SHA-256 over the given chunks, concatenated in order. */
fun sha256(vararg chunks: ByteArray): ByteArray {
val digest = MessageDigest.getInstance("SHA-256")
for (chunk in chunks) digest.update(chunk)
return digest.digest()
}

/** Lowercase hex of the full byte array. */
fun hex(bytes: ByteArray): String {
val out = StringBuilder(bytes.size * 2)
for (b in bytes) {
val v = b.toInt() and 0xFF
out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
}
return out.toString()
}

/** UTF-8 bytes of [value]. */
fun utf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

/** Big-endian 4-byte encoding of [value] (length framing for domain separation). */
fun intBytes(value: Int): ByteArray = byteArrayOf(
(value ushr 24).toByte(),
(value ushr 16).toByte(),
(value ushr 8).toByte(),
value.toByte(),
)

private const val HEX = "0123456789abcdef"

}
