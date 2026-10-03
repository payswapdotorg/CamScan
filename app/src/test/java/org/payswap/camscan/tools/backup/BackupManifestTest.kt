package org.payswap.camscan.tools.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.payswap.camscan.tools.FakeTimeSource

// CAMSCAN-PROD-015 §6.6 — BackupManifest coverage: documented line
// shape, byte-identical round-trip, parse rejection of every documented
// malformation, TimeSource injection.

class BackupManifestTest {

    private fun validSha(): String = "cd".repeat(32)

    private fun entry(path: String, size: Long): BackupEntry = BackupEntry(
        pathInArchive = path,
        kind = BackupEntryKind.PAGE_IMAGE,
        sizeBytes = size,
        sha256Hex = validSha(),
    )

    private fun manifest(): BackupManifest = BackupManifest(
        schemaVersion = 1,
        createdAtMillis = 1770000000123L,
        appVersionHint = "1.4.0",
        entries = listOf(
            entry("documents/doc-0001/index.json", 10L),
            entry("documents/doc-0001/pages/p1.jpg", 20L),
        ),
    )

    // ------------------------------------------------ serialization form

    @Test
    fun toTextUsesTheDocumentedLineShape() {
        val text = manifest().toText()
        val lines = text.split("\n")
        assertEquals(5, lines.size)
        assertEquals("camscan-backup|1|1770000000123|1.4.0", lines[0])
        assertEquals("entries|2", lines[1])
        assertEquals("documents/doc-0001/index.json|PAGE_IMAGE|10|" + validSha(), lines[2])
        assertEquals("documents/doc-0001/pages/p1.jpg|PAGE_IMAGE|20|" + validSha(), lines[3])
        assertEquals("end|2", lines[4])
    }

    @Test
    fun roundTripIsByteIdentical() {
        val original = manifest()
        val text = original.toText()
        val parsed = BackupManifest.parse(text)
        assertEquals(original, parsed)
        assertEquals(text, parsed!!.toText())
    }

    @Test
    fun emptyEntriesRoundTrip() {
        val original = BackupManifest(1, 5L, "", emptyList())
        val text = original.toText()
        assertEquals("camscan-backup|1|5|", text.split("\n")[0])
        assertEquals(original, BackupManifest.parse(text))
    }

    @Test
    fun emptyAppVersionHintRoundTrips() {
        val original = BackupManifest(1, 5L, "", listOf(entry("members/a", 1L)))
        assertEquals(original, BackupManifest.parse(original.toText()))
    }

    @Test
    fun entryCountAndTotalSizeBytesAreComputed() {
        val m = manifest()
        assertEquals(2, m.entryCount)
        assertEquals(30L, m.totalSizeBytes)
    }

    // ------------------------------------------------ TimeSource injection

    @Test
    fun createStampsTheInjectedClock() {
        val clock = FakeTimeSource(123456789L)
        val created = BackupManifest.create(listOf(entry("members/a", 1L)), "2.0.0", clock)
        assertEquals(123456789L, created.createdAtMillis)
        assertEquals(1, created.schemaVersion)
        assertEquals("2.0.0", created.appVersionHint)
    }

    @Test
    fun createRejectsInvalidEntriesByValidating() {
        val clock = FakeTimeSource(1L)
        try {
            BackupManifest.create(
                listOf(BackupEntry("bad|path", BackupEntryKind.OCR_TEXT, 1L, validSha())),
                "",
                clock,
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // documented: create validates through toText
        }
    }

    @Test
    fun createRejectsForbiddenVersionHint() {
        try {
            BackupManifest.create(emptyList(), "1.0|beta", FakeTimeSource(1L))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // documented: hint carries no pipe/tab/CR/LF
        }
    }

    // ------------------------------------------------ parse rejection

    @Test
    fun parseRejectsEmptyText() {
        assertNull(BackupManifest.parse(""))
    }

    @Test
    fun parseRejectsWrongHeaderTag() {
        assertNull(BackupManifest.parse("other|1|5|\nentries|0\nend|0"))
    }

    @Test
    fun parseRejectsUnsupportedSchemaVersion() {
        assertNull(BackupManifest.parse("camscan-backup|2|5|\nentries|0\nend|0"))
    }

    @Test
    fun parseRejectsNonNumericCreatedAt() {
        assertNull(BackupManifest.parse("camscan-backup|1|notanumber|\nentries|0\nend|0"))
    }

    @Test
    fun parseRejectsWrongCountTag() {
        assertNull(BackupManifest.parse("camscan-backup|1|5|\ncnt|0\nend|0"))
    }

    @Test
    fun parseRejectsCountMismatch() {
        val text = "camscan-backup|1|5|\nentries|2\nend|2"
        assertNull(BackupManifest.parse(text))
    }

    @Test
    fun parseRejectsMissingFooter() {
        val text = "camscan-backup|1|5|\nentries|1\n" + entry("members/a", 1L).toLine()
        assertNull(BackupManifest.parse(text))
    }

    @Test
    fun parseRejectsFooterCountMismatch() {
        val text = "camscan-backup|1|5|\nentries|1\n" +
            entry("members/a", 1L).toLine() + "\nend|2"
        assertNull(BackupManifest.parse(text))
    }

    @Test
    fun parseRejectsTrailingNewline() {
        assertNull(BackupManifest.parse(manifest().toText() + "\n"))
    }

    @Test
    fun parseRejectsEmbeddedCarriageReturn() {
        assertNull(BackupManifest.parse(manifest().toText().replace("\n", "\r", true)))
    }

    @Test
    fun parseRejectsMalformedEntryLine() {
        val text = "camscan-backup|1|5|\nentries|1\nnot-an-entry|end|1"
        assertNull(BackupManifest.parse(text))
    }

    @Test
    fun parseRejectsTooFewLines() {
        assertNull(BackupManifest.parse("camscan-backup|1|5|"))
        assertNull(BackupManifest.parse("camscan-backup|1|5|\nentries|0"))
    }

    @Test
    fun parseRejectsExtraEntryLines() {
        val text = "camscan-backup|1|5|\nentries|1\n" +
            entry("members/a", 1L).toLine() + "\n" +
            entry("members/b", 1L).toLine() + "\nend|1"
        assertNull(BackupManifest.parse(text))
    }

    @Test
    fun parseRejectsTabInVersionHint() {
        assertNull(BackupManifest.parse("camscan-backup|1|5|1.\t0\nentries|0\nend|0"))
    }

    @Test
    fun parseDistinguishesDifferentManifests() {
        val one = manifest()
        val two = BackupManifest(1, 1770000000124L, "1.4.0", one.entries)
        assertNotEquals(one, two)
    }

    @Test
    fun validVersionHintAcceptsEmptyAndPlain() {
        assertTrue(BackupManifest.isValidVersionHint(""))
        assertTrue(BackupManifest.isValidVersionHint("1.4.0-beta"))
    }
}
