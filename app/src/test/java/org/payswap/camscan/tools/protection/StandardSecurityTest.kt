package org.payswap.camscan.tools.protection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

// StandardSecurity tests (CAMSCAN-PROD-011 section 6.6): public MD5/SHA
// vectors, the 32-byte padding constant, password padding, and the
// algorithm SHAPES. Where expectations are not public constants they are
// derived by an independent in-test reimplementation that shares only the
// public primitives (MessageDigest, Rc4) - a differential check of the
// production path against the published algorithm steps.

class StandardSecurityTest {

    private fun md5Local(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(bytes)

    private fun md5Hex(text: String): String {
        val HEX = "0123456789abcdef"
        val digest = md5Local(text.toByteArray(Charsets.US_ASCII))
        val chars = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 0xFF
            chars[2 * index] = HEX[value ushr 4]
            chars[2 * index + 1] = HEX[value and 0x0F]
        }
        return String(chars)
    }

    private val idFirst = ByteArray(16) { index -> (index + 1).toByte() }

    @Test
    fun md5_publicVector_empty() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
    }

    @Test
    fun md5_publicVector_abc() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
    }

    @Test
    fun md5_publicVector_quickBrownFox() {
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", md5Hex("The quick brown fox jumps over the lazy dog"))
    }

    @Test
    fun padding_isExactly32StandardBytes() {
        assertEquals(32, StandardSecurity.PADDING.size)
        val expected = intArrayOf(
            0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41,
            0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80,
            0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
        )
        for (index in expected.indices) {
            assertEquals("padding byte " + index, expected[index], StandardSecurity.PADDING[index].toInt() and 0xFF)
        }
    }

    @Test
    fun padPassword_emptyPassword_isPurePadding() {
        assertArrayEquals(StandardSecurity.PADDING, StandardSecurity.padPassword(ByteArray(0)))
    }

    @Test
    fun padPassword_shortPassword_padsWithTheStandardString() {
        val padded = StandardSecurity.padPassword("abc".toByteArray(Charsets.US_ASCII))
        assertEquals(32, padded.size)
        assertEquals('a'.code.toByte(), padded[0])
        assertEquals('b'.code.toByte(), padded[1])
        assertEquals('c'.code.toByte(), padded[2])
        assertEquals(StandardSecurity.PADDING[3], padded[3])
        assertEquals(StandardSecurity.PADDING[31], padded[31])
    }

    @Test
    fun padPassword_longPassword_truncatesTo32() {
        val long = ByteArray(40) { index -> ('a' + index % 26).code.toByte() }
        val padded = StandardSecurity.padPassword(long)
        assertEquals(32, padded.size)
        for (index in 0 until 32) {
            assertEquals(long[index], padded[index])
        }
    }

    // ------------------------------------------------------------------
    // Algorithm 3 (O value)
    // ------------------------------------------------------------------

    @Test
    fun algorithm3_revision2_matchesIndependentRecomputation() {
        // R=2: O = RC4(MD5(pad(owner))[:5], pad(user)).
        val owner = "owner-secret".toByteArray(Charsets.US_ASCII)
        val user = "user-secret".toByteArray(Charsets.US_ASCII)
        val produced = StandardSecurity.computeOwnerEntry(owner, user, 5, 2)
        val expected = Rc4.crypt(
            md5Local(StandardSecurity.padPassword(owner)).copyOf(5),
            StandardSecurity.padPassword(user),
        )
        assertArrayEquals(expected, produced)
        assertEquals(32, produced.size)
    }

    @Test
    fun algorithm3_revision3_appliesRefinementAndVariantRounds() {
        val owner = "owner-secret".toByteArray(Charsets.US_ASCII)
        val user = "user-secret".toByteArray(Charsets.US_ASCII)
        val produced = StandardSecurity.computeOwnerEntry(owner, user, 16, 3)
        // Independent recomputation: 50x MD5 refinement over the first 16
        // bytes, then RC4 + 19 key-variant rounds.
        var hash = md5Local(StandardSecurity.padPassword(owner))
        repeat(50) { hash = md5Local(hash.copyOf(16)) }
        val baseKey = hash.copyOf(16)
        var expected = Rc4.crypt(baseKey, StandardSecurity.padPassword(user))
        for (round in 1..19) {
            val variant = ByteArray(16) { index -> (baseKey[index].toInt() xor round).toByte() }
            expected = Rc4.crypt(variant, expected)
        }
        assertArrayEquals(expected, produced)
    }

    @Test
    fun algorithm3_revision3_differsFromRevision2Shape() {
        val owner = "o".toByteArray(Charsets.US_ASCII)
        val user = "u".toByteArray(Charsets.US_ASCII)
        val o2 = StandardSecurity.computeOwnerEntry(owner, user, 5, 2)
        // An R=3 computation with the same passwords (16-byte key) cannot
        // equal the R=2 O value: the refinement and variant rounds change it.
        val o3 = StandardSecurity.computeOwnerEntry(owner, user, 16, 3)
        assertEquals(false, o2.contentEquals(o3))
    }

    @Test
    fun algorithm3_ownerEqualsUser_isAllowed() {
        val pw = "same".toByteArray(Charsets.US_ASCII)
        assertEquals(32, StandardSecurity.computeOwnerEntry(pw, pw, 5, 2).size)
        assertEquals(32, StandardSecurity.computeOwnerEntry(pw, pw, 16, 3).size)
    }

    @Test
    fun algorithm2_rejectsBadOwnerEntryLength() {
        try {
            StandardSecurity.computeEncryptionKey(
                ByteArray(0),
                ByteArray(31),
                196,
                idFirst,
                5,
                2,
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("32 bytes"))
        }
    }

    // ------------------------------------------------------------------
    // Algorithm 2 (encryption key)
    // ------------------------------------------------------------------

    @Test
    fun algorithm2_revision2_matchesIndependentRecomputation() {
        val user = "user-secret".toByteArray(Charsets.US_ASCII)
        val owner = StandardSecurity.computeOwnerEntry(
            "owner-secret".toByteArray(Charsets.US_ASCII),
            user,
            5,
            2,
        )
        val key = StandardSecurity.computeEncryptionKey(user, owner, 196, idFirst, 5, 2)
        // Independent recomputation of the documented concatenation.
        val input = ByteArray(32 + 32 + 4 + 16)
        System.arraycopy(StandardSecurity.padPassword(user), 0, input, 0, 32)
        System.arraycopy(owner, 0, input, 32, 32)
        input[64] = 196.toByte()
        input[65] = 0
        input[66] = 0
        input[67] = 0
        System.arraycopy(idFirst, 0, input, 68, 16)
        val expected = md5Local(input).copyOf(5)
        assertArrayEquals(expected, key)
        assertEquals(5, key.size)
    }

    @Test
    fun algorithm2_revision3_applies50RoundRefinement() {
        val user = "u".toByteArray(Charsets.US_ASCII)
        val owner = StandardSecurity.computeOwnerEntry(
            "o".toByteArray(Charsets.US_ASCII),
            user,
            16,
            3,
        )
        val key = StandardSecurity.computeEncryptionKey(user, owner, 196, idFirst, 16, 3)
        assertEquals(16, key.size)
        // The refined key differs from the raw first-16-bytes of the
        // initial digest (50 additional MD5 rounds).
        val input = ByteArray(32 + 32 + 4 + 16)
        System.arraycopy(StandardSecurity.padPassword(user), 0, input, 0, 32)
        System.arraycopy(owner, 0, input, 32, 32)
        input[64] = 196.toByte()
        System.arraycopy(idFirst, 0, input, 68, 16)
        val raw = md5Local(input).copyOf(16)
        assertEquals(false, raw.contentEquals(key))
    }

    @Test
    fun algorithm2_isSensitiveToEveryInput() {
        val user = "user".toByteArray(Charsets.US_ASCII)
        val owner = StandardSecurity.computeOwnerEntry(
            "owner".toByteArray(Charsets.US_ASCII),
            user,
            5,
            2,
        )
        val base = StandardSecurity.computeEncryptionKey(user, owner, 196, idFirst, 5, 2)

        val otherId = idFirst.copyOf().also { it[0] = 0x7F }
        val otherOwner = owner.copyOf().also { it[7] = 0x11 }
        val otherUser = "userX".toByteArray(Charsets.US_ASCII)

        assertEquals(
            false,
            base.contentEquals(StandardSecurity.computeEncryptionKey(user, owner, 192, idFirst, 5, 2)),
        )
        assertEquals(
            false,
            base.contentEquals(StandardSecurity.computeEncryptionKey(user, owner, 196, otherId, 5, 2)),
        )
        assertEquals(
            false,
            base.contentEquals(StandardSecurity.computeEncryptionKey(user, otherOwner, 196, idFirst, 5, 2)),
        )
        assertEquals(
            false,
            base.contentEquals(StandardSecurity.computeEncryptionKey(otherUser, owner, 196, idFirst, 5, 2)),
        )
    }

    @Test
    fun algorithm2_emptyPasswords_usePurePadding() {
        val owner = StandardSecurity.computeOwnerEntry(ByteArray(0), ByteArray(0), 5, 2)
        val key = StandardSecurity.computeEncryptionKey(ByteArray(0), owner, 196, idFirst, 5, 2)
        assertEquals(5, key.size)
    }

    // ------------------------------------------------------------------
    // Algorithm 4 / 5 (U value) and validation
    // ------------------------------------------------------------------

    @Test
    fun algorithm4_uIsRc4OfPaddingWithTheKey() {
        val key = ByteArray(5) { index -> (index * 3).toByte() }
        val u = StandardSecurity.computeUserEntryR2(key)
        assertArrayEquals(Rc4.crypt(key, StandardSecurity.PADDING.copyOf()), u)
        assertEquals(32, u.size)
    }

    @Test
    fun algorithm5_uIsTheDocumentedChainWithZeroTail() {
        val key = ByteArray(16) { index -> (index * 5 + 1).toByte() }
        val u = StandardSecurity.computeUserEntryR3(key, idFirst)
        // Independent recomputation of the chain.
        val input = ByteArray(32 + 16)
        System.arraycopy(StandardSecurity.PADDING, 0, input, 0, 32)
        System.arraycopy(idFirst, 0, input, 32, 16)
        var expected = Rc4.crypt(key, md5Local(input))
        for (round in 1..19) {
            val variant = ByteArray(16) { index -> (key[index].toInt() xor round).toByte() }
            expected = Rc4.crypt(variant, expected)
        }
        assertEquals(32, u.size)
        for (index in 0 until 16) {
            assertEquals("U byte " + index, expected[index], u[index])
        }
        for (index in 16 until 32) {
            assertEquals("U tail byte " + index + " must be zero (documented)", 0, u[index].toInt())
        }
    }

    @Test
    fun validation_revision2_comparesFull32Bytes() {
        val key = ByteArray(5) { it.toByte() }
        val id = idFirst
        val u = StandardSecurity.computeUserEntryR2(key)
        assertTrue(StandardSecurity.validateUserEntry(u, key, id, 2))
        val tampered = u.copyOf().also { it[31] = (it[31] + 1).toByte() }
        assertEquals(false, StandardSecurity.validateUserEntry(tampered, key, id, 2))
        val otherKey = key.copyOf().also { it[0] = 0x55 }
        assertEquals(false, StandardSecurity.validateUserEntry(u, otherKey, id, 2))
    }

    @Test
    fun validation_revision3_comparesOnlyFirst16Bytes() {
        val key = ByteArray(16) { index -> (index + 7).toByte() }
        val u = StandardSecurity.computeUserEntryR3(key, idFirst)
        assertTrue(StandardSecurity.validateUserEntry(u, key, idFirst, 3))
        // The trailing 16 bytes are arbitrary per the algorithm: editing
        // them must NOT invalidate a revision-3 U entry.
        val tamperedTail = u.copyOf().also { it[20] = (it[20].toInt() xor 0x5A).toByte() }
        assertTrue(StandardSecurity.validateUserEntry(tamperedTail, key, idFirst, 3))
        // Editing the first 16 bytes must invalidate it.
        val tamperedHead = u.copyOf().also { it[3] = (it[3].toInt() xor 0x01).toByte() }
        assertEquals(false, StandardSecurity.validateUserEntry(tamperedHead, key, idFirst, 3))
        // A different ID[0] changes the expected chain.
        val otherId = idFirst.copyOf().also { it[9] = 0x33 }
        assertEquals(false, StandardSecurity.validateUserEntry(u, key, otherId, 3))
    }

    // ------------------------------------------------------------------
    // Per-object key
    // ------------------------------------------------------------------

    @Test
    fun perObjectKey_matchesSpecRule() {
        // Per the standard security handler: MD5(key + objnum LE-3 +
        // gen LE-2), truncated to min(n + 5, 16).
        val baseKey = ByteArray(16) { index -> (index * 17 + 3).toByte() }
        val objectKey = StandardSecurity.computeObjectKey(baseKey, 5, 0)
        assertEquals(16, objectKey.size)
        val input = ByteArray(16 + 5)
        System.arraycopy(baseKey, 0, input, 0, 16)
        input[16] = 5
        input[17] = 0
        input[18] = 0
        input[19] = 0
        input[20] = 0
        val expected = md5Local(input).copyOf(16)
        assertArrayEquals(expected, objectKey)
    }

    @Test
    fun perObjectKey_40BitStrength_isTenBytes() {
        val baseKey = ByteArray(5) { it.toByte() }
        assertEquals(10, StandardSecurity.computeObjectKey(baseKey, 1, 0).size)
        assertEquals(10, StandardSecurity.computeObjectKey(baseKey, 999, 0).size)
    }

    @Test
    fun perObjectKey_differsPerObjectNumber() {
        val baseKey = ByteArray(5) { 0x42 }
        val k1 = StandardSecurity.computeObjectKey(baseKey, 1, 0)
        val k2 = StandardSecurity.computeObjectKey(baseKey, 2, 0)
        assertEquals(false, k1.contentEquals(k2))
    }

    @Test
    fun perObjectKey_encodesLargeObjectNumbersInThreeLittleEndianBytes() {
        val baseKey = ByteArray(5) { 0x01 }
        // Object 65536 = 0x010000 -> LE3 bytes (0, 0, 1).
        val objectKey = StandardSecurity.computeObjectKey(baseKey, 65536, 0)
        val input = ByteArray(5 + 5)
        System.arraycopy(baseKey, 0, input, 0, 5)
        input[5] = 0
        input[6] = 0
        input[7] = 1
        input[8] = 0
        input[9] = 0
        assertArrayEquals(md5Local(input).copyOf(10), objectKey)
    }

    // ------------------------------------------------------------------
    // Strength table
    // ------------------------------------------------------------------

    @Test
    fun rc4Strength_table() {
        assertEquals(1, Rc4Strength.RC4_40.version)
        assertEquals(2, Rc4Strength.RC4_40.revision)
        assertEquals(5, Rc4Strength.RC4_40.keyLengthBytes)
        assertEquals(2, Rc4Strength.RC4_128.version)
        assertEquals(3, Rc4Strength.RC4_128.revision)
        assertEquals(16, Rc4Strength.RC4_128.keyLengthBytes)
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    @Test
    fun permissions_printingOnly_isExactly196() {
        assertEquals(196, PdfPermissions.PRINTING_ALLOWED.pValue)
        assertEquals(196, PdfPermissions(true).pValue)
    }

    @Test
    fun permissions_nothingAllowed_isExactly192() {
        assertEquals(192, PdfPermissions.NOTHING_ALLOWED.pValue)
        assertEquals(192, PdfPermissions(false).pValue)
    }

    @Test
    fun permissions_bitTableDerivation() {
        // bit 3 (value 4) = printing; bits 7-8 (64 + 128) reserved-set;
        // everything else denied -> 196 / 192.
        assertEquals(4 + 64 + 128, PdfPermissions.PRINTING_ALLOWED.pValue)
        assertEquals(64 + 128, PdfPermissions.NOTHING_ALLOWED.pValue)
    }

    @Test
    fun permissions_decodePrintingBit() {
        assertTrue(PdfPermissions.printingAllowedOf(196))
        assertTrue(PdfPermissions.printingAllowedOf(4))
        assertEquals(false, PdfPermissions.printingAllowedOf(192))
        assertEquals(false, PdfPermissions.printingAllowedOf(0))
    }

    @Test
    fun permissions_valueSemantics() {
        assertEquals(PdfPermissions(true), PdfPermissions(true))
        assertNotEquals(PdfPermissions(true), PdfPermissions(false))
        assertTrue(PdfPermissions(true).toString().contains("196"))
    }
}
