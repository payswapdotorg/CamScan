package org.payswap.camscan.tools.protection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Rc4 tests (CAMSCAN-PROD-011 section 6.6): the two public known-answer
// vectors from the RC4 literature (the famous "Key"/"Plaintext" and
// "Wiki"/"pedia" test vectors published with the algorithm's analysis),
// symmetry, length preservation, and key-length behavior.

class Rc4Test {

    private fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val hi = Character.digit(text[2 * index], 16)
            val lo = Character.digit(text[2 * index + 1], 16)
            out[index] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray): String {
        val HEX = "0123456789abcdef"
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            chars[2 * index] = HEX[value ushr 4]
            chars[2 * index + 1] = HEX[value and 0x0F]
        }
        return String(chars)
    }

    @Test
    fun knownAnswerVector_keyPlaintext() {
        // Public vector: RC4("Key", "Plaintext") = BBF316E8D940AF0AD3.
        val cipher = Rc4.crypt("Key".toByteArray(Charsets.US_ASCII), "Plaintext".toByteArray(Charsets.US_ASCII))
        assertEquals("bbf316e8d940af0ad3", toHex(cipher))
    }

    @Test
    fun knownAnswerVector_wikiPedia() {
        // Public vector: RC4("Wiki", "pedia") = 1021BF0420.
        val cipher = Rc4.crypt("Wiki".toByteArray(Charsets.US_ASCII), "pedia".toByteArray(Charsets.US_ASCII))
        assertEquals("1021bf0420", toHex(cipher))
    }

    @Test
    fun crypt_isSymmetric_roundTrip() {
        val key = hex("0123456789abcdef")
        val data = "The quick brown fox jumps over the lazy dog".toByteArray(Charsets.US_ASCII)
        val cipher = Rc4.crypt(key, data)
        val plain = Rc4.crypt(key, cipher)
        assertArrayEquals(data, plain)
    }

    @Test
    fun crypt_preservesLength() {
        val key = byteArrayOf(1, 2, 3)
        for (size in listOf(0, 1, 2, 16, 64, 255, 1000)) {
            val data = ByteArray(size) { index -> (index * 31 and 0xFF).toByte() }
            assertEquals(size, Rc4.crypt(key, data).size)
        }
    }

    @Test
    fun crypt_emptyDataIsEmpty() {
        assertEquals(0, Rc4.crypt(byteArrayOf(9), ByteArray(0)).size)
    }

    @Test
    fun crypt_rejectsEmptyKey() {
        try {
            Rc4.crypt(ByteArray(0), byteArrayOf(1))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("key"))
        }
    }

    @Test
    fun crypt_keyLongerThan256Bytes_wrapsAround() {
        // The KSA indexes key[i mod keyLen]; a 300-byte key must be legal.
        val key = ByteArray(300) { index -> (index * 7 and 0xFF).toByte() }
        val data = ByteArray(64) { index -> (index and 0xFF).toByte() }
        assertArrayEquals(data, Rc4.crypt(key, Rc4.crypt(key, data)))
    }

    @Test
    fun crypt_differentKeysDifferentStreams() {
        val data = ByteArray(32) { index -> (index and 0xFF).toByte() }
        val a = Rc4.crypt(byteArrayOf(1), data)
        val b = Rc4.crypt(byteArrayOf(2), data)
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun crypt_isDeterministic() {
        val key = hex("deadbeef")
        val data = hex("00112233445566778899aabbccddeeff")
        assertArrayEquals(Rc4.crypt(key, data), Rc4.crypt(key, data))
    }

    @Test
    fun crypt_stringKeyOverload_matchesBytes() {
        val data = "Plaintext".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(
            Rc4.crypt("Key".toByteArray(Charsets.US_ASCII), data),
            Rc4.crypt("Key", data),
        )
    }
}
