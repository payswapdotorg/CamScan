package org.payswap.camscan.tools.conversion

// CAMSCAN-PROD-015 §6.2 — XlsxExportAdapter: minimal SpreadsheetML
// .xlsx export from OCR text. Package layout (fixed order):
//   1. [Content_Types].xml
//   2. _rels/.rels            (officeDocument -> xl/workbook.xml)
//   3. xl/workbook.xml        (one sheet, "Sheet1", rId1)
//   4. xl/_rels/workbook.xml.rels  (rId1 -> worksheets/sheet1.xml)
//   5. xl/worksheets/sheet1.xml
// Sheet structure: exactly ONE sheet; ONE row per OCR line, the lines of
// all pages flattened in page order; the FIRST cell of each row carries
// the line text as an inline string (t="inlineStr", NO sharedStrings
// part); row numbers and the cell's row coordinate are 1-based
// sequential, the cell column is A. Blank lines map to rows with an
// empty inline-string cell. XML built with EXPLICIT concatenation and
// the minimal escaping of [ConversionXml]. Deterministic bytes.
// HONEST SCOPE: text-level conversion (row structure only), NOT
// layout/fonts/images; parity vs the reference converter is UNVERIFIED.

/** Minimal deterministic SpreadsheetML (.xlsx) export from OCR text. */
object XlsxExportAdapter {

    /** File extension of the exported artifact. */
    const val FILE_EXTENSION = ".xlsx"

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private const val CONTENT_TYPES_ENTRY = "[Content_Types].xml"
    private const val RELS_ENTRY = "_rels/.rels"
    private const val WORKBOOK_ENTRY = "xl/workbook.xml"
    private const val WORKBOOK_RELS_ENTRY = "xl/_rels/workbook.xml.rels"
    private const val SHEET_ENTRY = "xl/worksheets/sheet1.xml"

    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_DOC_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    private const val WORKSHEET_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"

    /** Exports the document as deterministic minimal .xlsx bytes. */
    fun export(document: ConversionDocument): ByteArray {
        val parts = listOf(
            CONTENT_TYPES_ENTRY to contentTypesXml(),
            RELS_ENTRY to relsXml(),
            WORKBOOK_ENTRY to workbookXml(),
            WORKBOOK_RELS_ENTRY to workbookRelsXml(),
            SHEET_ENTRY to sheetXml(document),
        )
        return ConversionZip.build(parts)
    }

    /** Deterministic file name: sanitized title + ".xlsx". */
    fun fileNameFor(title: String): String {
        return ConversionNames.sanitizeFileName(title) + FILE_EXTENSION
    }

    private fun contentTypesXml(): String {
        return XML_DECLARATION +
            "<Types xmlns=\"" + PKG_NS + "\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "</Types>"
    }

    private fun relsXml(): String {
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            "<Relationship Id=\"rId1\" Type=\"" + OFFICE_DOC_REL + "\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"
    }

    private fun workbookXml(): String {
        return XML_DECLARATION +
            "<workbook xmlns=\"" + MAIN_NS + "\" xmlns:r=\"" + REL_NS + "\">" +
            "<sheets>" +
            "<sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/>" +
            "</sheets>" +
            "</workbook>"
    }

    private fun workbookRelsXml(): String {
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            "<Relationship Id=\"rId1\" Type=\"" + WORKSHEET_REL + "\" Target=\"worksheets/sheet1.xml\"/>" +
            "</Relationships>"
    }

    internal fun sheetXml(document: ConversionDocument): String {
        val rows = StringBuilder()
        var rowNumber = 0
        for (page in document.pages) {
            for (line in page.lines) {
                rowNumber++
                rows.append("<row r=\"")
                    .append(rowNumber.toString())
                    .append("\">")
                    .append("<c r=\"A")
                    .append(rowNumber.toString())
                    .append("\" t=\"inlineStr\"><is><t>")
                    .append(ConversionXml.escape(line))
                    .append("</t></is></c>")
                    .append("</row>")
            }
        }
        return XML_DECLARATION +
            "<worksheet xmlns=\"" + MAIN_NS + "\">" +
            "<sheetData>" +
            rows.toString() +
            "</sheetData>" +
            "</worksheet>"
    }
}
