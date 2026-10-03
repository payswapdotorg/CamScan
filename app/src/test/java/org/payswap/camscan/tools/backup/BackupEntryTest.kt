package org.payswap.camscan.tools.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — BackupEntry coverage: documented field order,
// byte-identical round-trip, parse rejection of malformed and injected
// lines, toLine validation of path/size/sha.

class BackupEntryTest {

    private fun validSha(): String = "ab".repeat(32)

    private fun validEntry(): BackupEntry = BackupEntry(
        pathInArchive = "documents/doc-0001/index.json",
        kind = BackupEntryKind.DOCUMENT_INDEX,
        sizeBytes = 42L,
        sha256Hex = validSha(),
    )

    // ------------------------------------------------ serialization form

    @Test
    fun toLineUsesTheDocumentedFieldOrder() {
        val line = validEntry().toLine()
        assertEquals(
            "documents/doc-0001/index.json|DOCUMENT_INDEX|42|" + validSha(),
            line,
        )
    }

    @Test
    fun toLineRoundTripIsByteIdentical() {
        val entry = validEntry()
        assertEquals(entry, BackupEntry.parse(entry.toLine()))
        assertEquals(entry.toLine(), BackupEntry.parse(entry.toLine())!!.toLine())
    }

    @Test
    fun kindEnumCoversExactlyTheFourDocumentedKinds() {
        assertEquals(
            listOf("DOCUMENT_INDEX", "PAGE_IMAGE", "OCR_TEXT", "MODE_NOTE"),
            BackupEntryKind.entries.map { kind -> kind.name },
        )
    }

    @Test
    fun toLineAcceptsEveryKind() {
        for (kind in BackupEntryKind.entries) {
            val entry = BackupEntry("members/x.bin", kind, 1L, validSha())
            assertEquals(kind, BackupEntry.parse(entry.toLine())!!.kind)
        }
    }

    // ------------------------------------------------ parse rejection

    @Test
    fun parseRejectsEmptyLine() {
        assertNull(BackupEntry.parse(""))
    }

    @Test
    fun parseRejectsWrongColumnCount() {
        assertNull(BackupEntry.parse("a|DOCUMENT_INDEX|1"))
        assertNull(BackupEntry.parse("a|DOCUMENT_INDEX|1|" + validSha() + "|extra"))
    }

    @Test
    fun parseRejectsPathWithPipeInjection() {
        assertNull(BackupEntry.parse("doc|evil|DOCUMENT_INDEX|1|" + validSha()))
    }

    @Test
    fun parseRejectsPathWithTabInjection() {
        assertNull(BackupEntry.parse("doc\tevil|DOCUMENT_INDEX|1|" + validSha()))
    }

    @Test
    fun parseRejectsInjectedNewline() {
        assertNull(BackupEntry.parse("doc\n evil|DOCUMENT_INDEX|1|" + validSha()))
    }

    @Test
    fun parseRejectsInjectedCarriageReturn() {
        assertNull(BackupEntry.parse("doc\r evil|DOCUMENT_INDEX|1|" + validSha()))
    }

    @Test
    fun parseRejectsAbsolutePath() {
        assertNull(BackupEntry.parse("/abs/path|DOCUMENT_INDEX|1|" + validSha()))
    }

    @Test
    fun parseRejectsEmptyPath() {
        assertNull(BackupEntry.parse("|DOCUMENT_INDEX|1|" + validSha()))
    }

    @Test
    fun parseRejectsUnknownKind() {
        assertNull(BackupEntry.parse("members/x|NOT_A_KIND|1|" + validSha()))
    }

    @Test
    fun parseRejectsLowercaseKind() {
        assertNull(BackupEntry.parse("members/x|document_index|1|" + validSha()))
    }

    @Test
    fun parseRejectsNegativeSize() {
        assertNull(BackupEntry.parse("members/x|PAGE_IMAGE|-1|" + validSha()))
    }

    @Test
    fun parseRejectsNonNumericSize() {
        assertNull(BackupEntry.parse("members/x|PAGE_IMAGE|ten|" + validSha()))
    }

    @Test
    fun parseRejectsShortSha() {
        assertNull(BackupEntry.parse("members/x|PAGE_IMAGE|1|abcd"))
    }

    @Test
    fun parseRejectsUppercaseSha() {
        assertNull(BackupEntry.parse("members/x|PAGE_IMAGE|1|" + "AB".repeat(32)))
    }

    @Test
    fun parseRejectsNonHexSha() {
        assertNull(BackupEntry.parse("members/x|PAGE_IMAGE|1|" + "zz".repeat(32)))
    }

    // ------------------------------------------------ toLine validation

    @Test
    fun toLineThrowsOnPathWithForbiddenCharacter() {
        try {
            BackupEntry("bad|path", BackupEntryKind.PAGE_IMAGE, 1L, validSha()).toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("pathInArchive"))
        }
    }

    @Test
    fun toLineThrowsOnAbsolutePath() {
        try {
            BackupEntry("/abs", BackupEntryKind.PAGE_IMAGE, 1L, validSha()).toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // documented: paths are relative
        }
    }

    @Test
    fun toLineThrowsOnNegativeSize() {
        try {
            BackupEntry("members/x", BackupEntryKind.PAGE_IMAGE, -2L, validSha()).toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // documented: sizes are non-negative
        }
    }

    @Test
    fun toLineThrowsOnInvalidSha() {
        try {
            BackupEntry("members/x", BackupEntryKind.PAGE_IMAGE, 1L, "nothex").toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // documented: 64 lowercase hex chars
        }
    }

    @Test
    fun isValidPathAcceptsRelativeNestedPathsOnly() {
        assertTrue(BackupEntry.isValidPath("documents/doc-0001/pages/p1.jpg"))
        assertFalse(BackupEntry.isValidPath(""))
        assertFalse(BackupEntry.isValidPath("/leading"))
        assertFalse(BackupEntry.isValidPath("pipe|"))
    }
}
