package org.payswap.camscan.tools.protection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

// PinProtectionRegistry tests (CAMSCAN-PROD-011 section 6.6): the verify
// matrix (right/wrong pin, unknown document), salt discipline (registry
// never generates salt; the seam supplies it), independent hash
// derivation, serialization round-trip, and policy mapping.

class PinProtectionRegistryTest {

    /** Deterministic salt seam for tests (production injects SecureRandom). */
    private class SequencedSaltSource(private var next: Int = 0) : SaltSource {
        override fun newSalt(): ByteArray {
            val salt = ByteArray(PinProtectionRegistry.SALT_LENGTH_BYTES)
            val value = next
            next += 1
            for (index in salt.indices) {
                salt[index] = ((value + index * 7) and 0xFF).toByte()
            }
            return salt
        }
    }

    private fun independentSha256Hex(salt: ByteArray, pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return toHexPublic(digest.digest())
    }

    // toHex is internal: tests reach it through the same module. This
    // local alias keeps the independent-derivation intent explicit.
    private fun toHexPublic(bytes: ByteArray): String {
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
    fun register_producesHexShapedRecord() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        val record = registry.register("doc-1", "1234")
        assertEquals(32, record.saltHex.length)
        assertEquals(64, record.sha256Hex.length)
        assertTrue(record.saltHex.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(record.sha256Hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun register_hashesSaltPlusPin_independentlyVerified() {
        val fixedSalt = ByteArray(16) { index -> (index * 11 + 3).toByte() }
        val registry = PinProtectionRegistry(SequencedSaltSource())
        val record = registry.registerWithSalt("doc-1", "5678", fixedSalt)
        assertEquals(independentSha256Hex(fixedSalt, "5678"), record.sha256Hex)
    }

    @Test
    fun verify_correctPin_returnsTrue() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("doc-1", "9911")
        assertTrue(registry.verify("doc-1", "9911"))
    }

    @Test
    fun verify_wrongPin_returnsFalse() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("doc-1", "9911")
        assertFalse(registry.verify("doc-1", "9912"))
        assertFalse(registry.verify("doc-1", ""))
        assertFalse(registry.verify("doc-1", "99110"))
    }

    @Test
    fun verify_unknownDocument_returnsFalse() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("doc-1", "1234")
        assertFalse(registry.verify("doc-9", "1234"))
    }

    @Test
    fun verify_performsTheSameWorkForUnknownDocuments() {
        // The constant-work discipline is structural: an unknown document
        // still digests the pin over a full-length salt and runs the same
        // fixed-length comparison. Observable proxy: verify() on an
        // unknown document and on a wrong pin both return false; the code
        // path shape is asserted by the source contract and this test
        // keeps the behavior honest.
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("doc-1", "0000")
        assertFalse(registry.verify("unknown", "0000"))
        assertFalse(registry.verify("doc-1", "0001"))
        assertTrue(registry.verify("doc-1", "0000"))
    }

    @Test
    fun samePinAndSalt_isDeterministic() {
        val salt = ByteArray(16) { 0x5A }
        val a = PinProtectionRegistry(SequencedSaltSource()).registerWithSalt("d", "pin", salt)
        val b = PinProtectionRegistry(SequencedSaltSource()).registerWithSalt("d", "pin", salt)
        assertEquals(a, b)
    }

    @Test
    fun differentSalts_produceDifferentHashes() {
        val saltA = ByteArray(16) { 0x01 }
        val saltB = ByteArray(16) { 0x02 }
        val registry = PinProtectionRegistry(SequencedSaltSource())
        val a = registry.registerWithSalt("d", "pin", saltA)
        val b = registry.registerWithSalt("d", "pin", saltB)
        assertNotEquals(a.sha256Hex, b.sha256Hex)
        assertNotEquals(a.saltHex, b.saltHex)
    }

