package org.payswap.camscan.tools.protection

import java.security.MessageDigest

// StandardSecurity (CAMSCAN-PROD-011 section 6.5): the pure-Kotlin
// implementation of the PDF standard security handler's MD5-based key
// algorithms for RC4 40-bit (V=1, R=2) and RC4 128-bit (V=2, R=3).
//
// The algorithm numbering follows the classic PDF security section
// numbering: Algorithm 2 = computing the encryption key, Algorithm 3 =
// computing the O (owner password) value, Algorithm 4 = computing the U
// (user password) value for R=2, Algorithm 5 = computing U for R>=3 (and
// its validation logic). Every step below is derived from the public
// algorithm descriptions in the PDF specification - no proprietary code.
//
// AES-256 / R6 / AESV2 are EXPLICITLY OUT OF SCOPE for this delivery.
//
// Determinism note: the last 16 bytes of the R=3 U value are "arbitrary
// padding" per the algorithm; this implementation uses 16 zero bytes so
// that fixed inputs always produce byte-identical encrypted documents.

/** MD5-based key derivation for the standard security handler (RC4). */
internal object StandardSecurity {

    /** The standard 32-byte password padding string. */
    val PADDING: ByteArray = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
    )

    /** Pads (or truncates) [password] to exactly 32 bytes. */
    fun padPassword(password: ByteArray): ByteArray {
        val out = PADDING.copyOf()
        val count = Math.min(password.size, 32)
        System.arraycopy(password, 0, out, 0, count)
        return out
    }

     // Algorithm 3: the O value.
     // MD5 over the padded owner password; R>=3 refines 50x over the first
     // n bytes; RC4-encrypt the padded user password with the first n bytes
     // of the hash; R>=3 additionally re-encrypts 19 times with key bytes
     // XORed with the round index.
     // /
    fun computeOwnerEntry(
        ownerPassword: ByteArray,
        userPassword: ByteArray,
        keyLengthBytes: Int,
        revision: Int,
    ): ByteArray {
        var hash = md5(padPassword(ownerPassword))
        if (revision >= 3) {
            repeat(REFINEMENT_ROUNDS) {
                hash = md5(hash.copyOf(keyLengthBytes))
            }
        }
        val rc4Key = hash.copyOf(keyLengthBytes)
        var ownerEntry = Rc4.crypt(rc4Key, padPassword(userPassword))
        if (revision >= 3) {
            for (round in 1..KEY_VARIANT_ROUNDS) {
                val variantKey = xorKeyWithRound(rc4Key, round)
                ownerEntry = Rc4.crypt(variantKey, ownerEntry)
            }
        }
        return ownerEntry
    }

     // Algorithm 2: the encryption key.
     // MD5 over padded user password + O (32 bytes) + P as 4-byte
     // little-endian + ID[0]; R>=3 refines 50x over the first n bytes; the
     // key is the first n bytes of the final hash.
     // /
    fun computeEncryptionKey(
        userPassword: ByteArray,
        ownerEntry: ByteArray,
        pValue: Int,
        idFirst: ByteArray,
        keyLengthBytes: Int,
        revision: Int,
    ): ByteArray {
        require(ownerEntry.size == 32) { "owner entry must be 32 bytes" }
        val input = ByteArray(32 + 32 + 4 + idFirst.size)
        System.arraycopy(padPassword(userPassword), 0, input, 0, 32)
        System.arraycopy(ownerEntry, 0, input, 32, 32)
        input[64] = (pValue and 0xFF).toByte()
        input[65] = ((pValue ushr 8) and 0xFF).toByte()
        input[66] = ((pValue ushr 16) and 0xFF).toByte()
        input[67] = ((pValue ushr 24) and 0xFF).toByte()
        System.arraycopy(idFirst, 0, input, 68, idFirst.size)

        var hash = md5(input)
        if (revision >= 3) {
            repeat(REFINEMENT_ROUNDS) {
                hash = md5(hash.copyOf(keyLengthBytes))
            }
        }
        return hash.copyOf(keyLengthBytes)
    }

    /** Algorithm 4: the U value for revision 2 (RC4 of the padding). */
    fun computeUserEntryR2(encryptionKey: ByteArray): ByteArray =
        Rc4.crypt(encryptionKey, PADDING.copyOf())

     // Algorithm 5: the U value for revision 3.
     // MD5 over padding + ID[0], RC4 with the key, then 19 more RC4 passes
     // with key bytes XORed with the round index; the trailing 16 bytes are
     // fixed zeros here (documented determinism choice).
     // /
    fun computeUserEntryR3(encryptionKey: ByteArray, idFirst: ByteArray): ByteArray {
        val input = ByteArray(32 + idFirst.size)
        System.arraycopy(PADDING, 0, input, 0, 32)
        System.arraycopy(idFirst, 0, input, 32, idFirst.size)
        var value = Rc4.crypt(encryptionKey, md5(input))
        for (round in 1..KEY_VARIANT_ROUNDS) {
            val variantKey = xorKeyWithRound(encryptionKey, round)
            value = Rc4.crypt(variantKey, value)
        }
        val out = ByteArray(32)
        System.arraycopy(value, 0, out, 0, 16)
        return out
    }

     // Validation of a U entry: R=2 compares the full 32 bytes; R>=3
     // compares only the first 16 bytes (the trailing 16 are arbitrary).
     // /
    fun validateUserEntry(
        userEntry: ByteArray,
        encryptionKey: ByteArray,
        idFirst: ByteArray,
        revision: Int,
    ): Boolean {
        require(userEntry.size == 32) { "user entry must be 32 bytes" }
        return if (revision == 2) {
            fixedEquals(userEntry, computeUserEntryR2(encryptionKey))
        } else {
            val expected = computeUserEntryR3(encryptionKey, idFirst)
            var diff = 0
            for (index in 0 until 16) {
                diff = diff or (userEntry[index].toInt() xor expected[index].toInt())
            }
            diff == 0
        }
    }

     // Per-object key: MD5 over (base key + object number as 3-byte
     // little-endian + generation as 2-byte little-endian), truncated to
     // min(n + 5, 16) bytes. Generation is always 0 for this writer.
     // /
    fun computeObjectKey(
        baseKey: ByteArray,
        objectNumber: Int,
        generation: Int,
    ): ByteArray {
        val input = ByteArray(baseKey.size + 5)
        System.arraycopy(baseKey, 0, input, 0, baseKey.size)
        input[baseKey.size] = (objectNumber and 0xFF).toByte()
        input[baseKey.size + 1] = ((objectNumber ushr 8) and 0xFF).toByte()
        input[baseKey.size + 2] = ((objectNumber ushr 16) and 0xFF).toByte()
        input[baseKey.size + 3] = (generation and 0xFF).toByte()
        input[baseKey.size + 4] = ((generation ushr 8) and 0xFF).toByte()
        val hash = md5(input)
        val length = Math.min(baseKey.size + 5, 16)
        return hash.copyOf(length)
    }

    /** MD5 digest via java.security.MessageDigest (allowed primitive). */
    fun md5(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(bytes)

    private fun xorKeyWithRound(key: ByteArray, round: Int): ByteArray {
        val out = ByteArray(key.size)
        for (index in key.indices) {
            out[index] = (key[index].toInt() xor round).toByte()
        }
        return out
    }

    private fun fixedEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        val count = Math.max(a.size, b.size)
        for (index in 0 until count) {
            val l = if (index < a.size) a[index].toInt() else 0
            val r = if (index < b.size) b[index].toInt() else 0
            diff = diff or (l xor r)
        }
        return diff == 0
    }

    private const val REFINEMENT_ROUNDS = 50
    private const val KEY_VARIANT_ROUNDS = 19
}
