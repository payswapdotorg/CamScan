package org.payswap.camscan.tools.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// TextGlyphSource tests (CAMSCAN-PROD-011 section 6.6): ASCII 32..126
// coverage, advance/measure arithmetic, line height, and spot-checks of
// hand-authored glyph pixels.

class TextGlyphSourceTest {

    private val font = TextGlyphSource()

    @Test
    fun coversAllAscii_from32to126() {
        for (code in 32..126) {
            assertNotNull("glyph missing for code " + code, font.glyph(code.toChar()))
        }
    }

    @Test
    fun returnsNull_outsideTheCoveredRange() {
        assertNull(font.glyph(31.toChar()))
        assertNull(font.glyph(127.toChar()))
        assertNull(font.glyph(0.toChar()))
        assertNull(font.glyph(255.toChar()))
    }

    @Test
    fun everyGlyph_is5x7_withFiveBitRows() {
        for (code in 32..126) {
            val glyph = font.glyph(code.toChar())!!
            assertEquals(5, glyph.widthPx)
            assertEquals(7, glyph.heightPx)
            for (row in 0 until 7) {
                assertTrue("row out of 5-bit range for code " + code, glyph.row(row) in 0..0x1F)
            }
        }
    }

    @Test
    fun spaceGlyph_isEmpty() {
        val space = font.glyph(' ')!!
        for (row in 0 until 7) {
            assertEquals(0, space.row(row))
        }
    }

    @Test
    fun advance_isSixForCoveredCharacters() {
        assertEquals(6, font.advance('A'))
        assertEquals(6, font.advance('0'))
        assertEquals(6, font.advance(' '))
        assertEquals(6, font.advance('~'))
    }

    @Test
    fun advance_isZeroForUncoveredCharacters() {
        assertEquals(0, font.advance(1.toChar()))
        assertEquals(0, font.advance(31.toChar()))
        assertEquals(0, font.advance(127.toChar()))
    }

    @Test
    fun measure_emptyText_isZeroByZero() {
        assertEquals(Size(0, 0), font.measure(""))
    }

    @Test
    fun measure_singleCharacter_is6By9() {
        assertEquals(Size(6, 9), font.measure("A"))
    }

    @Test
    fun measure_sumsAdvances_includingTrailingGap() {
        assertEquals(Size(18, 9), font.measure("ABC"))
        assertEquals(Size(60, 9), font.measure("0123456789"))
    }

    @Test
    fun measure_ignoresUncoveredCharacters() {
        assertEquals(Size(12, 9), font.measure("A" + 1.toChar() + "B"))
    }

    @Test
    fun lineHeight_isSevenRowsPlusTwoLeading() {
        assertEquals(9, font.lineHeight)
    }

    @Test
    fun glyphPixels_letterA_matchTheAuthoredBitmap() {
        val a = font.glyph('A')!!
        // Row 0 = 0x04 (column 2 only), row 4 = 0x1F (all columns).
        assertTrue(a.pixel(2, 0))
        assertFalse(a.pixel(0, 0))
        assertFalse(a.pixel(4, 0))
        for (col in 0 until 5) {
            assertTrue("A row 4 col " + col, a.pixel(col, 4))
        }
        // Sides on rows 2 and 6 (0x11).
        assertTrue(a.pixel(0, 2))
        assertTrue(a.pixel(4, 2))
        assertTrue(a.pixel(0, 6))
        assertTrue(a.pixel(4, 6))
    }

    @Test
    fun glyphPixels_letterI_matchTheAuthoredBitmap() {
        val i = font.glyph('I')!!
        assertTrue(i.pixel(1, 0))
        assertTrue(i.pixel(2, 0))
        assertTrue(i.pixel(3, 0))
        assertFalse(i.pixel(0, 0))
        assertTrue(i.pixel(2, 3))
        assertFalse(i.pixel(1, 3))
        assertTrue(i.pixel(1, 6))
        assertTrue(i.pixel(3, 6))
    }

    @Test
    fun glyphPixels_digit0_hasTheAuthoredDiagonal() {
        val zero = font.glyph('0')!!
        // '0' rows: 0E 11 13 15 19 11 0E - the diagonal lives on rows 2..4.
        assertTrue(zero.pixel(0, 2))
        assertTrue(zero.pixel(3, 2))
        assertTrue(zero.pixel(2, 3))
        assertTrue(zero.pixel(1, 4))
        assertTrue(zero.pixel(0, 5))
        assertTrue(zero.pixel(4, 5))
    }

    @Test
    fun glyphPixels_lowercaseA_usesTallLayout() {
        val a = font.glyph('a')!!
        // rows: 00 03 0D 13 13 0D 00 - hook on row 1, stem at column 4.
        assertTrue(a.pixel(3, 1))
        assertTrue(a.pixel(4, 1))
        assertTrue(a.pixel(4, 2))
        assertTrue(a.pixel(0, 3))
        assertTrue(a.pixel(3, 3))
        assertFalse(a.pixel(2, 3))
    }

    @Test
    fun glyphPixel_outOfRange_isFalse() {
        val a = font.glyph('A')!!
        assertFalse(a.pixel(-1, 0))
        assertFalse(a.pixel(5, 0))
        assertFalse(a.pixel(0, -1))
        assertFalse(a.pixel(0, 7))
    }

    @Test
    fun glyphValueSemantics_roundTripThroughCopy() {
        val a = font.glyph('A')!!
        assertEquals(a, font.glyph('A'))
        assertEquals(a.hashCode(), font.glyph('A')!!.hashCode())
    }

    @Test
    fun defaultId_isBuiltin5x7() {
        assertEquals("builtin-5x7", TextGlyphSource().id)
        assertEquals("builtin-5x7", TextGlyphSource.BUILTIN_ID)
    }

    @Test
    fun fontTable_hasExactly95Times7Rows() {
        assertEquals(95 * 7, TextGlyphSource.FONT_ROWS.size)
    }
}
