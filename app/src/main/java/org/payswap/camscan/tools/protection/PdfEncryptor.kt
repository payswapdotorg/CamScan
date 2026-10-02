package org.payswap.camscan.tools.protection

import java.security.MessageDigest

// PdfEncryptor (CAMSCAN-PROD-011 section 6.5): a pure-Kotlin PDF standard
// security handler (RC4, 40-bit and 128-bit; V=1/V=2, R=2/R=3) that
// post-processes the KNOWN deterministic PdfWriter output. The writer is
// never edited; its no-/ID trailer contract is respected by DERIVING and
// injecting /ID here.
//
// /ID derivation (documented): the base trailer deliberately carries no
// /ID (see PdfWriter.kt). The encryptor derives ID[0] as the first 16
// bytes of SHA-256(seedBytes + documentBytes) and sets ID[1] = ID[0].
// Tests inject a fixed [IdSeed]; PRODUCTION callers must inject a real
// random seed (the derivation is deterministic by design so tests and
// evidence pipelines stay reproducible - this is stated plainly in the
// delivery notes).
//
// Post-processing steps (documented):
//   1. parse the fixed layout (catalog 1, page-tree root 2, info 3, then
//      exactly 3 objects per page in document order);
//   2. derive ID[0] from the seed + the original document bytes;
//   3. Algorithm 3 -> O; Algorithm 2 -> encryption key (over the padded
//      user password + O + P little-endian + ID[0]); Algorithm 4/5 -> U;
//   4. RC4-encrypt every string and stream with the per-object key
//      (MD5(key + objnum LE-3 + gen LE-2), first min(n+5, 16) bytes):
//      the info dictionary's /Producer and /CreationDate strings (emitted
//      as hex strings), every content stream, and every DCTDecode image
//      XObject stream. /Length values are unchanged (RC4 preserves
//      length). The catalog, page-tree root and page dicts contain no
//      strings and are re-emitted verbatim;
//   5. append ONE /Encrypt dictionary as the next free object number with
//      /Filter /Standard, /V, /R, /O, /U, /P (and /Length 128 for V=2) -
//      O and U are hex strings (the /Encrypt dictionary itself is never
//      encrypted, per the standard security handler rules);
//   6. rebuild the cross-reference table with recomputed byte offsets
//      (N+2 entries including the free-list head) and write the new
//      trailer carrying /Size, /Root, /Info, /Encrypt and /ID.
//
// Output determinism: same document bytes + same passwords + same
// permissions + same seed -> byte-identical output (tested by running the
// encryption twice). The trailing 16 bytes of the R=3 U value are fixed
// zeros (documented in StandardSecurity).
//
// AES-256 / R6 / AESV2 are EXPLICITLY OUT OF SCOPE for this delivery.

/** RC4 strength selection: 40-bit (V=1, R=2) or 128-bit (V=2, R=3). */
enum class Rc4Strength(
    val version: Int,
    val revision: Int,
    val keyLengthBytes: Int,
) {
    RC4_40(1, 2, 5),
    RC4_128(2, 3, 16),
}

/** Seed seam for the /ID derivation; production injects real randomness. */
fun interface IdSeed {
    fun seedBytes(): ByteArray
}

/** Pure post-processor turning PdfWriter output into an encrypted PDF. */
object PdfEncryptor {

