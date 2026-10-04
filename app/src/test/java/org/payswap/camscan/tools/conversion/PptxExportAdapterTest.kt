package org.payswap.camscan.tools.conversion

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PPTX-017 — PptxExportAdapter coverage, mirroring the sibling
// suites: fixed OPC zip layout (entry order + timestamps), presentation
// rel graph (master/layout/theme/slides), slide mapping rule (one slide
// per page, first paragraph = title, remaining paragraphs = body, lines
// separated by a:br like the docx w:br discipline), XML escaping, empty
// documents, the names-registry registration, byte determinism and two
// pinned golden sha256 hashes.

class PptxExportAdapterTest {

    // Golden hashes are pinned constants: exporting the FIXED documents
    // below must reproduce byte streams with EXACTLY these sha256 values
    // in every environment. If an adapter change is deliberate, update
    // the pin in the same commit and say why.
    private val goldenTwoPageSha256 =
        "aeb6216f3159c94b0e6ed80d22da0952ae4c1ef3b66b27823bf5be5bbe21b064"
    private val goldenEmptySha256 =
        "f5a3ad494c9ea7233bcdab1147457a4823a657f662b895adb1cccd94657189b9"

    private val hexChars = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'a', 'b', 'c', 'd', 'e', 'f',
    )

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

    private fun partOf(bytes: ByteArray, name: String): String {
        for (part in unzip(bytes)) {
            if (part.first == name) return part.second
        }
        throw AssertionError(name + " missing")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val value = b.toInt() and 0xFF
            sb.append(hexChars[value ushr 4])
            sb.append(hexChars[value and 0x0F])
        }
        return sb.toString()
    }

    private fun twoPageDocument(): ConversionDocument = ConversionDocument(
        title = "Deck",
        pages = listOf(
            ConversionPage(listOf("first page line", "", "second para")),
            ConversionPage(listOf("second page line")),
        ),
    )

    private fun skeletonEntries(): List<String> = listOf(
        "[Content_Types].xml",
        "_rels/.rels",
        "ppt/presentation.xml",
        "ppt/_rels/presentation.xml.rels",
        "ppt/slideMasters/slideMaster1.xml",
        "ppt/slideMasters/_rels/slideMaster1.xml.rels",
        "ppt/slideLayouts/slideLayout1.xml",
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
        "ppt/theme/theme1.xml",
    )

    @Test
    fun zipEntryNamesAreTheFixedSkeletonPlusSlidePairsInOrder() {
        val parts = unzip(PptxExportAdapter.export(twoPageDocument()))
        assertEquals(
            skeletonEntries() + listOf(
                "ppt/slides/slide1.xml",
                "ppt/slides/_rels/slide1.xml.rels",
                "ppt/slides/slide2.xml",
                "ppt/slides/_rels/slide2.xml.rels",
            ),
            parts.map { p -> p.first },
        )
    }

    @Test
    fun emptyDocumentProducesOnlyTheSkeletonWithoutSlideParts() {
        val parts = unzip(PptxExportAdapter.export(ConversionDocument("Empty", emptyList())))
        assertEquals(skeletonEntries(), parts.map { p -> p.first })
    }

    @Test
    fun packageContainsNoDocPropsAndNothingOutsidePpt() {
        val parts = unzip(PptxExportAdapter.export(twoPageDocument()))
        for (part in parts) {
            val name = part.first
            val allowed = name == "[Content_Types].xml" || name == "_rels/.rels" || name.startsWith("ppt/")
            assertTrue("unexpected part " + name, allowed)
        }
    }

    @Test
    fun contentTypesDeclareThePresentationFamilyParts() {
        val contentTypes = partOf(PptxExportAdapter.export(twoPageDocument()), "[Content_Types].xml")
        assertTrue(contentTypes.contains("presentationml.presentation.main+xml"))
        assertTrue(contentTypes.contains("presentationml.slideMaster+xml"))
        assertTrue(contentTypes.contains("presentationml.slideLayout+xml"))
        assertTrue(contentTypes.contains("presentationml.slide+xml"))
        assertTrue(contentTypes.contains("application/vnd.openxmlformats-officedocument.theme+xml"))
        assertTrue(contentTypes.contains("/ppt/presentation.xml"))
        assertTrue(contentTypes.contains("application/vnd.openxmlformats-package.relationships+xml"))
    }

    @Test
    fun contentTypesGrowExactlyOneSlideOverridePerPage() {
        val document = ConversionDocument(
            "Three",
            listOf(
                ConversionPage(listOf("a")),
                ConversionPage(listOf("b")),
                ConversionPage(listOf("c")),
            ),
        )
        val contentTypes = partOf(PptxExportAdapter.export(document), "[Content_Types].xml")
        assertEquals(3, contentTypes.split("/ppt/slides/slide").size - 1)
        assertTrue(contentTypes.contains("/ppt/slides/slide3.xml"))
        assertTrue(!contentTypes.contains("/ppt/slides/slide4.xml"))
    }

    @Test
    fun packageRelsTargetThePresentationPart() {
        val rels = partOf(PptxExportAdapter.export(twoPageDocument()), "_rels/.rels")
        assertTrue(rels.contains("Target=\"ppt/presentation.xml\""))
        assertTrue(rels.contains("officeDocument"))
        assertEquals(1, rels.split("<Relationship ").size - 1)
    }

    @Test
    fun presentationDeclaresOneSlideIdPerPageWithSequentialIds() {
        val presentation = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/presentation.xml")
        assertEquals(2, presentation.split("<p:sldId ").size - 1)
        assertTrue(presentation.contains("<p:sldId id=\"256\" r:id=\"rId2\"/>"))
        assertTrue(presentation.contains("<p:sldId id=\"257\" r:id=\"rId3\"/>"))
        assertTrue(!presentation.contains("rId4\""))
    }

    @Test
    fun presentationCarriesTheMasterListAndFixedSixteenNineGeometry() {
        val presentation = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/presentation.xml")
        assertTrue(presentation.contains("<p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/>"))
        assertTrue(presentation.contains("<p:sldSz cx=\"12192000\" cy=\"6858000\" type=\"screen16x9\"/>"))
        assertTrue(presentation.contains("<p:notesSz cx=\"6858000\" cy=\"9144000\"/>"))
        // Schema order: the master list comes before the slide list.
        assertTrue(presentation.indexOf("<p:sldMasterIdLst>") < presentation.indexOf("<p:sldIdLst>"))
    }

    @Test
    fun presentationRelsTargetMasterSlidesAndThemeInOrder() {
        val rels = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/_rels/presentation.xml.rels")
        assertEquals(4, rels.split("<Relationship ").size - 1)
        assertTrue(rels.contains("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>"))
        assertTrue(rels.contains("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide1.xml\"/>"))
        assertTrue(rels.contains("<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide2.xml\"/>"))
        assertTrue(rels.contains("<Relationship Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>"))
    }

    @Test
    fun emptyDocumentPresentationRelsKeepMasterAndThemeOnly() {
        val rels = partOf(
            PptxExportAdapter.export(ConversionDocument("Empty", emptyList())),
            "ppt/_rels/presentation.xml.rels",
        )
        assertEquals(2, rels.split("<Relationship ").size - 1)
        assertTrue(rels.contains("Id=\"rId1\""))
        assertTrue(rels.contains("Id=\"rId2\""))
        assertTrue(rels.contains("theme/theme1.xml"))
        assertTrue(!rels.contains("slides/"))
    }

    @Test
    fun masterRelsTargetTheSharedLayoutAndTheme() {
        val rels = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/slideMasters/_rels/slideMaster1.xml.rels")
        assertEquals(2, rels.split("<Relationship ").size - 1)
        assertTrue(rels.contains("Target=\"../slideLayouts/slideLayout1.xml\""))
        assertTrue(rels.contains("Target=\"../theme/theme1.xml\""))
    }

    @Test
    fun layoutRelsTargetTheMaster() {
        val rels = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/slideLayouts/_rels/slideLayout1.xml.rels")
        assertEquals(1, rels.split("<Relationship ").size - 1)
        assertTrue(rels.contains("Target=\"../slideMasters/slideMaster1.xml\""))
    }

    @Test
    fun everySlideHasARelsFileTargetingTheSharedLayout() {
        val bytes = PptxExportAdapter.export(twoPageDocument())
        for (number in listOf(1, 2)) {
            val rels = partOf(bytes, "ppt/slides/_rels/slide" + number.toString() + ".xml.rels")
            assertEquals(1, rels.split("<Relationship ").size - 1)
            assertTrue(rels.contains("Target=\"../slideLayouts/slideLayout1.xml\""))
        }
    }

    @Test
    fun themePartIsAStructurallyCompleteMinimalConstant() {
        val theme = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/theme/theme1.xml")
        assertTrue(theme.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"))
        assertTrue(theme.contains("<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""))
        // The 12 required clrScheme slots, in schema order.
        for (tag in listOf(
            "<a:dk1>", "<a:lt1>", "<a:dk2>", "<a:lt2>",
            "<a:accent1>", "<a:accent2>", "<a:accent3>",
            "<a:accent4>", "<a:accent5>", "<a:accent6>",
            "<a:hlink>", "<a:folHlink>",
        )) {
            assertTrue(theme.contains(tag))
        }
        assertEquals(1, theme.split("<a:clrScheme").size - 1)
        // Font scheme: major + minor, each with latin/ea/cs typefaces.
        assertTrue(theme.contains("typeface=\"Calibri Light\""))
        assertTrue(theme.contains("typeface=\"Calibri\""))
        assertEquals(2, theme.split("<a:latin ").size - 1)
        // Format scheme: 3 fills, 3 lines, 3 effects, 3 background fills.
        val fillZone = theme.substring(theme.indexOf("<a:fillStyleLst>"), theme.indexOf("</a:fillStyleLst>"))
        val bgZone = theme.substring(theme.indexOf("<a:bgFillStyleLst>"))
        assertEquals(3, fillZone.split("<a:solidFill>").size - 1)
        assertEquals(3, bgZone.split("<a:solidFill>").size - 1)
        assertEquals(3, theme.split("<a:ln ").size - 1)
        assertEquals(3, theme.split("<a:effectStyle>").size - 1)
        assertTrue(theme.contains("<a:fillStyleLst>"))
        assertTrue(theme.contains("<a:lnStyleLst>"))
        assertTrue(theme.contains("<a:effectStyleLst>"))
        assertTrue(theme.contains("<a:bgFillStyleLst>"))
        assertTrue(theme.endsWith("</a:theme>"))
    }

    @Test
    fun masterAndLayoutCarryNoPlaceholderPrototypes() {
        val bytes = PptxExportAdapter.export(twoPageDocument())
        val master = partOf(bytes, "ppt/slideMasters/slideMaster1.xml")
        val layout = partOf(bytes, "ppt/slideLayouts/slideLayout1.xml")
        assertEquals(0, master.split("<p:sp>").size - 1)
        assertEquals(0, layout.split("<p:sp>").size - 1)
        assertTrue(master.contains("<p:clrMap "))
        assertTrue(layout.contains("type=\"blank\""))
        assertTrue(layout.contains("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"))
        // The master layout list points at rId1 of the MASTER's rels.
        assertTrue(master.contains("<p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/>"))
    }

    @Test
    fun slideCountMatchesPageCount() {
        val document = ConversionDocument(
            "Three",
            listOf(
                ConversionPage(listOf("a")),
                ConversionPage(emptyList()),
                ConversionPage(listOf("c")),
            ),
        )
        val parts = unzip(PptxExportAdapter.export(document))
        assertEquals(3, parts.count { p -> p.first.startsWith("ppt/slides/slide") && p.first.endsWith(".xml") })
        assertEquals(3, parts.count { p -> p.first.endsWith(".xml.rels") && p.first.startsWith("ppt/slides/_rels/") })
    }

    @Test
    fun slidesCarryTheDeclarationAndAllThreeNamespaces() {
        val slide = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/slides/slide1.xml")
        assertTrue(slide.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"))
        assertTrue(slide.contains("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""))
        assertTrue(slide.contains("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""))
        assertTrue(slide.contains("xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""))
        assertTrue(slide.endsWith("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"))
    }

    @Test
    fun firstParagraphBecomesTheTitlePlaceholder() {
        val slide = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/slides/slide1.xml")
        assertTrue(slide.contains("<p:ph type=\"title\"/>"))
        assertTrue(slide.contains("<a:t>first page line</a:t>"))
        assertTrue(slide.contains("<a:t>second para</a:t>"))
    }

    @Test
    fun remainingParagraphsBecomeBodyTextParagraphs() {
        val slide = partOf(PptxExportAdapter.export(twoPageDocument()), "ppt/slides/slide1.xml")
        assertTrue(slide.contains("<p:ph type=\"body\"/>"))
        // Paragraph 1 ("first page line") is the title; paragraph 2
        // ("second para") is the single body paragraph.
        val titleZone = slide.substring(slide.indexOf("<p:ph type=\"title\"/>"), slide.indexOf("<p:ph type=\"body\"/>"))
        val bodyZone = slide.substring(slide.indexOf("<p:ph type=\"body\"/>"))
        assertTrue(titleZone.contains("first page line"))
        assertTrue(!titleZone.contains("second para"))
        assertTrue(bodyZone.contains("second para"))
        assertTrue(!bodyZone.contains("first page line"))
    }

    @Test
    fun linesWithinAParagraphUseRunsWithBreaksBetweenLikeDocx() {
        val page = ConversionPage(listOf("line one", "line two"))
        val xml = PptxExportAdapter.slideXml(page)
        assertEquals(
            "<p:txBody><a:bodyPr/><a:lstStyle/>" +
                "<a:p>" +
                "<a:r><a:rPr lang=\"en-US\" sz=\"4400\"/><a:t>line one</a:t></a:r>" +
                "<a:br/>" +
                "<a:r><a:rPr lang=\"en-US\" sz=\"4400\"/><a:t>line two</a:t></a:r>" +
                "</a:p>" +
                "</p:txBody>",
            xml.substring(xml.indexOf("<p:txBody>"), xml.indexOf("</p:txBody>") + "</p:txBody>".length),
        )
    }

    @Test
    fun multiParagraphBodyGetsOneApPerRemainingParagraph() {
        val page = ConversionPage(listOf("t", "", "b1", "", "b2"))
        val xml = PptxExportAdapter.slideXml(page)
        val bodyZone = xml.substring(xml.indexOf("<p:ph type=\"body\"/>"))
        assertEquals(2, bodyZone.split("<a:p>").size - 1)
        assertTrue(bodyZone.contains("<a:t>b1</a:t>"))
        assertTrue(bodyZone.contains("<a:t>b2</a:t>"))
    }

    @Test
    fun titleAndBodyUseFixedFontSizesAndExplicitGeometry() {
        val xml = PptxExportAdapter.slideXml(ConversionPage(listOf("t", "", "b")))
        assertTrue(xml.contains("sz=\"4400\""))
        assertTrue(xml.contains("sz=\"2800\""))
        assertTrue(xml.contains("<a:off x=\"838200\" y=\"365125\"/>"))
        assertTrue(xml.contains("<a:ext cx=\"10477500\" cy=\"1325563\"/>"))
        assertTrue(xml.contains("<a:off x=\"838200\" y=\"1825610\"/>"))
        assertTrue(xml.contains("<a:ext cx=\"10477500\" cy=\"4476765\"/>"))
        assertTrue(xml.contains("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"))
    }

    @Test
    fun emptyPageYieldsSlideWithEmptyTitleAndBodyPlaceholders() {
        val xml = PptxExportAdapter.slideXml(ConversionPage(emptyList()))
        assertTrue(xml.contains("<p:ph type=\"title\"/>"))
        assertTrue(xml.contains("<p:ph type=\"body\"/>"))
        // Both placeholders exist but carry NO text runs at all.
        assertEquals(0, xml.split("<a:r>").size - 1)
        assertEquals(0, xml.split("<a:t>").size - 1)
        assertEquals(2, xml.split("<a:endParaRPr").size - 1)
        assertTrue(xml.contains("<a:endParaRPr lang=\"en-US\" sz=\"4400\"/>"))
        assertTrue(xml.contains("<a:endParaRPr lang=\"en-US\" sz=\"2800\"/>"))
    }

    @Test
    fun allBlankPageBehavesExactlyLikeAnEmptyPage() {
        val xml = PptxExportAdapter.slideXml(ConversionPage(listOf("", "   ", "\t")))
        assertEquals(0, xml.split("<a:r>").size - 1)
        assertEquals(2, xml.split("<a:endParaRPr").size - 1)
    }

    @Test
    fun escapingHitsAmpersandLessAndGreater() {
        val xml = PptxExportAdapter.slideXml(ConversionPage(listOf("a & b < c > d")))
        assertTrue(xml.contains("a &amp; b &lt; c &gt; d"))
        assertTrue(!xml.contains("a & b"))
    }

    @Test
    fun quotesAndApostrophesPassThroughUnescapedLikeTheSharedRule() {
        val xml = PptxExportAdapter.slideXml(ConversionPage(listOf("it's \"quoted\"")))
        assertTrue(xml.contains("<a:t>it's \"quoted\"</a:t>"))
    }

    @Test
    fun unicodeTextSurvivesTheZipRoundTrip() {
        val document = ConversionDocument(
            "U",
            listOf(ConversionPage(listOf("Ünïcödé 東京 résumé © 2026"))),
        )
        val slide = partOf(PptxExportAdapter.export(document), "ppt/slides/slide1.xml")
        assertTrue(slide.contains("<a:t>Ünïcödé 東京 résumé © 2026</a:t>"))
    }

    @Test
    fun everyPageLandsInItsOwnSlideInOrder() {
        val document = ConversionDocument(
            "S",
            listOf(
                ConversionPage(listOf("page1-line")),
                ConversionPage(listOf("page2-line")),
            ),
        )
        val bytes = PptxExportAdapter.export(document)
        val slide1 = partOf(bytes, "ppt/slides/slide1.xml")
        val slide2 = partOf(bytes, "ppt/slides/slide2.xml")
        assertTrue(slide1.contains("page1-line"))
        assertTrue(!slide1.contains("page2-line"))
        assertTrue(slide2.contains("page2-line"))
        assertTrue(!slide2.contains("page1-line"))
    }

    @Test
    fun tenPageDocumentsNumberSlidesSequentiallyWithoutPadding() {
        val pages = (1..10).map { number -> ConversionPage(listOf("line " + number.toString())) }
        val bytes = PptxExportAdapter.export(ConversionDocument("Ten", pages))
        val names = unzip(bytes).map { p -> p.first }
        assertTrue(names.contains("ppt/slides/slide10.xml"))
        assertTrue(names.contains("ppt/slides/_rels/slide10.xml.rels"))
        assertTrue(!names.contains("ppt/slides/slide010.xml"))
        val presentation = partOf(bytes, "ppt/presentation.xml")
        assertTrue(presentation.contains("<p:sldId id=\"265\" r:id=\"rId11\"/>"))
        val rels = partOf(bytes, "ppt/_rels/presentation.xml.rels")
        assertTrue(rels.contains("Target=\"slides/slide10.xml\""))
        assertTrue(rels.contains("<Relationship Id=\"rId12\""))
    }

    @Test
    fun twoExportsOfTheSameDocumentAreByteIdentical() {
        val document = twoPageDocument()
        val first = PptxExportAdapter.export(document)
        val second = PptxExportAdapter.export(document)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun everyZipEntryCarriesTheFixedTimestamp() {
        val bytes = PptxExportAdapter.export(twoPageDocument())
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                assertEquals(ConversionZip.FIXED_EPOCH_MILLIS, entry.time)
                zis.closeEntry()
            }
        }
    }

    @Test
    fun goldenSha256OfTheFixedTwoPageDocument() {
        assertEquals(goldenTwoPageSha256, sha256Hex(PptxExportAdapter.export(twoPageDocument())))
    }

    @Test
    fun goldenSha256OfTheFixedEmptyDocument() {
        val document = ConversionDocument("Empty", emptyList())
        assertEquals(goldenEmptySha256, sha256Hex(PptxExportAdapter.export(document)))
    }

    @Test
    fun fileNameForSanitizesAndAppendsThePptxExtension() {
        assertEquals("Deck 2026.pptx", PptxExportAdapter.fileNameFor("Deck 2026"))
        assertEquals("a-b.pptx", PptxExportAdapter.fileNameFor("a/b"))
        assertEquals("document.pptx", PptxExportAdapter.fileNameFor(""))
        assertEquals("document.pptx", PptxExportAdapter.fileNameFor("   "))
    }

    @Test
    fun exportExtensionsRegistryListsAllThreeOfficeFormatsAppendOnly() {
        assertEquals(listOf(".docx", ".xlsx", ".pptx"), ConversionNames.EXPORT_EXTENSIONS)
        assertTrue(ConversionNames.EXPORT_EXTENSIONS.contains(DocxExportAdapter.FILE_EXTENSION))
        assertTrue(ConversionNames.EXPORT_EXTENSIONS.contains(XlsxExportAdapter.FILE_EXTENSION))
        assertTrue(ConversionNames.EXPORT_EXTENSIONS.contains(PptxExportAdapter.FILE_EXTENSION))
        // Append-only: the wave-4 pair keeps its documented default name.
        assertEquals("document", ConversionNames.DEFAULT_BASE_NAME)
    }

    @Test
    fun adapterMirrorsTheSiblingNamingSeam() {
        // Same programmatic seam as the siblings: export(document) bytes +
        // fileNameFor(title) built on the shared sanitizer.
        assertEquals(ConversionNames.sanitizeFileName("Q&A: Deck") + ".pptx", PptxExportAdapter.fileNameFor("Q&A: Deck"))
    }
}
