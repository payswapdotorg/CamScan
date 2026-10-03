package org.payswap.camscan.tools.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.payswap.camscan.core.time.TimeSource

// CAMSCAN-PROD-012 §7 — ModeNote coverage: byte-identical round-trip,
// documented field order, parse rejection of malformed and injected
// lines, toLine validation, and TimeSource injection.

class ModeNoteTest {

    private fun validNote(): ModeNote = ModeNote(
        documentId = "doc-0001",
        modeId = ScanModeIds.ID_CARD,
        appliedAtMillis = 1770000000123L,
        pagesProduced = 1,
        splitApplied = false,
        selectionConfidence = 0.75,
    )

    // ------------------------------------------------ serialization form

    @Test
    fun toLineUsesTheDocumentedFieldOrder() {
        val line = validNote().toLine()
        assertEquals(
            "doc-0001|mode-id-card|1770000000123|1|false|0.75",
            line,
        )
    }

    @Test
    fun toLineFieldOrderIsStableAcrossValues() {
        val note = ModeNote(
            documentId = "doc-0002",
            modeId = ScanModeIds.BOOK_SPREAD,
            appliedAtMillis = 42L,
            pagesProduced = 2,
            splitApplied = true,
            selectionConfidence = 1.0,
        )
        assertEquals("doc-0002|mode-book-spread|42|2|true|1.0", note.toLine())
    }

    @Test
    fun separatorConstantIsThePipe() {
        assertEquals("|", ModeNote.SEPARATOR)
    }

    // ------------------------------------------------ round-trip

    @Test
    fun roundTripIsByteIdentical() {
        val line = validNote().toLine()
        val parsed = ModeNote.parse(line)
        assertEquals(validNote(), parsed)
        assertEquals(line, parsed!!.toLine())
    }

    @Test
    fun roundTripIsByteIdenticalForTrickyConfidenceValues() {
        for (confidence in listOf(0.1, 1.0 / 3.0, 0.08333333333333333, 1.0, 0.0)) {
            val note = ModeNote(
                documentId = "d1",
                modeId = ScanModeIds.BUSINESS_CARD,
                appliedAtMillis = 7L,
                pagesProduced = 3,
                splitApplied = true,
                selectionConfidence = confidence,
            )
            val parsed = ModeNote.parse(note.toLine())
            assertEquals("confidence " + confidence, note, parsed)
            assertEquals(note.toLine(), parsed!!.toLine())
        }
    }

    @Test
    fun parseAcceptsEveryCatalogModeId() {
        for (mode in ScanModeCatalog.modes) {
            val note = ModeNote(
                documentId = "d",
                modeId = mode.modeId,
                appliedAtMillis = 1L,
                pagesProduced = 1,
                splitApplied = false,
                selectionConfidence = 0.5,
            )
            assertEquals(note, ModeNote.parse(note.toLine()))
        }
    }

    // ------------------------------------------------ parse rejection

    @Test
    fun parseRejectsWrongColumnCount() {
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false"))
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false|0.5|extra"))
        assertNull(ModeNote.parse(""))
    }

    @Test
    fun parseRejectsNewlineInjection() {
        assertNull(ModeNote.parse("doc\ninjected|mode-id-card|1|1|false|0.5"))
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false|0.5\n"))
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false|0.5\r\nx"))
    }

    @Test
    fun parseRejectsTabInjection() {
        assertNull(ModeNote.parse("doc\tinjected|mode-id-card|1|1|false|0.5"))
        assertNull(ModeNote.parse("doc|mode-id\tcard|1|1|false|0.5"))
    }

    @Test
    fun parseRejectsPipeInjection() {
        // An extra pipe shifts the column count — rejected.
        assertNull(ModeNote.parse("doc|mo|de|1|1|false|0.5"))
    }

    @Test
    fun parseRejectsEmptyStringFields() {
        assertNull(ModeNote.parse("|mode-id-card|1|1|false|0.5"))
        assertNull(ModeNote.parse("doc||1|1|false|0.5"))
    }

    @Test
    fun parseRejectsNonNumericTimestamp() {
        assertNull(ModeNote.parse("doc|mode-id-card|12a45|1|false|0.5"))
    }

    @Test
    fun parseRejectsNonNumericPageCount() {
        assertNull(ModeNote.parse("doc|mode-id-card|1|two|false|0.5"))
    }

    @Test
    fun parseRejectsBadBooleans() {
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|True|0.5"))
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|FALSE|0.5"))
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|yes|0.5"))
    }

    @Test
    fun parseRejectsNonFiniteConfidence() {
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false|NaN"))
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false|Infinity"))
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false|-Infinity"))
    }

    @Test
    fun parseRejectsNonNumericConfidence() {
        assertNull(ModeNote.parse("doc|mode-id-card|1|1|false|zero"))
    }

    // ------------------------------------------------ toLine validation

    @Test
    fun toLineThrowsOnPipeInsideDocumentId() {
        val note = validNote().copy(documentId = "doc|1")
        try {
            note.toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun toLineThrowsOnNewlineInsideModeId() {
        val note = validNote().copy(modeId = "mode\nid")
        try {
            note.toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun toLineThrowsOnTabInsideDocumentId() {
        val note = validNote().copy(documentId = "doc\t1")
        try {
            note.toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun toLineThrowsOnNanConfidence() {
        val note = validNote().copy(selectionConfidence = Double.NaN)
        try {
            note.toLine()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    // ------------------------------------------------ TimeSource injection

    @Test
    fun recordStampsAppliedAtThroughInjectedTimeSource() {
        val fixed = TimeSource { 1234567890123L }
        val note = ModeNote.record(
            documentId = "doc-0009",
            mode = IdCardMode,
            pagesProduced = 1,
            splitApplied = false,
            selectionConfidence = 0.9,
            timeSource = fixed,
        )
        assertEquals(1234567890123L, note.appliedAtMillis)
        assertEquals(ScanModeIds.ID_CARD, note.modeId)
    }

    @Test
    fun recordUsesEachTimeSourceReadIndependently() {
        var stamp = 100L
        val stepping = TimeSource {
            val now = stamp
            stamp += 10L
            now
        }
        val first = ModeNote.record("a", BookSpreadMode, 2, true, 0.5, stepping)
        val second = ModeNote.record("b", BookSpreadMode, 2, true, 0.6, stepping)
        assertEquals(100L, first.appliedAtMillis)
        assertEquals(110L, second.appliedAtMillis)
        assertTrue(first.appliedAtMillis < second.appliedAtMillis)
    }

    @Test
    fun recordRoundTripsThroughItsOwnLine() {
        val note = ModeNote.record(
            documentId = "doc-round-trip",
            mode = BookSpreadMode,
            pagesProduced = 2,
            splitApplied = true,
            selectionConfidence = 0.42,
            timeSource = TimeSource { 55L },
        )
        assertEquals(note, ModeNote.parse(note.toLine()))
    }

    // ------------------------------------------------ determinism

    @Test
    fun serializationIsDeterministic() {
        assertEquals(validNote().toLine(), validNote().toLine())
    }
}