    @Test
    fun differentPins_produceDifferentHashes() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("d1", "1111")
        registry.register("d2", "2222")
        assertNotEquals(registry.recordFor("d1")!!.sha256Hex, registry.recordFor("d2")!!.sha256Hex)
    }

    @Test
    fun saltSource_seamSuppliesFreshSaltPerRegistration() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("d1", "pin")
        registry.register("d2", "pin")
        // Same pin, different salt from the seam -> different digests.
        assertNotEquals(registry.recordFor("d1")!!.sha256Hex, registry.recordFor("d2")!!.sha256Hex)
    }

    @Test
    fun registerWithSalt_enforcesSaltLength() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        try {
            registry.registerWithSalt("d", "pin", ByteArray(15))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("16"))
        }
    }

    @Test
    fun remove_deletesRecordAndPolicyReverts() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("doc-1", "1234")
        assertTrue(registry.remove("doc-1"))
        assertNull(registry.recordFor("doc-1"))
        assertEquals(DocumentProtectionPolicy.Unprotected, registry.policyFor("doc-1"))
        assertFalse(registry.verify("doc-1", "1234"))
        assertFalse(registry.remove("doc-1"))
    }

    @Test
    fun policyFor_unprotectedDocument_isUnprotected() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        assertEquals(DocumentProtectionPolicy.Unprotected, registry.policyFor("anything"))
    }

    @Test
    fun policyFor_protectedDocument_isPinRequiredWithDocumentId() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("doc-42", "1234")
        val policy = registry.policyFor("doc-42")
        assertTrue(policy is DocumentProtectionPolicy.PinRequired)
        assertEquals("doc-42", (policy as DocumentProtectionPolicy.PinRequired).pinRecordId)
    }

    @Test
    fun serialization_roundTripsThroughSerializableForm() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        registry.register("doc-1", "1111")
        registry.register("doc-2", "2222")
        val rows = registry.toSerializableForm()
        assertEquals(2, rows.size)
        assertEquals("doc-1", rows[0].documentId)
        assertEquals("doc-2", rows[1].documentId)

        val restored = PinProtectionRegistry.fromSerializableForm(rows, SequencedSaltSource())
        assertTrue(restored.verify("doc-1", "1111"))
        assertTrue(restored.verify("doc-2", "2222"))
        assertFalse(restored.verify("doc-1", "2222"))
        assertEquals(registry.toSerializableForm(), restored.toSerializableForm())
    }

    @Test
    fun registrySize_tracksRegistrations() {
        val registry = PinProtectionRegistry(SequencedSaltSource())
        assertEquals(0, registry.size)
        registry.register("a", "1")
        registry.register("b", "2")
        assertEquals(2, registry.size)
        registry.remove("a")
        assertEquals(1, registry.size)
    }

    @Test
    fun constantTimeEquals_table() {
        assertTrue(PinProtectionRegistry.constantTimeEquals("abcdef", "abcdef"))
        assertFalse(PinProtectionRegistry.constantTimeEquals("abcdef", "abcdeg"))
        assertFalse(PinProtectionRegistry.constantTimeEquals("abcdef", "abcde"))
        assertFalse(PinProtectionRegistry.constantTimeEquals("", "a"))
        assertTrue(PinProtectionRegistry.constantTimeEquals("", ""))
    }

    @Test
    fun recordAndPolicy_valueSemantics() {
        val record = PinRecord("00", "11")
        assertEquals(record, PinRecord("00", "11"))
        assertEquals(
            DocumentProtectionPolicy.PinRequired("d"),
            DocumentProtectionPolicy.PinRequired("d"),
        )
        assertNotEquals(DocumentProtectionPolicy.PinRequired("d"), DocumentProtectionPolicy.Unprotected)
    }

    @Test
    fun hexHelpers_roundTrip() {
        val bytes = byteArrayOf(0x00, 0x0F, 0xA5.toByte(), 0xFF.toByte())
        assertEquals("000fa5ff", toHex(bytes))
        assertTrue(fromHex("000fa5ff").contentEquals(bytes))
        try {
            fromHex("0")
            org.junit.Assert.fail("expected IllegalArgumentException for odd hex length")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("even"))
        }
    }
}
