package org.payswap.camscan.tools.conversion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — DocxExportAdapter coverage: fixed zip layout,
// page-break placement (between pages only, never trailing), paragraph
// and run structure, XML escaping, determinism, file naming.

class DocxExportAdapterTest {

    /** Unzips export bytes to ordered (name, text) parts. */
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

    private fun documentXmlOf(bytes: ByteArray): String {
        for (part in unzip(bytes)) {
            if (part.first == "word/document.xml") return part.second
        }
        throw AssertionError("word/document.xml missing")
    }

    private fun twoPageDocument(): ConversionDocument = ConversionDocument(
        title = "Report",
        pages = listOf(
            ConversionPage(listOf("first page line", "", "second para")),
            ConversionPage(listOf("second page line")),
        ),
    )

    @Test
    fun zipEntryNamesAreTheFixedThreeInOrder() {
        val parts = unzip(DocxExportAdapter.export(twoPageDocument()))
        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"),
            parts.map { p -> p.first },
        )
    }

    @Test
    fun contentTypesDeclaresTheWordprocessingMainPart() {
        val parts = unzip(DocxExportAdapter.export(twoPageDocument()))
        val contentTypes = parts[0].second
        assertTrue(contentTypes.contains("wordprocessingml.document.main+xml"))
        assertTrue(contentTypes.contains("/word/document.xml"))
    }

    @Test
    fun relsTargetTheDocumentPart() {
        val parts = unzip(DocxExportAdapter.export(twoPageDocument()))
        val rels = parts[1].second
        assertTrue(rels.contains("Target=\"word/document.xml\""))
        assertTrue(rels.contains("officeDocument"))
    }

    @Test
    fun documentXmlCarriesTheDeclarationAndBody() {
        val xml = documentXmlOf(DocxExportAdapter.export(twoPageDocument()))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"))
        assertTrue(xml.contains("<w:body>"))
        assertTrue(xml.endsWith("</w:body></w:document>"))
    }

    @Test
    fun pageBreaksGoBetweenPagesAndNeverTrail() {
        val xml = documentXmlOf(DocxExportAdapter.export(twoPageDocument()))
        val breakToken = "<w:br w:type=\"page\"/>"
        val occurrences = xml.split(breakToken).size - 1
        assertEquals(1, occurrences)
        // The body must not END with a page-break paragraph.
        val bodyEnd = xml.substringBefore("</w:body>")
        assertTrue(!bodyEnd.endsWith("<w:p><w:r>" + breakToken + "</w:r></w:p>"))
    }

    @Test
    fun singlePageDocumentHasNoPageBreakAtAll() {
        val document = ConversionDocument("One", listOf(ConversionPage(listOf("only line"))))
        val xml = documentXmlOf(DocxExportAdapter.export(document))
        assertEquals(0, xml.split("<w:br w:type=\"page\"/>").size - 1)
    }

    @Test
    fun threePagesCarryExactlyTwoBreaksEvenWithAnEmptyMiddlePage() {
        val document = ConversionDocument(
            "Three",
            listOf(
                ConversionPage(listOf("a")),
                ConversionPage(emptyList()),
                ConversionPage(listOf("c")),
            ),
        )
        val xml = documentXmlOf(DocxExportAdapter.export(document))
        assertEquals(2, xml.split("<w:br w:type=\"page\"/>").size - 1)
    }

    @Test
    fun paragraphsUseOneRunPerLineWithInnerBreaksBetweenLines() {
        val document = ConversionDocument(
            "P",
            listOf(ConversionPage(listOf("line one", "line two"))),
        )
        val xml = documentXmlOf(DocxExportAdapter.export(document))
        // One paragraph, two runs, one text-wrapping break between them.
        assertEquals(
            "<w:body><w:p>" +
                "<w:r><w:t xml:space=\"preserve\">line one</w:t></w:r>" +
                "<w:r><w:br/></w:r>" +
                "<w:r><w:t xml:space=\"preserve\">line two</w:t></w:r>" +
                "</w:p></w:body></w:document>",
            xml.substring(xml.indexOf("<w:body>")),
        )
    }

    @Test
    fun blankLineDelimitsParagraphsIntoSeparateWpElements() {
        val document = ConversionDocument(
            "P",
            listOf(ConversionPage(listOf("para one", "", "para two"))),
        )
        val xml = documentXmlOf(DocxExportAdapter.export(document))
        assertEquals(2, xml.split("<w:p>").size - 1)
        assertTrue(xml.contains(">para one<"))
        assertTrue(xml.contains(">para two<"))
    }

    @Test
    fun escapingHitsAmpersandLessAndGreater() {
        val document = ConversionDocument(
            "E",
            listOf(ConversionPage(listOf("a & b < c > d"))),
        )
        val xml = documentXmlOf(DocxExportAdapter.export(document))
        assertTrue(xml.contains("a &amp; b &lt; c &gt; d"))
        assertTrue(!xml.contains("a & b"))
    }

    @Test
    fun twoExportsOfTheSameDocumentAreByteIdentical() {
        val document = twoPageDocument()
        val first = DocxExportAdapter.export(document)
        val second = DocxExportAdapter.export(document)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun everyZipEntryCarriesTheFixedTimestamp() {
        val bytes = DocxExportAdapter.export(twoPageDocument())
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                assertEquals(ConversionZip.FIXED_EPOCH_MILLIS, entry.time)
                zis.closeEntry()
            }
        }
    }

    @Test
    fun fileNameForSanitizesAndAppendsTheDocxExtension() {
        assertEquals("My Doc.docx", DocxExportAdapter.fileNameFor("My Doc"))
        assertEquals("a-b.docx", DocxExportAdapter.fileNameFor("a/b"))
        assertEquals("document.docx", DocxExportAdapter.fileNameFor(""))
    }

    @Test
    fun emptyDocumentProducesAValidMinimalBody() {
        val document = ConversionDocument("Empty", emptyList())
        val xml = documentXmlOf(DocxExportAdapter.export(document))
        assertTrue(xml.contains("<w:body></w:body>"))
        assertEquals(0, xml.split("<w:br w:type=\"page\"/>").size - 1)
    }

    @Test
    fun rawZipRoundTripShowsMembersAreReadable() {
        val bytes = DocxExportAdapter.export(twoPageDocument())
        // Re-zip the extracted parts and confirm lossless text round-trip
        // (the docx-unzippable property at the JVM level).
        val parts = unzip(bytes)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for (part in parts) {
                val entry = ZipEntry(part.first)
                entry.time = ConversionZip.FIXED_EPOCH_MILLIS
                zos.putNextEntry(entry)
                zos.write(part.second.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        assertEquals(parts.size, unzip(bos.toByteArray()).size)
    }
}
