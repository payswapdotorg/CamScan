package org.payswap.camscan.export.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PdfWriter] (CAMSCAN-PROD-007 §6.6): structure, exact xref
 * byte offsets (the tests parse the writer's own output), single- and
 * multi-page documents, rotation, metadata, and byte-determinism.
 *
 * Synthetic JPEG payloads are deliberately ASCII without digit sequences so
 * a whole-file scan for "N 0 obj" cannot false-positive inside image data.
 */
class PdfWriterTest {

    // ------------------------------------------------------------------
    // Header / EOF / basic structure
    // ------------------------------------------------------------------

    @Test
    fun headerAndEof_areWellFormed() {
        val pdf = PdfWriter.write(listOf(page()), FIXED_MILLIS)
        val text = ascii(pdf)
        assertTrue(text.startsWith("%PDF-1.4\n"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue("binary marker line must follow the version", text.contains("\n%\uFFFD\uFFFD\uFFFD\uFFFD\n"))
    }

    @Test
    fun objectCount_isThreePlusThreePerPage() {
        assertEquals(6, countObjects(PdfWriter.write(listOf(page()), FIXED_MILLIS)))
        assertEquals(12, countObjects(PdfWriter.write(threePages(), FIXED_MILLIS)))
    }

    // ------------------------------------------------------------------
    // Parse-your-own-output xref verification (exact byte offsets)
    // ------------------------------------------------------------------

    @Test
    fun xrefOffsets_areExact_everyEntryPointsAtItsObject() {
        val pdf = PdfWriter.write(threePages(), FIXED_MILLIS)
        val xref = parseXref(pdf)

        assertEquals(13, xref.entries.size)
        val free = xref.entries[0]
        assertEquals(0, free.offset)
        assertEquals(65535, free.generation)
        assertFalse(free.inUse)

        for (number in 1..12) {
            val entry = xref.entries[number]
            assertTrue("object $number must be in use", entry.inUse)
            val at = ascii(pdf, entry.offset)
            assertTrue(
                "xref entry for object $number must point at '$number 0 obj' (was '${at.take(12)}')",
                at.startsWith("$number 0 obj\n"),
            )
            assertTrue("object $number dictionary must open after the header", at.contains("<<"))
        }
    }

    @Test
    fun xrefOffsets_areExact_singlePageDocument() {
        val pdf = PdfWriter.write(listOf(page()), FIXED_MILLIS)
        val xref = parseXref(pdf)
        assertEquals(7, xref.entries.size)
        for (number in 1..6) {
            assertTrue(ascii(pdf, xref.entries[number].offset).startsWith("$number 0 obj\n"))
        }
    }

    @Test
    fun objectScan_matchesXref_noStrayObjects() {
        val pdf = PdfWriter.write(threePages(), FIXED_MILLIS)
        val xref = parseXref(pdf)
        val scanned = scanObjectOffsets(pdf)
        assertEquals((1..12).toList(), scanned.keys.sorted())
        for ((number, offset) in scanned) {
            assertEquals("scanned offset of $number must equal its xref offset", offset, xref.entries[number].offset)
        }
    }

    @Test
    fun xrefEntries_areTwentyBytesEach() {
        val pdf = PdfWriter.write(listOf(page()), FIXED_MILLIS)
        val table = ascii(pdf, parseXref(pdf).tableOffset)
        val firstEntry = table.indexOf('\n', table.indexOf('\n') + 1) + 1
        for (index in 0 until 7) {
            val entry = table.substring(firstEntry + index * 20, firstEntry + (index + 1) * 20)
            assertEquals("entry $index must be 20 bytes", 20, entry.length)
            assertTrue(entry.endsWith(" \n") || entry.startsWith("0000000000"))
        }
    }

    // ------------------------------------------------------------------
    // Page-tree content
    // ------------------------------------------------------------------

    @Test
    fun singlePage_pdfHasOnePageWithExactMediaBoxAndContent() {
        val pdf = PdfWriter.write(listOf(page(widthPx = 128, heightPx = 96)), FIXED_MILLIS)
        val text = ascii(pdf)

        assertTrue(text.contains("/Count 1"))
        assertTrue(text.contains("/Kids [4 0 R]"))
        assertTrue("MediaBox must be [0 0 96 72] at 0.75 pt/px", text.contains("/MediaBox [0 0 96 72]"))
        assertTrue(
            "content stream must place the image across the full box",
            text.contains("q 96 0 0 72 0 0 cm /Im0 Do Q"),
        )
        assertTrue(text.contains("/Im0 6 0 R"))
        assertTrue(text.contains("/Contents 5 0 R"))
    }

    @Test
    fun threePage_pdfHasThreeKidsInOrderWithPerPageImages() {
        val pdf = PdfWriter.write(threePages(), FIXED_MILLIS)
        val text = ascii(pdf)

        assertTrue(text.contains("/Count 3"))
        assertTrue(text.contains("/Kids [4 0 R 7 0 R 10 0 R]"))
        assertTrue(text.contains("/Im0 6 0 R"))
        assertTrue(text.contains("/Im0 9 0 R"))
        assertTrue(text.contains("/Im0 12 0 R"))
        assertTrue("page payloads must be embedded in order", text.indexOf(PAYLOAD_A) < text.indexOf(PAYLOAD_B))
        assertTrue(text.indexOf(PAYLOAD_B) < text.indexOf(PAYLOAD_C))
    }

    @Test
    fun fractionalPixelDimensions_keepExactQuarterPointBoxes() {
        val pdf = PdfWriter.write(listOf(page(widthPx = 101, heightPx = 99)), FIXED_MILLIS)
        assertTrue(ascii(pdf).contains("/MediaBox [0 0 75.75 74.25]"))
    }

    @Test
    fun imageXObjects_declareDctDecodeWithPixelGeometry() {
        val pdf = PdfWriter.write(listOf(page(widthPx = 640, heightPx = 480)), FIXED_MILLIS)
        val text = ascii(pdf)
        assertTrue(text.contains("/Type /XObject /Subtype /Image /Width 640 /Height 480"))
        assertTrue(text.contains("/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"))
        assertTrue(text.contains("/Length ${PAYLOAD_A.length}"))
    }

    @Test
    fun grayscaleAndCmykComponentCounts_mapToColorSpaces() {
        val gray = PdfWriter.write(listOf(page(colorComponents = 1)), FIXED_MILLIS)
        assertTrue(ascii(gray).contains("/ColorSpace /DeviceGray"))
        val cmyk = PdfWriter.write(listOf(page(colorComponents = 4)), FIXED_MILLIS)
        assertTrue(ascii(cmyk).contains("/ColorSpace /DeviceCMYK"))
        val odd = PdfWriter.write(listOf(page(colorComponents = 2)), FIXED_MILLIS)
        assertTrue("unexpected counts fall back to DeviceRGB", ascii(odd).contains("/ColorSpace /DeviceRGB"))
    }

    // ------------------------------------------------------------------
    // Rotation
    // ------------------------------------------------------------------

    @Test
    fun rotationDegrees_mapToPdfRotateValues() {
        fun rotate(degrees: Int): String = ascii(
            PdfWriter.write(listOf(page(rotationDegrees = degrees)), FIXED_MILLIS),
        )

        assertTrue(rotate(0).contains("/Rotate 0"))
        assertTrue(rotate(90).contains("/Rotate 90"))
        assertTrue(rotate(180).contains("/Rotate 180"))
        assertTrue(rotate(270).contains("/Rotate 270"))
        assertTrue("360 wraps to 0", rotate(360).contains("/Rotate 0"))
        assertTrue("-90 wraps to 270", rotate(-90).contains("/Rotate 270"))
        assertTrue("450 wraps to 90", rotate(450).contains("/Rotate 90"))
        assertTrue("45 snaps down to 0", rotate(45).contains("/Rotate 0"))
    }

    @Test
    fun perPageRotation_isAppliedIndependently() {
        val pdf = PdfWriter.write(
            listOf(page(rotationDegrees = 90), page(rotationDegrees = 180), page(rotationDegrees = 270)),
            FIXED_MILLIS,
        )
        val text = ascii(pdf)
        assertTrue(text.contains("/Rotate 90"))
        assertTrue(text.contains("/Rotate 180"))
        assertTrue(text.contains("/Rotate 270"))
        assertFalse(text.contains("/Rotate 0"))
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    @Test
    fun metadata_hasProducerAndInjectedUtcCreationDate() {
        val pdf = PdfWriter.write(listOf(page()), 1_000_000_000L)
        val text = ascii(pdf)
        assertTrue(text.contains("/Producer (CamScan)"))
        assertTrue(
            "CreationDate must render the injected instant in UTC",
            text.contains("/CreationDate (D:19700112134640+00'00')"),
        )
    }

    @Test
    fun trailer_hasSizeRootAndInfo_noRandomId() {
        val pdf = PdfWriter.write(threePages(), FIXED_MILLIS)
        val text = ascii(pdf)
        assertTrue(text.contains("trailer\n<< /Size 13 /Root 1 0 R /Info 3 0 R >>"))
        assertFalse("no /ID: PDF 1.4 allows omitting it and determinism requires it", text.contains("/ID"))
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    fun sameInputs_produceByteIdenticalOutput() {
        val pages = threePages()
        val first = PdfWriter.write(pages, FIXED_MILLIS)
        val second = PdfWriter.write(pages, FIXED_MILLIS)
        assertArrayEquals(first, second)
    }

    @Test
    fun differentTimestamps_produceDifferentCreationDates() {
        val pages = listOf(page())
        val first = ascii(PdfWriter.write(pages, 0L))
        val second = ascii(PdfWriter.write(pages, 60_000L))
        assertTrue(first.contains("D:19700101000000"))
        assertTrue(second.contains("D:19700101000100"))
        assertFalse(first == second)
    }

    // ------------------------------------------------------------------
    // Malformed input (documented API contract)
    // ------------------------------------------------------------------

    @Test
    fun emptyPageList_throwsIllegalArgument() {
        try {
            PdfWriter.write(emptyList(), FIXED_MILLIS)
            fail("empty page list must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("pages"))
        }
    }

    @Test
    fun emptyJpegBytes_throwIllegalArgument() {
        try {
            PdfWriter.write(listOf(page(jpeg = ByteArray(0))), FIXED_MILLIS)
            fail("empty JPEG bytes must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("jpegBytes"))
        }
    }

    @Test
    fun nonPositiveDimensions_throwIllegalArgument() {
        try {
            PdfWriter.write(listOf(page(widthPx = 0)), FIXED_MILLIS)
            fail("zero width must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("dimensions"))
        }
        try {
            PdfWriter.write(listOf(page(heightPx = -3)), FIXED_MILLIS)
            fail("negative height must throw IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("dimensions"))
        }
    }

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    @Test
    fun pxToPoints_isExactQuarterPointArithmetic() {
        assertEquals("0", PdfWriter.pxToPoints(0))
        assertEquals("75", PdfWriter.pxToPoints(100))
        assertEquals("75.75", PdfWriter.pxToPoints(101))
        assertEquals("74.25", PdfWriter.pxToPoints(99))
        assertEquals("96", PdfWriter.pxToPoints(128))
        assertEquals("72", PdfWriter.pxToPoints(96))
    }

    // ------------------------------------------------------------------
    // Fixtures and parsing helpers
    // ------------------------------------------------------------------

    private fun page(
        jpeg: ByteArray = PAYLOAD_A.toByteArray(),
        widthPx: Int = 128,
        heightPx: Int = 96,
        rotationDegrees: Int = 0,
        colorComponents: Int = 3,
    ) = PdfPageImage(
        jpegBytes = jpeg,
        widthPx = widthPx,
        heightPx = heightPx,
        rotationDegrees = rotationDegrees,
        colorComponents = colorComponents,
    )

    private fun threePages() = listOf(
        page(jpeg = PAYLOAD_A.toByteArray(), widthPx = 128, heightPx = 96),
        page(jpeg = PAYLOAD_B.toByteArray(), widthPx = 256, heightPx = 192, rotationDegrees = 90),
        page(jpeg = PAYLOAD_C.toByteArray(), widthPx = 64, heightPx = 48, rotationDegrees = 180),
    )

    /** US-ASCII decoding keeps one char per byte, so char index == byte offset. */
    private fun ascii(bytes: ByteArray, from: Int = 0): String =
        String(bytes, from, bytes.size - from, Charsets.US_ASCII)

    private fun countObjects(pdf: ByteArray): Int = scanObjectOffsets(pdf).size

    private class ParsedXref(val tableOffset: Int, val entries: List<Entry>) {
        class Entry(val offset: Int, val generation: Int, val inUse: Boolean)
    }

    /** Parses the xref table from the writer's own output via startxref. */
    private fun parseXref(pdf: ByteArray): ParsedXref {
        val text = ascii(pdf)
        val marker = "startxref\n"
        val markerAt = text.lastIndexOf(marker)
        assertTrue("startxref must exist", markerAt >= 0)
        val afterMarker = text.substring(markerAt + marker.length)
        val tableOffset = afterMarker.substring(0, afterMarker.indexOf('\n')).trim().toInt()

        val table = ascii(pdf, tableOffset)
        assertTrue("table must start with the xref keyword", table.startsWith("xref\n"))
        val firstNewline = table.indexOf('\n')
        val secondNewline = table.indexOf('\n', firstNewline + 1)
        val count = table.substring(firstNewline + 1, secondNewline).split(' ')[1].toInt()

        val entries = ArrayList<ParsedXref.Entry>(count)
        var cursor = secondNewline + 1
        repeat(count) {
            val entry = table.substring(cursor, cursor + 20)
            entries += ParsedXref.Entry(
                offset = entry.substring(0, 10).trim().toInt(),
                generation = entry.substring(11, 16).trim().toInt(),
                inUse = entry.substring(17, 18) == "n",
            )
            cursor += 20
        }
        return ParsedXref(tableOffset, entries)
    }

    /** Scans the whole file for "N 0 obj" headers — parse-your-own-output. */
    private fun scanObjectOffsets(pdf: ByteArray): Map<Int, Int> {
        val text = ascii(pdf)
        val pattern = Regex("(\\d+) 0 obj\n")
        val result = LinkedHashMap<Int, Int>()
        for (match in pattern.findAll(text)) {
            result[match.groupValues[1].toInt()] = match.range.first
        }
        return result
    }

    private companion object {
        const val FIXED_MILLIS = 1_712_345_678_901L

        /** Digit-free ASCII payloads so object-header scans stay unambiguous. */
        const val PAYLOAD_A = "SYNTHETIC-JPEG-PAYLOAD-ALPHA"
        const val PAYLOAD_B = "SYNTHETIC-JPEG-PAYLOAD-BRAVO"
        const val PAYLOAD_C = "SYNTHETIC-JPEG-PAYLOAD-CHARLIE"
    }
}
