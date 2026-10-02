package org.payswap.camscan.tools.protection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.export.pdf.PdfPageImage
import org.payswap.camscan.export.pdf.PdfWriter

// PdfEncryptor tests (CAMSCAN-PROD-011 section 6.6): structural
// post-processing of the deterministic PdfWriter output, /ID derivation,
// the /Encrypt dictionary contents, xref integrity, the full
// encrypt-then-decrypt round trip (streams AND info strings), U entry
// validation, byte determinism, and input rejection.
//
// Every expectation is derived either from the documented fixed layout of
// PdfWriter (read from the real file at the base) or from the published
// standard security handler algorithm steps; nothing is copied from
// proprietary code.

class PdfEncryptorTest {

    companion object {
        private val FIXED_MILLIS = 1_754_000_000_000L

        // Synthetic JPEG payload; the parser never scans inside stream
        // data (it uses the declared /Length), so arbitrary bytes are fine.
        private val FAKE_JPEG = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x02, 0x00, 0x00, 0x01,
            0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x2A,
            0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte(), 0x33, 0x77,
        )

        private val USER_PW = "user-pw".toByteArray(Charsets.US_ASCII)
        private val OWNER_PW = "owner-pw".toByteArray(Charsets.US_ASCII)
        private val SEED_A = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        private val SEED_B = byteArrayOf(0x09, 0x08, 0x07, 0x06)
        private val PRINTING = PdfPermissions.PRINTING_ALLOWED
    }

    private fun seedOf(bytes: ByteArray): IdSeed = IdSeed { bytes }

    private fun onePagePdf(): ByteArray =
        PdfWriter.write(listOf(PdfPageImage(FAKE_JPEG, 8, 6)), FIXED_MILLIS)

    private fun threePagePdf(): ByteArray =
        PdfWriter.write(
            listOf(
                PdfPageImage(FAKE_JPEG, 8, 6),
                PdfPageImage(FAKE_JPEG, 8, 6),
                PdfPageImage(FAKE_JPEG, 8, 6),
            ),
            FIXED_MILLIS,
        )

    private fun encrypt40(pdf: ByteArray, seed: ByteArray = SEED_A): ByteArray =
        PdfEncryptor.encrypt(pdf, USER_PW, OWNER_PW, Rc4Strength.RC4_40, PRINTING, seedOf(seed))

    private fun encrypt128(pdf: ByteArray, seed: ByteArray = SEED_A): ByteArray =
        PdfEncryptor.encrypt(pdf, USER_PW, OWNER_PW, Rc4Strength.RC4_128, PRINTING, seedOf(seed))

    // ------------------------------------------------------------------
    // Test-side tail parser (xref + trailer of the encrypted output)
    // ------------------------------------------------------------------

    private class EncryptedTail(bytes: ByteArray) {

        val xrefOffset: Int
        val sizeValue: Int
        val encryptObjectNumber: Int
        val idHex: String
        val offsets: IntArray

        init {
            val needle = "startxref\n".toByteArray(Charsets.US_ASCII)
            var marker = -1
            var pos = bytes.size - needle.size
            while (pos >= 0) {
                var matched = true
                for (n in needle.indices) {
                    if (bytes[pos + n] != needle[n]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    marker = pos
                    break
                }
                pos--
            }
            assertTrue("startxref marker not found", marker >= 0)
            var cursor = marker + needle.size
            val digits = StringBuilder()
            while (cursor < bytes.size && bytes[cursor] != '\n'.code.toByte()) {
                digits.append((bytes[cursor].toInt() and 0xFF).toChar())
                cursor++
            }
            xrefOffset = digits.toString().toInt()

            val tail = String(bytes, xrefOffset, bytes.size - xrefOffset, Charsets.US_ASCII)
            assertTrue(tail.startsWith("xref\n"))
            val lines = tail.split("\n")
            // lines[0] = "xref", lines[1] = "0 K", lines[2] = free head,
            // lines[3..] = object entries (each 19 chars + the split-off \n).
            val header = lines[1].split(" ")
            sizeValue = header[1].toInt()
            offsets = IntArray(sizeValue)
            for (number in 1 until sizeValue) {
                val entry = lines[number + 2]
                assertEquals("xref entry line must be 19 chars: " + entry, 19, entry.length)
                assertEquals("00000 n", entry.substring(11, 18))
                offsets[number] = entry.substring(0, 10).toInt()
                // Every offset must point exactly at "N 0 obj".
                val prefix = number.toString() + " 0 obj"
                for (index in prefix.indices) {
                    assertEquals(
                        "xref offset for object " + number + " byte " + index,
                        prefix[index],
                        (bytes[offsets[number] + index].toInt() and 0xFF).toChar(),
                    )
                }
            }
            val trailerLine = lines[sizeValue + 2]
            assertTrue(trailerLine.startsWith("trailer"))
            val dictLine = lines[sizeValue + 3]
            encryptObjectNumber = intAfter(dictLine, "/Encrypt ")
            assertTrue(intAfter(dictLine, "/Size ") == sizeValue)
            val idMarker = "/ID [<"
            val idStart = dictLine.indexOf(idMarker) + idMarker.length
            val idEnd = dictLine.indexOf(">", idStart)
            idHex = dictLine.substring(idStart, idEnd)
        }

        private fun intAfter(text: String, marker: String): Int {
            val start = text.indexOf(marker) + marker.length
            var end = start
            while (end < text.length && text[end] in '0'..'9') {
                end++
            }
            return text.substring(start, end).toInt()
        }
    }

    /** Hex string contents of every "<...>" span in a region (skips "<<" dict openers). */
    private fun extractHexStrings(bytes: ByteArray, from: Int, until: Int): List<String> {
        val strings = ArrayList<String>()
        var index = from
        while (index < until) {
            if (bytes[index] == '<'.code.toByte()) {
                if (index + 1 < until && bytes[index + 1] == '<'.code.toByte()) {
                    index += 2
                    continue
                }
                var end = index + 1
                while (end < until && bytes[end] != '>'.code.toByte()) {
                    end++
                }
                val sb = StringBuilder()
                for (i in index + 1 until end) {
                    sb.append((bytes[i].toInt() and 0xFF).toChar())
                }
                strings.add(sb.toString())
                index = end + 1
            } else {
                index++
            }
        }
        return strings
    }

    private fun slice(bytes: ByteArray, from: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        System.arraycopy(bytes, from, out, 0, length)
        return out
    }

    private fun ownerAndUserEntries(encrypted: ByteArray): Pair<ByteArray, ByteArray> {
        val objects = PdfWriterLayout.parseObjects(encrypted)
        val last = objects[objects.size - 1] as ParsedObject.Dict
        val hexStrings = extractHexStrings(encrypted, last.bodyStart, last.end - 7)
        assertEquals(2, hexStrings.size)
        return Pair(fromHex(hexStrings[0]), fromHex(hexStrings[1]))
    }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    @Test
    fun encryptedFile_hasExactlyOneExtraObject() {
        val original = PdfWriterLayout.parseObjects(onePagePdf())
        assertEquals(6, original.size)
        val encrypted = PdfWriterLayout.parseObjects(encrypt40(onePagePdf()))
        assertEquals(7, encrypted.size)
        assertEquals(original.size + 1, encrypted.size)
    }

    @Test
    fun encryptedFile_keepsSequentialObjectNumbers() {
        val encrypted = PdfWriterLayout.parseObjects(encrypt128(threePagePdf()))
        for ((index, parsed) in encrypted.withIndex()) {
            assertEquals(index + 1, parsed.number)
        }
        assertEquals(13, encrypted.size)
    }

    @Test
    fun xrefTable_offsetsPointAtObjectHeaders() {
        val encrypted = encrypt128(threePagePdf())
        val tail = EncryptedTail(encrypted)
        assertEquals(14, tail.sizeValue)
        // The constructor already asserts every offset points at "N 0 obj".
    }

    @Test
    fun xrefEntryZero_isTheFreeListHead() {
        val encrypted = encrypt40(onePagePdf())
        val xrefOffset = EncryptedTail(encrypted).xrefOffset
        val tail = String(encrypted, xrefOffset, encrypted.size - xrefOffset, Charsets.US_ASCII)
        val lines = tail.split("\n")
        assertEquals("xref", lines[0])
        assertEquals("0 8", lines[1])
        assertEquals("0000000000 65535 f ", lines[2])
    }

    @Test
    fun trailer_carriesSizeRootInfoEncryptAndId() {
        val encrypted = encrypt40(onePagePdf())
        val tail = EncryptedTail(encrypted)
        assertEquals(8, tail.sizeValue)
        assertEquals(7, tail.encryptObjectNumber)
        val tailText = String(encrypted, tail.xrefOffset, encrypted.size - tail.xrefOffset, Charsets.US_ASCII)
        assertTrue(tailText.contains("/Root 1 0 R"))
        assertTrue(tailText.contains("/Info 3 0 R"))
        assertTrue(tailText.contains("/Encrypt 7 0 R"))
        assertTrue(tailText.contains("/ID [<" + tail.idHex + "> <" + tail.idHex + ">]"))
    }

    @Test
    fun idHex_is32LowercaseHexChars() {
        val tail = EncryptedTail(encrypt40(onePagePdf()))
        assertEquals(32, tail.idHex.length)
        assertTrue(tail.idHex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun idDerivation_isFirst16BytesOfSha256OverSeedPlusDocument() {
        val original = onePagePdf()
        val tail = EncryptedTail(encrypt40(original))
        assertEquals(toHex(PdfEncryptor.deriveIdFirst(SEED_A, original)), tail.idHex)
    }

    @Test
    fun idChanges_whenTheSeedChanges() {
        val original = onePagePdf()
        val tailA = EncryptedTail(encrypt40(original, SEED_A))
        val tailB = EncryptedTail(encrypt40(original, SEED_B))
        assertNotEquals(tailA.idHex, tailB.idHex)
    }

    @Test
    fun startxref_pointsAtTheXrefTable() {
        val encrypted = encrypt40(onePagePdf())
        val tail = EncryptedTail(encrypted)
        val xref = "xref\n".toByteArray(Charsets.US_ASCII)
        for (index in xref.indices) {
            assertEquals(xref[index], encrypted[tail.xrefOffset + index])
        }
    }

    @Test
    fun fileEndsWithEof() {
        val encrypted = encrypt40(onePagePdf())
        val text = String(encrypted, encrypted.size - 6, 6, Charsets.US_ASCII)
        assertEquals("%%EOF\n", text)
    }

    // ------------------------------------------------------------------
    // /Encrypt dictionary contents
    // ------------------------------------------------------------------

    @Test
    fun encryptDict_40Bit_hasV1R2AndNoLength() {
        val encrypted = encrypt40(onePagePdf())
        val dictText = encryptDictText(encrypted)
        assertTrue(dictText.contains("/Filter /Standard"))
        assertTrue(dictText.contains("/V 1"))
        assertTrue(dictText.contains("/R 2"))
        assertEquals(false, dictText.contains("/Length"))
        assertTrue(dictText.contains("/P 196"))
        val entries = ownerAndUserEntries(encrypted)
        assertEquals(32, entries.first.size)
        assertEquals(32, entries.second.size)
    }

    @Test
    fun encryptDict_128Bit_hasV2R3AndLength128() {
        val encrypted = encrypt128(onePagePdf())
        val dictText = encryptDictText(encrypted)
        assertTrue(dictText.contains("/Filter /Standard"))
        assertTrue(dictText.contains("/V 2"))
        assertTrue(dictText.contains("/Length 128"))
        assertTrue(dictText.contains("/R 3"))
        assertTrue(dictText.contains("/P 196"))
    }

    @Test
    fun encryptDict_noPermissions_hasP192() {
        val encrypted = PdfEncryptor.encrypt(
            onePagePdf(),
            USER_PW,
            OWNER_PW,
            Rc4Strength.RC4_40,
            PdfPermissions.NOTHING_ALLOWED,
            seedOf(SEED_A),
        )
        assertTrue(encryptDictText(encrypted).contains("/P 192"))
    }

    @Test
    fun ownerEntry_matchesRecomputationWithTheGivenPasswords() {
        val encrypted = encrypt128(onePagePdf())
        val o = ownerAndUserEntries(encrypted).first
        val expected = StandardSecurity.computeOwnerEntry(OWNER_PW, USER_PW, 16, 3)
        assertArrayEquals(expected, o)
    }

    private fun encryptDictText(encrypted: ByteArray): String {
        val objects = PdfWriterLayout.parseObjects(encrypted)
        val last = objects[objects.size - 1] as ParsedObject.Dict
        return String(encrypted, last.bodyStart, last.end - 7 - last.bodyStart, Charsets.US_ASCII)
    }

    // ------------------------------------------------------------------
    // Pass-through and string encryption
    // ------------------------------------------------------------------

    @Test
    fun unencryptedObjects_passThroughByteIdentically() {
        val original = onePagePdf()
        val encrypted = encrypt40(original)
        val originalObjects = PdfWriterLayout.parseObjects(original)
        val encryptedObjects = PdfWriterLayout.parseObjects(encrypted)
        // Catalog (1), page-tree root (2), page dict (4): no strings.
        for (number in listOf(1, 2, 4)) {
            val a = originalObjects.first { it.number == number }
            val b = encryptedObjects.first { it.number == number }
            assertArrayEquals(
                slice(original, a.start, a.end - a.start),
                slice(encrypted, b.start, b.end - b.start),
            )
        }
    }

    @Test
    fun infoStrings_areEncryptedAsHexStrings() {
        val original = onePagePdf()
        val encrypted = encrypt40(original)
        val originalObjects = PdfWriterLayout.parseObjects(original)
        val encryptedObjects = PdfWriterLayout.parseObjects(encrypted)
        val originalInfo = originalObjects.first { it.number == 3 } as ParsedObject.Dict
        val encryptedInfo = encryptedObjects.first { it.number == 3 } as ParsedObject.Dict

        val plainStrings = PdfWriterLayout.extractLiteralStrings(
            original,
            originalInfo.bodyStart,
            originalInfo.end - 7,
        )
        assertEquals(2, plainStrings.size)
        assertEquals("CamScan", String(plainStrings[0], Charsets.US_ASCII))

        // The encrypted info dict carries NO literal strings - only hex.
        val stillLiteral = PdfWriterLayout.extractLiteralStrings(
            encrypted,
            encryptedInfo.bodyStart,
            encryptedInfo.end - 7,
        )
        assertEquals(0, stillLiteral.size)

        // Decrypt the hex strings with the object-3 key and compare.
        val idFirst = PdfEncryptor.deriveIdFirst(SEED_A, original)
        val o = ownerAndUserEntries(encrypted).first
        val key = PdfEncryptor.recoverEncryptionKey(USER_PW, o, 196, idFirst, Rc4Strength.RC4_40)
        val objectKey = StandardSecurity.computeObjectKey(key, 3, 0)
        val hexStrings = extractHexStrings(encrypted, encryptedInfo.bodyStart, encryptedInfo.end - 7)
        assertEquals(2, hexStrings.size)
        assertArrayEquals(plainStrings[0], Rc4.crypt(objectKey, fromHex(hexStrings[0])))
        assertArrayEquals(plainStrings[1], Rc4.crypt(objectKey, fromHex(hexStrings[1])))
    }

    // ------------------------------------------------------------------
    // Stream encryption round trip
    // ------------------------------------------------------------------

    @Test
    fun streamRoundTrip_40Bit_reproducesOriginalStreams() {
        val original = onePagePdf()
        val encrypted = encrypt40(original)
        assertStreamRoundTrip(original, encrypted, Rc4Strength.RC4_40)
    }

    @Test
    fun streamRoundTrip_128BitMultiPage_reproducesOriginalStreams() {
        val original = threePagePdf()
        val encrypted = encrypt128(original)
        assertStreamRoundTrip(original, encrypted, Rc4Strength.RC4_128)
    }

    private fun assertStreamRoundTrip(original: ByteArray, encrypted: ByteArray, strength: Rc4Strength) {
        val originalObjects = PdfWriterLayout.parseObjects(original)
        val encryptedObjects = PdfWriterLayout.parseObjects(encrypted)
        assertEquals(originalObjects.size, encryptedObjects.size - 1)

        val idFirst = PdfEncryptor.deriveIdFirst(SEED_A, original)
        val o = ownerAndUserEntries(encrypted).first
        val key = PdfEncryptor.recoverEncryptionKey(USER_PW, o, 196, idFirst, strength)

        var checkedStreams = 0
        for (encObject in encryptedObjects) {
            if (encObject !is ParsedObject.Stream) continue
            val origObject = originalObjects.first { it.number == encObject.number } as ParsedObject.Stream
            assertEquals(origObject.dataLength, encObject.dataLength)
            assertEquals(origObject.dataStart - origObject.start, encObject.dataStart - encObject.start)
            val cipher = slice(encrypted, encObject.dataStart, encObject.dataLength)
            val plain = slice(original, origObject.dataStart, origObject.dataLength)
            val objectKey = StandardSecurity.computeObjectKey(key, encObject.number, 0)
            assertArrayEquals(
                "stream " + encObject.number + " failed to decrypt",
                plain,
                Rc4.crypt(objectKey, cipher),
            )
            // Ciphertext really differs from plaintext (sanity).
            assertEquals(false, plain.contentEquals(cipher))
            checkedStreams++
        }
        // Two streams per page (content + image XObject).
        assertEquals(2 * ((originalObjects.size - 3) / 3), checkedStreams)
    }

    @Test
    fun contentStream_roundTripsToTheWriterBytes() {
        val original = onePagePdf()
        val encrypted = encrypt40(original)
        val originalObjects = PdfWriterLayout.parseObjects(original)
        val encryptedObjects = PdfWriterLayout.parseObjects(encrypted)
        val originalContent = originalObjects.first { it.number == 5 } as ParsedObject.Stream
        val encryptedContent = encryptedObjects.first { it.number == 5 } as ParsedObject.Stream

        val idFirst = PdfEncryptor.deriveIdFirst(SEED_A, original)
        val o = ownerAndUserEntries(encrypted).first
        val key = PdfEncryptor.recoverEncryptionKey(USER_PW, o, 196, idFirst, Rc4Strength.RC4_40)
        val objectKey = StandardSecurity.computeObjectKey(key, 5, 0)

        val decrypted = Rc4.crypt(objectKey, slice(encrypted, encryptedContent.dataStart, encryptedContent.dataLength))
        val plainText = String(decrypted, Charsets.US_ASCII)
        assertEquals(String(slice(original, originalContent.dataStart, originalContent.dataLength), Charsets.US_ASCII), plainText)
        // The documented canonical placement for an 8x6 page.
        assertEquals("q 6 0 0 4.5 0 0 cm /Im0 Do Q", plainText)
    }

    @Test
    fun declaredLengths_unchangedByEncryption() {
        val original = threePagePdf()
        val encrypted = encrypt128(original)
        val originalObjects = PdfWriterLayout.parseObjects(original)
        val encryptedObjects = PdfWriterLayout.parseObjects(encrypted)
        for (encObject in encryptedObjects) {
            if (encObject !is ParsedObject.Stream) continue
            val origObject = originalObjects.first { it.number == encObject.number } as ParsedObject.Stream
            assertEquals(origObject.dataLength, encObject.dataLength)
        }
    }

    // ------------------------------------------------------------------
    // U entry validation
    // ------------------------------------------------------------------

    @Test
    fun userEntry_validatesWithTheCorrectPassword_40Bit() {
        val original = onePagePdf()
        val encrypted = encrypt40(original)
        val u = ownerAndUserEntries(encrypted).second
        val idFirst = PdfEncryptor.deriveIdFirst(SEED_A, original)
        val o = ownerAndUserEntries(encrypted).first
        val key = PdfEncryptor.recoverEncryptionKey(USER_PW, o, 196, idFirst, Rc4Strength.RC4_40)
        assertTrue(StandardSecurity.validateUserEntry(u, key, idFirst, 2))
        val wrongKey = PdfEncryptor.recoverEncryptionKey(
            "wrong".toByteArray(Charsets.US_ASCII),
            o,
            196,
            idFirst,
            Rc4Strength.RC4_40,
        )
        assertEquals(false, StandardSecurity.validateUserEntry(u, wrongKey, idFirst, 2))
    }

    @Test
    fun userEntry_validatesWithTheCorrectPassword_128Bit() {
        val original = threePagePdf()
        val encrypted = encrypt128(original)
        val entries = ownerAndUserEntries(encrypted)
        val u = entries.second
        val o = entries.first
        val idFirst = PdfEncryptor.deriveIdFirst(SEED_A, original)
        val key = PdfEncryptor.recoverEncryptionKey(USER_PW, o, 196, idFirst, Rc4Strength.RC4_128)
        assertTrue(StandardSecurity.validateUserEntry(u, key, idFirst, 3))
        val wrongKey = PdfEncryptor.recoverEncryptionKey(
            "wrong".toByteArray(Charsets.US_ASCII),
            o,
            196,
            idFirst,
            Rc4Strength.RC4_128,
        )
        assertEquals(false, StandardSecurity.validateUserEntry(u, wrongKey, idFirst, 3))
    }

    // ------------------------------------------------------------------
    // Determinism and sensitivity
    // ------------------------------------------------------------------

    @Test
    fun encryption_isDeterministic_runTwice_40Bit() {
        val original = onePagePdf()
        assertArrayEquals(encrypt40(original), encrypt40(original))
    }

    @Test
    fun encryption_isDeterministic_runTwice_128Bit() {
        val original = threePagePdf()
        assertArrayEquals(encrypt128(original), encrypt128(original))
    }

    @Test
    fun differentSeed_producesDifferentBytes() {
        val original = onePagePdf()
        assertEquals(false, encrypt40(original, SEED_A).contentEquals(encrypt40(original, SEED_B)))
    }

    @Test
    fun differentUserPassword_producesDifferentBytes() {
        val original = onePagePdf()
        val a = PdfEncryptor.encrypt(original, USER_PW, OWNER_PW, Rc4Strength.RC4_40, PRINTING, seedOf(SEED_A))
        val b = PdfEncryptor.encrypt(
            original,
            "other".toByteArray(Charsets.US_ASCII),
            OWNER_PW,
            Rc4Strength.RC4_40,
            PRINTING,
            seedOf(SEED_A),
        )
        assertEquals(false, a.contentEquals(b))
    }

    @Test
    fun strengths_produceDifferentBytes() {
        val original = onePagePdf()
        assertEquals(false, encrypt40(original).contentEquals(encrypt128(original)))
    }

    @Test
    fun emptyPasswords_areSupported() {
        val original = onePagePdf()
        val encrypted = PdfEncryptor.encrypt(
            original,
            ByteArray(0),
            ByteArray(0),
            Rc4Strength.RC4_40,
            PRINTING,
            seedOf(SEED_A),
        )
        val objects = PdfWriterLayout.parseObjects(encrypted)
        assertEquals(7, objects.size)
        val u = ownerAndUserEntries(encrypted).second
        val idFirst = PdfEncryptor.deriveIdFirst(SEED_A, original)
        val o = ownerAndUserEntries(encrypted).first
        val key = PdfEncryptor.recoverEncryptionKey(ByteArray(0), o, 196, idFirst, Rc4Strength.RC4_40)
        assertTrue(StandardSecurity.validateUserEntry(u, key, idFirst, 2))
    }

    // ------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------

    @Test
    fun rejectsInputThatIsNotPdfWriterOutput() {
        try {
            PdfEncryptor.encrypt(
                "not a pdf at all".toByteArray(Charsets.US_ASCII),
                USER_PW,
                OWNER_PW,
                Rc4Strength.RC4_40,
                PRINTING,
                seedOf(SEED_A),
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("PdfWriter"))
        }
    }

    @Test
    fun rejectsTruncatedInput() {
        val original = onePagePdf()
        try {
            PdfEncryptor.encrypt(
                original.copyOf(40),
                USER_PW,
                OWNER_PW,
                Rc4Strength.RC4_40,
                PRINTING,
                seedOf(SEED_A),
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Any structural failure mode is acceptable: truncated header,
            // unterminated object, or stream overrun.
            assertTrue(true)
        }
    }

    // ------------------------------------------------------------------
    // Layout parser unit checks
    // ------------------------------------------------------------------

    @Test
    fun layoutParser_rejectsGarbage() {
        try {
            PdfWriterLayout.parseObjects(byteArrayOf(1, 2, 3))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("too short") || expected.message!!.contains("header"))
        }
    }

    @Test
    fun layoutParser_reportsWriterPageCount() {
        assertEquals(1, PdfWriterLayout.pageCount(PdfWriterLayout.parseObjects(onePagePdf())))
        assertEquals(3, PdfWriterLayout.pageCount(PdfWriterLayout.parseObjects(threePagePdf())))
    }

    @Test
    fun layoutParser_streamObjectsAreClassifiedByMarker() {
        val objects = PdfWriterLayout.parseObjects(onePagePdf())
        assertEquals(6, objects.size)
        assertTrue(objects[0] is ParsedObject.Dict)
        assertTrue(objects[1] is ParsedObject.Dict)
        assertTrue(objects[2] is ParsedObject.Dict)
        assertTrue(objects[3] is ParsedObject.Dict)
        assertTrue(objects[4] is ParsedObject.Stream)
        assertTrue(objects[5] is ParsedObject.Stream)
    }

    @Test
    fun parseHelper_encryptDictTextIsWellFormed() {
        val text = encryptDictText(encrypt40(onePagePdf()))
        assertTrue(text.startsWith("<< /Filter /Standard"))
        assertTrue(text.endsWith(">>\n"))
    }

    @Test
    fun parseEncryptDict_findsTwoHexStrings() {
        val encrypted = encrypt40(onePagePdf())
        val objects = PdfWriterLayout.parseObjects(encrypted)
        val last = objects[objects.size - 1] as ParsedObject.Dict
        assertEquals(2, extractHexStrings(encrypted, last.bodyStart, last.end - 7).size)
        assertEquals(7, last.number)
    }
}