    /** Encrypts [pdfBytes] (PdfWriter output) with the given parameters. */
    fun encrypt(
        pdfBytes: ByteArray,
        userPassword: ByteArray,
        ownerPassword: ByteArray,
        strength: Rc4Strength,
        permissions: PdfPermissions,
        seed: IdSeed,
    ): ByteArray {
        val objects = PdfWriterLayout.parseObjects(pdfBytes)
        require((objects.size - 3) % 3 == 0) { "unexpected object count" }

        val idFirst = deriveIdFirst(seed.seedBytes(), pdfBytes)
        val n = strength.keyLengthBytes
        val revision = strength.revision

        val ownerEntry = StandardSecurity.computeOwnerEntry(
            ownerPassword, userPassword, n, revision,
        )
        val encryptionKey = StandardSecurity.computeEncryptionKey(
            userPassword, ownerEntry, permissions.pValue, idFirst, n, revision,
        )
        val userEntry = if (revision == 2) {
            StandardSecurity.computeUserEntryR2(encryptionKey)
        } else {
            StandardSecurity.computeUserEntryR3(encryptionKey, idFirst)
        }

        val out = ByteArrayBuilder()
        out.append(pdfBytes, 0, 15)

        val objectCount = objects.size
        val encryptObjectNumber = objectCount + 1
        val offsets = IntArray(encryptObjectNumber + 1)

        for (parsed in objects) {
            offsets[parsed.number] = out.size()
            when (parsed) {
                is ParsedObject.Dict -> {
                    if (parsed.number == INFO_OBJECT_NUMBER) {
                        out.append(parsed.number.toString() + " 0 obj\n")
                        val transformed = PdfWriterLayout.replaceLiteralStringsWithHex(
                            pdfBytes,
                            parsed.bodyStart,
                            parsed.end - ENDOBJ_TAIL_LENGTH,
                        ) { plain -> encryptWithObjectKey(encryptionKey, parsed.number, plain) }
                        out.append(transformed)
                        out.append("endobj\n")
                    } else {
                        out.append(pdfBytes, parsed.start, parsed.end - parsed.start)
                    }
                }
                is ParsedObject.Stream -> {
                    val objectKey = StandardSecurity.computeObjectKey(
                        encryptionKey, parsed.number, GENERATION,
                    )
                    val encrypted = Rc4.crypt(
                        objectKey,
                        slice(pdfBytes, parsed.dataStart, parsed.dataLength),
                    )
                    out.append(pdfBytes, parsed.start, parsed.dataStart - parsed.start)
                    out.append(encrypted)
                    out.append("\nendstream\n")
                    out.append("endobj\n")
                }
            }
        }

        offsets[encryptObjectNumber] = out.size()
        out.append(encryptObjectNumber.toString() + " 0 obj\n")
        out.append("<< /Filter /Standard /V " + strength.version)
        if (strength == Rc4Strength.RC4_128) {
            out.append(" /Length 128")
        }
        out.append(" /R " + revision)
        out.append(" /O <" + toHex(ownerEntry) + ">")
        out.append(" /U <" + toHex(userEntry) + ">")
        out.append(" /P " + permissions.pValue)
        out.append(" >>\nendobj\n")

        val xrefOffset = out.size()
        val entryCount = encryptObjectNumber + 1
        out.append("xref\n0 " + entryCount + "\n")
        out.append("0000000000 65535 f \n")
        for (number in 1..encryptObjectNumber) {
            out.append(offsets[number].toString().padStart(OFFSET_DIGITS, '0') + " 00000 n \n")
        }

        val idHex = toHex(idFirst)
        out.append("trailer\n<< /Size " + entryCount)
        out.append(" /Root 1 0 R /Info 3 0 R")
        out.append(" /Encrypt " + encryptObjectNumber + " 0 R")
        out.append(" /ID [<" + idHex + "> <" + idHex + ">] >>\n")
        out.append("startxref\n" + xrefOffset + "\n")
        out.append("%%EOF\n")
        return out.toByteArray()
    }

    /** ID[0] = first 16 bytes of SHA-256(seed + document bytes). */
    fun deriveIdFirst(seedBytes: ByteArray, documentBytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(seedBytes)
        digest.update(documentBytes)
        val full = digest.digest()
        return full.copyOf(16)
    }

     // Recovers the encryption key from the parameters (test/verification
     // support: Algorithm 2 over the derived ID). Exposed for the
     // encrypt-then-decrypt round-trip proof.
     // /
    fun recoverEncryptionKey(
        userPassword: ByteArray,
        ownerEntry: ByteArray,
        pValue: Int,
        idFirst: ByteArray,
        strength: Rc4Strength,
    ): ByteArray = StandardSecurity.computeEncryptionKey(
        userPassword,
        ownerEntry,
        pValue,
        idFirst,
        strength.keyLengthBytes,
        strength.revision,
    )

    private fun encryptWithObjectKey(
        encryptionKey: ByteArray,
        objectNumber: Int,
        plain: ByteArray,
    ): ByteArray {
        val objectKey = StandardSecurity.computeObjectKey(
            encryptionKey, objectNumber, GENERATION,
        )
        return Rc4.crypt(objectKey, plain)
    }

    private fun slice(bytes: ByteArray, from: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        System.arraycopy(bytes, from, out, 0, length)
        return out
    }

    private const val INFO_OBJECT_NUMBER = 3
    private const val GENERATION = 0
    private const val OFFSET_DIGITS = 10
    private const val ENDOBJ_TAIL_LENGTH = 7
}
