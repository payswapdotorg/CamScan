package org.payswap.camscan.tools.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — backup archive engine coverage: deterministic
// layout (manifest first, then manifest order), write->read round-trip
// via the in-memory IO fake, byte determinism, fixed entry timestamps,
// writer validation, and sealed Corrupt detection for every documented
// failure mode.

class BackupArchiveTest {

    private fun shaOf(bytes: ByteArray): String = Sha256Text.sha256Hex(bytes)

    private fun entryFor(path: String, bytes: ByteArray): BackupEntry = BackupEntry(
        pathInArchive = path,
        kind = BackupEntryKind.PAGE_IMAGE,
        sizeBytes = bytes.size.toLong(),
        sha256Hex = shaOf(bytes),
    )

    private fun contents(): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        map["documents/doc-0001/index.json"] = "index-json-bytes".toByteArray()
        map["documents/doc-0001/pages/p1.jpg"] = byteArrayOf(1, 2, 3, 4, 5)
        map["documents/doc-0001/ocr/p1.txt"] = "recognized text".toByteArray()
        return map
    }

    private fun manifestFor(map: Map<String, ByteArray>, createdAt: Long = 500L): BackupManifest {
        val entries = map.keys.map { key -> entryFor(key, map.getValue(key)) }
        return BackupManifest(1, createdAt, "1.5.0", entries)
    }

    /** Unzips bytes to ordered (name, bytes) pairs, asserting readability. */
    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                result.add(entry.name to zis.readBytes())
                zis.closeEntry()
            }
        }
        return result
    }

    /** Hand-builds a raw zip (fixed timestamps) for corrupt-input tests. */
    private fun rawZip(members: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for (member in members) {
                val zipEntry = ZipEntry(member.first)
                zipEntry.time = BackupArchiveWriter.FIXED_EPOCH_MILLIS
                zos.putNextEntry(zipEntry)
                zos.write(member.second)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ------------------------------------------------ layout + determinism

    @Test
    fun layoutIsManifestFirstThenManifestOrder() {
        val map = contents()
        val manifest = manifestFor(map)
        val bytes = BackupArchiveWriter.buildBytes(manifest, map)
        val names = unzip(bytes).map { pair -> pair.first }
        assertEquals(
            listOf(
                "manifest.txt",
                "documents/doc-0001/index.json",
                "documents/doc-0001/pages/p1.jpg",
                "documents/doc-0001/ocr/p1.txt",
            ),
            names,
        )
    }

    @Test
    fun manifestMemberCarriesTheSerializedManifest() {
        val map = contents()
        val manifest = manifestFor(map)
        val bytes = BackupArchiveWriter.buildBytes(manifest, map)
        val members = unzip(bytes)
        assertEquals(manifest.toText(), String(members[0].second, Charsets.UTF_8))
    }

    @Test
    fun everyEntryCarriesTheFixedTimestamp() {
        val map = contents()
        val bytes = BackupArchiveWriter.buildBytes(manifestFor(map), map)
        val times = mutableListOf<Long>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                times.add(entry.time)
                zis.closeEntry()
            }
        }
        assertEquals(times.size, times.filter { t -> t == BackupArchiveWriter.FIXED_EPOCH_MILLIS }.size)
    }

    @Test
    fun twoBuildsOfTheSameInputAreByteIdentical() {
        val map = contents()
        val manifest = manifestFor(map)
        val first = BackupArchiveWriter.buildBytes(manifest, map)
        val second = BackupArchiveWriter.buildBytes(manifest, map)
        assertTrue(first.contentEquals(second))
    }

    // ------------------------------------------------ write -> read round-trip

    @Test
    fun writeThenReadRoundTripsThroughTheIoSeam() {
        val io = InMemoryBackupIo()
        val map = contents()
        val manifest = manifestFor(map, createdAt = 4242L)
        BackupArchiveWriter.write("backups/b.zip", manifest, map, io)
        val read = BackupArchiveReader.read("backups/b.zip", io)
        assertEquals(manifest, read.manifest)
        for (key in map.keys) {
            assertTrue(map.getValue(key).contentEquals(read.member(key)!!))
        }
        assertEquals(map.size, read.members.size)
    }

    @Test
    fun readReturnsVerifiedMembersUnderPrefixInManifestOrder() {
        val io = InMemoryBackupIo()
        val map = contents()
        val manifest = manifestFor(map)
        BackupArchiveWriter.write("b.zip", manifest, map, io)
        val read = BackupArchiveReader.read("b.zip", io)
        val docEntries = read.membersUnder("documents/doc-0001/")
        assertEquals(3, docEntries.size)
        assertEquals("documents/doc-0001/index.json", docEntries[0].pathInArchive)
    }

    // ------------------------------------------------ writer validation

    @Test
    fun writerRejectsMissingDeclaredContent() {
        val map = contents()
        val manifest = manifestFor(map)
        val truncated = LinkedHashMap<String, ByteArray>(map)
        truncated.remove("documents/doc-0001/ocr/p1.txt")
        try {
            BackupArchiveWriter.buildBytes(manifest, truncated)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("missing content"))
        }
    }

    @Test
    fun writerRejectsUndeclaredContent() {
        val map = contents()
        val manifest = manifestFor(map)
        val extended = LinkedHashMap<String, ByteArray>(map)
        extended["documents/doc-0001/extra.bin"] = byteArrayOf(9)
        try {
            BackupArchiveWriter.buildBytes(manifest, extended)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not declared"))
        }
    }

    @Test
    fun writerRejectsSizeMismatch() {
        val map = contents()
        val entries = map.keys.map { key -> entryFor(key, map.getValue(key)) }.toMutableList()
        val wrong = BackupEntry(
            entries[0].pathInArchive,
            entries[0].kind,
            entries[0].sizeBytes + 1L,
            entries[0].sha256Hex,
        )
        entries[0] = wrong
        try {
            BackupArchiveWriter.buildBytes(BackupManifest(1, 5L, "", entries), map)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size mismatch"))
        }
    }

    @Test
    fun writerRejectsShaMismatch() {
        val map = contents()
        val entries = map.keys.map { key -> entryFor(key, map.getValue(key)) }.toMutableList()
        val wrong = BackupEntry(
            entries[0].pathInArchive,
            entries[0].kind,
            entries[0].sizeBytes,
            "ff".repeat(32),
        )
        entries[0] = wrong
        try {
            BackupArchiveWriter.buildBytes(BackupManifest(1, 5L, "", entries), map)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("sha256 mismatch"))
        }
    }

    @Test
    fun writerRejectsManifestEntryNamedManifestTxt() {
        val reserved = BackupEntry(
            "manifest.txt",
            BackupEntryKind.OCR_TEXT,
            1L,
            shaOf(byteArrayOf(1)),
        )
        try {
            BackupArchiveWriter.buildBytes(
                BackupManifest(1, 5L, "", listOf(reserved)),
                mapOf("manifest.txt" to byteArrayOf(1)),
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reserved"))
        }
    }

    // ------------------------------------------------ reader verification

    @Test
    fun readerThrowsMissingForAbsentArchive() {
        try {
            BackupArchiveReader.read("nope.zip", InMemoryBackupIo())
            fail("expected BackupArchiveError.Missing")
        } catch (expected: BackupArchiveError.Missing) {
            // documented: absent is Missing, not Corrupt
        }
    }

    @Test
    fun readerDetectsTamperedBytesAsCorrupt() {
        val io = InMemoryBackupIo()
        val map = contents()
        BackupArchiveWriter.write("b.zip", manifestFor(map), map, io)
        val original = io.read("b.zip")!!
        val tampered = original.copyOf()
        // Flip a byte in the middle of the compressed payload.
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x55).toByte()
        io.write("b.zip", tampered)
        try {
            BackupArchiveReader.read("b.zip", io)
            fail("expected BackupArchiveError.Corrupt")
        } catch (expected: BackupArchiveError.Corrupt) {
            // documented: any unreadable zip is Corrupt
        }
    }

    @Test
    fun readerDetectsShaMismatchAsCorrupt() {
        val map = contents()
        // Manifest declares a WRONG sha for the first member.
        val entries = map.keys.map { key -> entryFor(key, map.getValue(key)) }.toMutableList()
        entries[0] = BackupEntry(
            entries[0].pathInArchive,
            entries[0].kind,
            entries[0].sizeBytes,
            "ee".repeat(32),
        )
        val lyingManifest = BackupManifest(1, 5L, "", entries)
        val members = mutableListOf<Pair<String, ByteArray>>()
        members.add(
            BackupArchiveWriter.MANIFEST_ENTRY_NAME to
                lyingManifest.toText().toByteArray(Charsets.UTF_8),
        )
        for (key in map.keys) {
            members.add(key to map.getValue(key))
        }
        val io = InMemoryBackupIo()
        io.write("liar.zip", rawZip(members))
        try {
            BackupArchiveReader.read("liar.zip", io)
            fail("expected BackupArchiveError.Corrupt")
        } catch (expected: BackupArchiveError.Corrupt) {
            assertTrue(expected.message!!.contains("sha256 mismatch"))
        }
    }

    @Test
    fun readerRejectsManifestNotFirstAsCorrupt() {
        val map = contents()
        val manifest = manifestFor(map)
        val members = mutableListOf<Pair<String, ByteArray>>()
        for (key in map.keys) {
            members.add(key to map.getValue(key))
        }
        members.add(
            BackupArchiveWriter.MANIFEST_ENTRY_NAME to
                manifest.toText().toByteArray(Charsets.UTF_8),
        )
        val io = InMemoryBackupIo()
        io.write("reordered.zip", rawZip(members))
        try {
            BackupArchiveReader.read("reordered.zip", io)
            fail("expected BackupArchiveError.Corrupt")
        } catch (expected: BackupArchiveError.Corrupt) {
            assertTrue(expected.message!!.contains("first member"))
        }
    }

    @Test
    fun readerRejectsExtraMemberAsCorrupt() {
        val map = contents()
        val manifest = manifestFor(map)
        val members = mutableListOf<Pair<String, ByteArray>>()
        members.add(
            BackupArchiveWriter.MANIFEST_ENTRY_NAME to
                manifest.toText().toByteArray(Charsets.UTF_8),
        )
        for (key in map.keys) {
            members.add(key to map.getValue(key))
        }
        members.add("documents/doc-0001/stowaway.bin" to byteArrayOf(7))
        val io = InMemoryBackupIo()
        io.write("extra.zip", rawZip(members))
        try {
            BackupArchiveReader.read("extra.zip", io)
            fail("expected BackupArchiveError.Corrupt")
        } catch (expected: BackupArchiveError.Corrupt) {
            assertTrue(expected.message!!.contains("member count mismatch"))
        }
    }

    @Test
    fun readerRejectsReorderedMembersAsCorrupt() {
        val map = contents()
        val manifest = manifestFor(map)
        val keys = map.keys.toList()
        val members = mutableListOf<Pair<String, ByteArray>>()
        members.add(
            BackupArchiveWriter.MANIFEST_ENTRY_NAME to
                manifest.toText().toByteArray(Charsets.UTF_8),
        )
        // Reverse the member order relative to the manifest.
        for (index in keys.indices.reversed()) {
            members.add(keys[index] to map.getValue(keys[index]))
        }
        val io = InMemoryBackupIo()
        io.write("swapped.zip", rawZip(members))
        try {
            BackupArchiveReader.read("swapped.zip", io)
            fail("expected BackupArchiveError.Corrupt")
        } catch (expected: BackupArchiveError.Corrupt) {
            assertTrue(expected.message!!.contains("member order mismatch"))
        }
    }

    @Test
    fun readerRejectsUnparseableManifestAsCorrupt() {
        val members = listOf(
            BackupArchiveWriter.MANIFEST_ENTRY_NAME to "garbage".toByteArray(),
        )
        val io = InMemoryBackupIo()
        io.write("garbage.zip", rawZip(members))
        try {
            BackupArchiveReader.read("garbage.zip", io)
            fail("expected BackupArchiveError.Corrupt")
        } catch (expected: BackupArchiveError.Corrupt) {
            assertTrue(expected.message!!.contains("manifest"))
        }
    }

    @Test
    fun readerRejectsEmptyZipAsCorrupt() {
        val io = InMemoryBackupIo()
        io.write("empty.zip", rawZip(emptyList()))
        try {
            BackupArchiveReader.read("empty.zip", io)
            fail("expected BackupArchiveError.Corrupt")
        } catch (expected: BackupArchiveError.Corrupt) {
            // documented: empty archive is corrupt
        }
    }

    // ------------------------------------------------ hashing helper

    @Test
    fun sha256HexMatchesKnownVector() {
        // sha256 of the empty string (well-known vector).
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256Text.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun sha256HexIsValidatedAsLowercase64() {
        assertTrue(Sha256Text.isValidHex("0123456789abcdef".repeat(4)))
        assertTrue(!Sha256Text.isValidHex("0123456789ABCDEF".repeat(4)))
        assertTrue(!Sha256Text.isValidHex("abc"))
    }
}
