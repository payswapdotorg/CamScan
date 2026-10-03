package org.payswap.camscan.tools.conversion

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — XlsxExportAdapter coverage: fixed zip layout,
// one row per OCR line with 1-based sequential numbering, first-cell
// inlineStr semantics, page flattening, escaping, determinism, naming.

class XlsxExportAdapterTest {

    private fun unzip(bytes: ByteArray): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                result.add(entry.name to String(zis.readBytes(), Charsets.UTF_8))
                zis.closeEntry()
            }
        }
        return result
    }

    private fun sheetXmlOf(bytes: ByteArray): String {
        for (part in unzip(bytes)) {
            if (part.first == "xl/worksheets/sheet1.xml") return part.second
        }
        throw AssertionError("xl/worksheets/sheet1.xml missing")
    }

    private fun twoPageDocument(): ConversionDocument = ConversionDocument(
        title = "Sheet Doc",
        pages = listOf(
            ConversionPage(listOf("r1 line", "r2 line")),
            ConversionPage(listOf("r3 line")),
        ),
    )

    @Test
    fun zipEntryNamesAreTheFixedFiveInOrder() {
        val parts = unzip(XlsxExportAdapter.export(twoPageDocument()))
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
            ),
            parts.map { p -> p.first },
        )
    }

    @Test
    fun contentTypesDeclareWorkbookAndWorksheetParts() {
        val parts = unzip(XlsxExportAdapter.export(twoPageDocument()))
        val contentTypes = parts[0].second
        assertTrue(contentTypes.contains("spreadsheetml.sheet.main+xml"))
        assertTrue(contentTypes.contains("spreadsheetml.worksheet+xml"))
    }

    @Test
    fun workbookDeclaresExactlyOneSheet() {
        val parts = unzip(XlsxExportAdapter.export(twoPageDocument()))
        val workbook = parts[2].second
        assertEquals(1, workbook.split("<sheet ").size - 1)
        assertTrue(workbook.contains("name=\"Sheet1\""))
    }

    @Test
    fun workbookRelsTargetTheWorksheet() {
        val parts = unzip(XlsxExportAdapter.export(twoPageDocument()))
        val rels = parts[3].second
        assertTrue(rels.contains("Target=\"worksheets/sheet1.xml\""))
    }

    @Test
    fun oneRowPerLineWithSequentialOneBasedNumbers() {
        val xml = sheetXmlOf(XlsxExportAdapter.export(twoPageDocument()))
        assertEquals(3, xml.split("<row ").size - 1)
        assertTrue(xml.contains("<row r=\"1\">"))
        assertTrue(xml.contains("<row r=\"2\">"))
        assertTrue(xml.contains("<row r=\"3\">"))
        assertTrue(!xml.contains("<row r=\"4\">"))
    }

    @Test
    fun firstCellIsColumnAInlineStringMatchingTheRow() {
        val xml = sheetXmlOf(XlsxExportAdapter.export(twoPageDocument()))
        assertTrue(xml.contains("<c r=\"A1\" t=\"inlineStr\"><is><t>r1 line</t></is></c>"))
        assertTrue(xml.contains("<c r=\"A3\" t=\"inlineStr\"><is><t>r3 line</t></is></c>"))
    }

    @Test
    fun noSharedStringsPartExists() {
        val parts = unzip(XlsxExportAdapter.export(twoPageDocument()))
        for (part in parts) {
            assertTrue(!part.first.contains("sharedStrings"))
        }
    }

    @Test
    fun linesAreFlattenedAcrossPagesInOrder() {
        val document = ConversionDocument(
            "F",
            listOf(
                ConversionPage(listOf("page1-line")),
                ConversionPage(listOf("page2-line1", "page2-line2")),
            ),
        )
        val xml = sheetXmlOf(XlsxExportAdapter.export(document))
        val rowA = xml.indexOf("page1-line")
        val rowB = xml.indexOf("page2-line1")
        val rowC = xml.indexOf("page2-line2")
        assertTrue(rowA < rowB)
        assertTrue(rowB < rowC)
    }

    @Test
    fun blankLinesProduceRowsWithEmptyCells() {
        val document = ConversionDocument(
            "B",
            listOf(ConversionPage(listOf("top", "", "bottom"))),
        )
        val xml = sheetXmlOf(XlsxExportAdapter.export(document))
        assertEquals(3, xml.split("<row ").size - 1)
        assertTrue(xml.contains("<c r=\"A2\" t=\"inlineStr\"><is><t></t></is></c>"))
    }

    @Test
    fun escapingHitsAmpersandLessAndGreater() {
        val document = ConversionDocument(
            "E",
            listOf(ConversionPage(listOf("5 & 6 < 7 > 8"))),
        )
        val xml = sheetXmlOf(XlsxExportAdapter.export(document))
        assertTrue(xml.contains("5 &amp; 6 &lt; 7 &gt; 8"))
    }

    @Test
    fun emptyDocumentProducesEmptySheetData() {
        val document = ConversionDocument("Empty", emptyList())
        val xml = sheetXmlOf(XlsxExportAdapter.export(document))
        assertTrue(xml.contains("<sheetData></sheetData>"))
        assertEquals(0, xml.split("<row ").size - 1)
    }

    @Test
    fun twoExportsOfTheSameDocumentAreByteIdentical() {
        val document = twoPageDocument()
        assertTrue(
            XlsxExportAdapter.export(document).contentEquals(
                XlsxExportAdapter.export(document),
            ),
        )
    }

    @Test
    fun everyZipEntryCarriesTheFixedTimestamp() {
        val bytes = XlsxExportAdapter.export(twoPageDocument())
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                assertEquals(ConversionZip.FIXED_EPOCH_MILLIS, entry.time)
                zis.closeEntry()
            }
        }
    }

    @Test
    fun fileNameForSanitizesAndAppendsTheXlsxExtension() {
        assertEquals("Budget 2026.xlsx", XlsxExportAdapter.fileNameFor("Budget 2026"))
        assertEquals("a-b.xlsx", XlsxExportAdapter.fileNameFor("a:b"))
        assertEquals("document.xlsx", XlsxExportAdapter.fileNameFor("   "))
    }

    @Test
    fun sheetXmlCarriesTheDeclarationAndMainNamespace() {
        val xml = sheetXmlOf(XlsxExportAdapter.export(twoPageDocument()))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"))
        assertTrue(xml.contains("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"))
    }
}
