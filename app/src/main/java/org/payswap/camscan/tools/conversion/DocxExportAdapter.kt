package org.payswap.camscan.tools.conversion

// CAMSCAN-PROD-015 §6.2 — DocxExportAdapter: minimal WordprocessingML
// .docx export from OCR text. Package layout (fixed order):
//   1. [Content_Types].xml
//   2. _rels/.rels            (officeDocument -> word/document.xml)
//   3. word/document.xml
// document.xml structure: one w:p per PARAGRAPH (blank-line-delimited
// segmentation, see [ConversionMapping]); within a paragraph ONE w:r per
// line, consecutive lines separated by a text-wrapping w:br; BETWEEN two
// pages a single paragraph carrying <w:br w:type="page"/>; NO trailing
// page break. XML is built with EXPLICIT string concatenation and the
// minimal escaping of [ConversionXml] (only ampersand, less, greater).
// Deterministic bytes: fixed entry order + fixed zip timestamps.
// HONEST SCOPE: text-level conversion (paragraph structure only), NOT
// layout/fonts/images; parity vs the reference converter is UNVERIFIED.

/** Minimal deterministic WordprocessingML (.docx) export from OCR text. */
object DocxExportAdapter {

    /** File extension of the exported artifact. */
    const val FILE_EXTENSION = ".docx"

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private const val CONTENT_TYPES_ENTRY = "[Content_Types].xml"
    private const val RELS_ENTRY = "_rels/.rels"
    private const val DOCUMENT_ENTRY = "word/document.xml"

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val PKG_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_DOC_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"

    private const val PAGE_BREAK_PARAGRAPH = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
    private const val LINE_BREAK_RUN = "<w:r><w:br/></w:r>"

    /** Exports the document as deterministic minimal .docx bytes. */
    fun export(document: ConversionDocument): ByteArray {
        val parts = listOf(
            CONTENT_TYPES_ENTRY to contentTypesXml(),
            RELS_ENTRY to relsXml(),
            DOCUMENT_ENTRY to documentXml(document),
        )
        return ConversionZip.build(parts)
    }

    /** Deterministic file name: sanitized title + ".docx". */
    fun fileNameFor(title: String): String {
        return ConversionNames.sanitizeFileName(title) + FILE_EXTENSION
    }

    private fun contentTypesXml(): String {
        return XML_DECLARATION +
            "<Types xmlns=\"" + PKG_NS + "\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
    }

    private fun relsXml(): String {
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            "<Relationship Id=\"rId1\" Type=\"" + OFFICE_DOC_REL + "\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
    }

    internal fun documentXml(document: ConversionDocument): String {
        val body = StringBuilder()
        val pageCount = document.pages.size
        for (pageIndex in document.pages.indices) {
            val page = document.pages[pageIndex]
            for (paragraph in ConversionMapping.paragraphsOf(page)) {
                body.append("<w:p>")
                for (lineIndex in paragraph.indices) {
                    if (lineIndex > 0) {
                        body.append(LINE_BREAK_RUN)
                    }
                    body.append("<w:r><w:t xml:space=\"preserve\">")
                        .append(ConversionXml.escape(paragraph[lineIndex]))
                        .append("</w:t></w:r>")
                }
                body.append("</w:p>")
            }
            // Page break strictly BETWEEN pages; never trailing.
            if (pageIndex < pageCount - 1) {
                body.append(PAGE_BREAK_PARAGRAPH)
            }
        }
        return XML_DECLARATION +
            "<w:document xmlns:w=\"" + W_NS + "\"><w:body>" +
            body.toString() +
            "</w:body></w:document>"
    }
}
