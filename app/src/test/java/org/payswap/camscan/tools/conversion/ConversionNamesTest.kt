package org.payswap.camscan.tools.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — ConversionNames + ConversionXml coverage: the
// documented sanitizer table and the minimal XML escaping set.

class ConversionNamesTest {

    @Test
    fun plainTitlesPassThroughUnchanged() {
        assertEquals("Scan 2026-01-15", ConversionNames.sanitizeFileName("Scan 2026-01-15"))
    }

    @Test
    fun spacesArePreserved() {
        assertEquals("My Scan Doc", ConversionNames.sanitizeFileName("My Scan Doc"))
    }

    @Test
    fun slashesBecomeHyphens() {
        assertEquals("a-b", ConversionNames.sanitizeFileName("a/b"))
    }

    @Test
    fun backslashesBecomeHyphens() {
        assertEquals("a-b", ConversionNames.sanitizeFileName("a" + Char(92) + "b"))
    }

    @Test
    fun colonsStarsAndQuestionsBecomeHyphens() {
        assertEquals("a-b-c-", ConversionNames.sanitizeFileName("a:b*c?"))
    }

    @Test
    fun quotesAnglesAndPipesBecomeHyphens() {
        assertEquals("x-y-z-w-", ConversionNames.sanitizeFileName("x\"y<z>w|"))
    }

    @Test
    fun controlCharactersBecomeHyphens() {
        assertEquals("a-b", ConversionNames.sanitizeFileName("a" + Char(7) + "b"))
    }

    @Test
    fun emptyTitleFallsBackToTheDocumentedDefault() {
        assertEquals(ConversionNames.DEFAULT_BASE_NAME, ConversionNames.sanitizeFileName(""))
    }

    @Test
    fun blankTitleFallsBackToTheDocumentedDefault() {
        assertEquals(ConversionNames.DEFAULT_BASE_NAME, ConversionNames.sanitizeFileName("   "))
    }

    @Test
    fun titleIsTrimmedBeforeSanitizing() {
        assertEquals("Doc", ConversionNames.sanitizeFileName("  Doc  "))
    }

    @Test
    fun forbiddenSetMatchesTheDocumentedTable() {
        for (ch in charArrayOf('/', ':', '*', '?', '"', '<', '>', '|')) {
            assertTrue(ConversionNames.isForbiddenFileNameChar(ch))
        }
        assertTrue(ConversionNames.isForbiddenFileNameChar(Char(92)))
        assertTrue(ConversionNames.isForbiddenFileNameChar(Char(31)))
        assertTrue(!ConversionNames.isForbiddenFileNameChar('a'))
        assertTrue(!ConversionNames.isForbiddenFileNameChar(' '))
        assertTrue(!ConversionNames.isForbiddenFileNameChar('.'))
    }
}

class ConversionXmlTest {

    @Test
    fun ampersandIsEscaped() {
        assertEquals("a &amp; b", ConversionXml.escape("a & b"))
    }

    @Test
    fun lessThanIsEscaped() {
        assertEquals("a &lt; b", ConversionXml.escape("a < b"))
    }

    @Test
    fun greaterThanIsEscaped() {
        assertEquals("a &gt; b", ConversionXml.escape("a > b"))
    }

    @Test
    fun quotesAndApostrophesAreNotEscaped() {
        assertEquals("\"quoted\"", ConversionXml.escape("\"quoted\""))
        assertEquals("it's", ConversionXml.escape("it's"))
    }

    @Test
    fun plainTextIsUntouched() {
        assertEquals("plain text 123", ConversionXml.escape("plain text 123"))
    }

    @Test
    fun allThreeEscapesApplyTogether() {
        assertEquals("&lt;a &amp; b&gt;", ConversionXml.escape("<a & b>"))
    }
}
