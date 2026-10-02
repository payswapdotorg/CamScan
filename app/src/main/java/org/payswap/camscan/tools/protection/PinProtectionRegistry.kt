package org.payswap.camscan.tools.protection

import java.security.MessageDigest

// PinProtectionRegistry (CAMSCAN-PROD-011 section 6.5): documentId ->
// PinRecord(saltHex, sha256Hex) with a salted SHA-256 pin hash.
//
// Salt discipline (documented, binding): the registry NEVER generates
// salt itself. Salt arrives either through the injected [SaltSource] seam
// (register) or explicitly (restore, persistence wiring). Tests inject a
// deterministic source; PRODUCTION must inject a source backed by a
// cryptographically secure random generator (e.g. SecureRandom) - the
// delivery notes state this plainly.
//
// Threat model (documented, honest): a single salted SHA-256 pass resists
// rainbow tables and direct hash lookups, but SHA-256 is FAST; an
// attacker who obtains the persisted registry can brute-force short PINs
// offline. Mitigations belong to product policy (minimum PIN length) and
// possible future hardening (iterated/PBKDF2 hashing). This order
// specifies exactly salted SHA-256 and ships exactly that.
//
// verify() discipline (constant work, no early-exit timing signal): for
// BOTH known and unknown document ids the method performs one SHA-256
// digest over a fixed-length input (salt of exactly SALT_LENGTH bytes -
// the stored salt, or a zero salt for unknown documents) and one
// fixed-length 64-character hex comparison via [constantTimeEquals]; the
// result is computed from the accumulated XOR difference only at the very
// end, so control flow is independent of both the pin and the outcome.

/** Seam that supplies fresh salt bytes; production injects SecureRandom. */
fun interface SaltSource {
    fun newSalt(): ByteArray
}

/** One persisted PIN record: hex-encoded salt plus hex-encoded digest. */
class PinRecord(
    val saltHex: String,
    val sha256Hex: String,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinRecord) return false
        return saltHex == other.saltHex && sha256Hex == other.sha256Hex
    }

    override fun hashCode(): Int = 31 * saltHex.hashCode() + sha256Hex.hashCode()

    override fun toString(): String =
        "PinRecord[salt=" + saltHex + ";sha256=" + sha256Hex + "]"
}

/** Flat, persistence-shaped serialization of one registry row. */
class SerializedPinRecord(
    val documentId: String,
    val saltHex: String,
    val sha256Hex: String,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerializedPinRecord) return false
        return documentId == other.documentId && saltHex == other.saltHex &&
            sha256Hex == other.sha256Hex
    }

    override fun hashCode(): Int = 31 * (31 * documentId.hashCode() + saltHex.hashCode()) + sha256Hex.hashCode()

    override fun toString(): String =
        "SerializedPinRecord[doc=" + documentId + ";salt=" + saltHex + "]"
}

/** documentId -> PinRecord registry with constant-work verification. */
class PinProtectionRegistry(private val saltSource: SaltSource) {

    private val records = LinkedHashMap<String, PinRecord>()

    /** Registers a pin for [documentId], drawing salt from the seam. */
    fun register(documentId: String, pin: String): PinRecord {
        val salt = saltSource.newSalt()
        return registerWithSalt(documentId, pin, salt)
    }

    /** Registers with an explicit salt (persistence wiring, tests). */
    fun registerWithSalt(documentId: String, pin: String, salt: ByteArray): PinRecord {
        require(salt.size == SALT_LENGTH_BYTES) {
            "salt must be exactly " + SALT_LENGTH_BYTES + " bytes"
        }
        val record = PinRecord(
            toHex(salt),
            toHex(saltedSha256(salt, pin)),
        )
        records[documentId] = record
        return record
    }

    /** Verifies [userPin] against the record for [documentId]. */
    fun verify(documentId: String, userPin: String): Boolean {
        val record = records[documentId]
        val salt = if (record == null) {
            ByteArray(SALT_LENGTH_BYTES)
        } else {
            fromHex(record.saltHex)
        }
        val candidate = toHex(saltedSha256(salt, userPin))
        val expected = record?.sha256Hex ?: ZERO_HASH_HEX
        return constantTimeEquals(candidate, expected)
    }

    /** Current policy for [documentId]. */
    fun policyFor(documentId: String): DocumentProtectionPolicy {
        val record = records[documentId] ?: return DocumentProtectionPolicy.Unprotected
        return if (record.sha256Hex == ZERO_HASH_HEX && record.saltHex == ZERO_HASH_HEX) {
            DocumentProtectionPolicy.Unprotected
        } else {
            DocumentProtectionPolicy.PinRequired(documentId)
        }
    }

    /** Removes the record for [documentId]; true when it existed. */
    fun remove(documentId: String): Boolean = records.remove(documentId) != null

    /** The record for [documentId], or null. */
    fun recordFor(documentId: String): PinRecord? = records[documentId]

    /** Flat serialization for future storage wiring (deterministic order). */
    fun toSerializableForm(): List<SerializedPinRecord> {
        val out = ArrayList<SerializedPinRecord>(records.size)
        for ((documentId, record) in records) {
            out.add(SerializedPinRecord(documentId, record.saltHex, record.sha256Hex))
        }
        return out
    }

    val size: Int get() = records.size

    companion object {
        /** Fixed salt length (documented contract of the record shape). */
        const val SALT_LENGTH_BYTES: Int = 16

        /** 64 hex zero chars - the comparison target for unknown docs. */
        internal const val ZERO_HASH_HEX: String =
            "0000000000000000000000000000000000000000000000000000000000000000"

        /** Rebuilds a registry from serialized rows. */
        fun fromSerializableForm(
            rows: List<SerializedPinRecord>,
            saltSource: SaltSource,
        ): PinProtectionRegistry {
            val registry = PinProtectionRegistry(saltSource)
            for (row in rows) {
                registry.records[row.documentId] = PinRecord(row.saltHex, row.sha256Hex)
            }
            return registry
        }

        /** SHA-256(salt + pin UTF-8 bytes) as raw bytes. */
        internal fun saltedSha256(salt: ByteArray, pin: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update(pin.toByteArray(Charsets.UTF_8))
            return digest.digest()
        }

        /** Length-safe constant-work hex comparison; no early exit. */
        internal fun constantTimeEquals(a: String, b: String): Boolean {
            val left = a.toByteArray(Charsets.US_ASCII)
            val right = b.toByteArray(Charsets.US_ASCII)
            var diff = left.size xor right.size
            val count = Math.max(left.size, right.size)
            for (index in 0 until count) {
                val l = if (index < left.size) left[index].toInt() else 0
                val r = if (index < right.size) right[index].toInt() else 0
                diff = diff or (l xor r)
            }
            return diff == 0
        }
    }
}

/** Lowercase hex encoding (used by pin records and the PDF encryptor). */
internal fun toHex(bytes: ByteArray): String {
    val HEX = "0123456789abcdef"
    val chars = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 0xFF
        chars[2 * index] = HEX[value ushr 4]
        chars[2 * index + 1] = HEX[value and 0x0F]
    }
    return String(chars)
}

/** Lowercase hex decoding; even length, hex digits only. */
internal fun fromHex(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even" }
    val out = ByteArray(hex.length / 2)
    for (index in out.indices) {
        val hi = hexDigit(hex[2 * index])
        val lo = hexDigit(hex[2 * index + 1])
        out[index] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun hexDigit(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: " + ch)
}
