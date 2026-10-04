package org.payswap.camscan.tools.conversion

// CAMSCAN-PPTX-017 — PptxExportAdapter: minimal PresentationML .pptx
// export from OCR text, completing the wave-4 Office conversion trio
// (docx/xlsx siblings in this package). Package layout (fixed order):
//   1. [Content_Types].xml
//   2. _rels/.rels                (officeDocument -> ppt/presentation.xml)
//   3. ppt/presentation.xml       (master list + one sldId per page)
//   4. ppt/_rels/presentation.xml.rels (rId1 master, rId2..rIdN+1 slides,
//                                      rIdN+2 theme)
//   5. ppt/slideMasters/slideMaster1.xml (+ its rels: layout + theme)
//   6. ppt/slideLayouts/slideLayout1.xml (+ its rels: master)
//   7. ppt/theme/theme1.xml       (fixed minimal constant, see themeXml)
//   8. ppt/slides/slideN.xml + ppt/slides/_rels/slideN.xml.rels, one pair
//      per page in page order, N sequential from 1 without padding.
// Slide mapping rule (documented, tested): ONE slide per page, mirroring
// the page-per-section discipline of the siblings. The page's paragraphs
// come from [ConversionMapping] (blank-line-delimited segmentation, the
// exact rule the docx adapter uses). The FIRST paragraph becomes the
// TITLE placeholder; every remaining paragraph becomes one body text
// paragraph (<a:p>) in the BODY placeholder; lines within a paragraph
// are runs separated by <a:br/> — the pptx mirror of the docx w:br
// discipline. A page with no paragraphs yields a slide whose title and
// body placeholders carry empty endParaRPr paragraphs (shapes always
// present, never any text runs). The shared input model carries no
// heading metadata, so "heading-ish" is honestly approximated as "first
// paragraph" — documented here, not papered over.
// Deterministic bytes: fixed entry order + fixed zip timestamps via
// [ConversionZip], fixed XML byte stream (single-line concatenated XML,
// zero injected whitespace, zero randomness). XML built with EXPLICIT
// string concatenation and the minimal escaping of [ConversionXml]
// (only ampersand, less, greater). No string templates exist in this
// file (transit-corruption protocol).
// HONEST SCOPE: text-level conversion only — NO images, NO charts, NO
// per-slide layouts beyond the one shared minimal blank layout, NO
// docProps parts, NO notes slides. The slideMaster/slideLayout carry NO
// placeholder prototypes; both slide shapes therefore carry EXPLICIT
// fixed geometry (EMU constants below) instead of inherited geometry.
// Parity vs the reference converter is UNVERIFIED.

/** Minimal deterministic PresentationML (.pptx) export from OCR text. */
object PptxExportAdapter {

    /** File extension of the exported artifact. */
    const val FILE_EXTENSION = ".pptx"

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private const val CONTENT_TYPES_ENTRY = "[Content_Types].xml"
    private const val RELS_ENTRY = "_rels/.rels"
    private const val PRESENTATION_ENTRY = "ppt/presentation.xml"
    private const val PRESENTATION_RELS_ENTRY = "ppt/_rels/presentation.xml.rels"
    private const val MASTER_ENTRY = "ppt/slideMasters/slideMaster1.xml"
    private const val MASTER_RELS_ENTRY = "ppt/slideMasters/_rels/slideMaster1.xml.rels"
    private const val LAYOUT_ENTRY = "ppt/slideLayouts/slideLayout1.xml"
    private const val LAYOUT_RELS_ENTRY = "ppt/slideLayouts/_rels/slideLayout1.xml.rels"
    private const val THEME_ENTRY = "ppt/theme/theme1.xml"
    private const val SLIDE_ENTRY_PREFIX = "ppt/slides/slide"
    private const val SLIDE_ENTRY_SUFFIX = ".xml"
    private const val SLIDE_RELS_DIR = "ppt/slides/_rels/slide"
    private const val SLIDE_RELS_SUFFIX = ".xml.rels"

    private const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

    private const val OFFICE_DOC_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    private const val SLIDE_MASTER_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
    private const val SLIDE_LAYOUT_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
    private const val SLIDE_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val THEME_REL =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme"

    private const val PRESENTATION_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private const val SLIDE_MASTER_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
    private const val SLIDE_LAYOUT_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
    private const val THEME_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.theme+xml"
    private const val SLIDE_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"

    // Fixed 16:9 slide geometry in EMU (12192000 x 6858000 = 13.333in x 7.5in).
    // Title box and body box constants mirror the standard PowerPoint 16:9
    // title/content bands; they are FIXED constants so the byte stream never
    // varies. Shape ids inside a slide: 1 = spTree root group, 2 = title, 3 = body.
    private const val SLIDE_WIDTH_EMU = "12192000"
    private const val SLIDE_HEIGHT_EMU = "6858000"
    private const val TITLE_OFF_X = "838200"
    private const val TITLE_OFF_Y = "365125"
    private const val TITLE_EXT_CX = "10477500"
    private const val TITLE_EXT_CY = "1325563"
    private const val BODY_OFF_X = "838200"
    private const val BODY_OFF_Y = "1825610"
    private const val BODY_EXT_CX = "10477500"
    private const val BODY_EXT_CY = "4476765"
    private const val TITLE_FONT_SIZE = "4400"
    private const val BODY_FONT_SIZE = "2800"
    private const val TEXT_LANGUAGE = "en-US"

    // sldMasterId ids are 4-byte unsigned values >= 2147483648; the sldId
    // ids of slides are < 2147483648 and >= 256. Fixed constants below.
    private const val MASTER_LAYOUT_LIST_ID = "2147483648"
    private const val LAYOUT_LIST_ID = "2147483649"
    private const val FIRST_SLIDE_ID = 256

    // The empty group shape tree every spTree starts with (master, layout,
    // slides): nvGrpSpPr + grpSpPr with the identity transform, no shapes.
    private const val EMPTY_GROUP_TREE =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    /** Exports the document as deterministic minimal .pptx bytes. */
    fun export(document: ConversionDocument): ByteArray {
        val parts = mutableListOf<Pair<String, String>>()
        parts.add(CONTENT_TYPES_ENTRY to contentTypesXml(document))
        parts.add(RELS_ENTRY to relsXml())
        parts.add(PRESENTATION_ENTRY to presentationXml(document))
        parts.add(PRESENTATION_RELS_ENTRY to presentationRelsXml(document))
        parts.add(MASTER_ENTRY to masterXml())
        parts.add(MASTER_RELS_ENTRY to masterRelsXml())
        parts.add(LAYOUT_ENTRY to layoutXml())
        parts.add(LAYOUT_RELS_ENTRY to layoutRelsXml())
        parts.add(THEME_ENTRY to themeXml())
        for (pageIndex in document.pages.indices) {
            val number = (pageIndex + 1).toString()
            parts.add(SLIDE_ENTRY_PREFIX + number + SLIDE_ENTRY_SUFFIX to slideXml(document.pages[pageIndex]))
            parts.add(SLIDE_RELS_DIR + number + SLIDE_RELS_SUFFIX to slideRelsXml())
        }
        return ConversionZip.build(parts)
    }

    /** Deterministic file name: sanitized title + ".pptx". */
    fun fileNameFor(title: String): String {
        return ConversionNames.sanitizeFileName(title) + FILE_EXTENSION
    }

    private fun contentTypesXml(document: ConversionDocument): String {
        val overrides = StringBuilder()
        for (pageIndex in document.pages.indices) {
            val number = (pageIndex + 1).toString()
            overrides.append("<Override PartName=\"/ppt/slides/slide" + number + ".xml\" ContentType=\"")
                .append(SLIDE_CONTENT_TYPE)
                .append("\"/>")
        }
        return XML_DECLARATION +
            "<Types xmlns=\"" + PKG_NS + "\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"" + PRESENTATION_CONTENT_TYPE + "\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"" + SLIDE_MASTER_CONTENT_TYPE + "\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"" + SLIDE_LAYOUT_CONTENT_TYPE + "\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"" + THEME_CONTENT_TYPE + "\"/>" +
            overrides.toString() +
            "</Types>"
    }

    private fun relsXml(): String {
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            "<Relationship Id=\"rId1\" Type=\"" + OFFICE_DOC_REL + "\" Target=\"ppt/presentation.xml\"/>" +
            "</Relationships>"
    }

    private fun presentationXml(document: ConversionDocument): String {
        val slideIds = StringBuilder()
        for (pageIndex in document.pages.indices) {
            val slideId = (FIRST_SLIDE_ID + pageIndex).toString()
            val relId = slideRelationshipId(pageIndex)
            slideIds.append("<p:sldId id=\"" + slideId + "\" r:id=\"" + relId + "\"/>")
        }
        return XML_DECLARATION +
            "<p:presentation xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\" xmlns:p=\"" + P_NS + "\">" +
            "<p:sldMasterIdLst>" +
            "<p:sldMasterId id=\"" + MASTER_LAYOUT_LIST_ID + "\" r:id=\"rId1\"/>" +
            "</p:sldMasterIdLst>" +
            "<p:sldIdLst>" +
            slideIds.toString() +
            "</p:sldIdLst>" +
            "<p:sldSz cx=\"" + SLIDE_WIDTH_EMU + "\" cy=\"" + SLIDE_HEIGHT_EMU + "\" type=\"screen16x9\"/>" +
            "<p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
            "</p:presentation>"
    }

    // Relationship id layout in presentation.xml.rels (fixed, documented):
    // rId1 = slideMaster, rId2..rId(N+1) = slides/slide1..slideN in page
    // order, rId(N+2) = theme. N = page count; an empty document therefore
    // keeps rId1 (master) and rId2 (theme) only.
    private fun slideRelationshipId(pageIndex: Int): String {
        return "rId" + (pageIndex + 2).toString()
    }

    private fun presentationRelsXml(document: ConversionDocument): String {
        val relationships = StringBuilder()
        relationships.append("<Relationship Id=\"rId1\" Type=\"" + SLIDE_MASTER_REL + "\" Target=\"slideMasters/slideMaster1.xml\"/>")
        for (pageIndex in document.pages.indices) {
            val number = (pageIndex + 1).toString()
            relationships.append("<Relationship Id=\"" + slideRelationshipId(pageIndex) + "\" Type=\"" + SLIDE_REL + "\" Target=\"slides/slide" + number + ".xml\"/>")
        }
        val themeRelationshipId = "rId" + (document.pages.size + 2).toString()
        relationships.append("<Relationship Id=\"" + themeRelationshipId + "\" Type=\"" + THEME_REL + "\" Target=\"theme/theme1.xml\"/>")
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            relationships.toString() +
            "</Relationships>"
    }

    // slideMaster1.xml: minimal VALID CT_SlideMaster — cSld with an empty
    // spTree, the required clrMap (identity mapping onto the theme slots),
    // and sldLayoutIdLst pointing at the single shared blank layout via the
    // master's rId1. NO placeholder prototypes and NO txStyles are modeled
    // (honest open: slides carry explicit geometry instead of inheritance).
    private fun masterXml(): String {
        return XML_DECLARATION +
            "<p:sldMaster xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\" xmlns:p=\"" + P_NS + "\">" +
            "<p:cSld>" +
            "<p:spTree>" + EMPTY_GROUP_TREE + "</p:spTree>" +
            "</p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst>" +
            "<p:sldLayoutId id=\"" + LAYOUT_LIST_ID + "\" r:id=\"rId1\"/>" +
            "</p:sldLayoutIdLst>" +
            "</p:sldMaster>"
    }

    private fun masterRelsXml(): String {
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            "<Relationship Id=\"rId1\" Type=\"" + SLIDE_LAYOUT_REL + "\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"" + THEME_REL + "\" Target=\"../theme/theme1.xml\"/>" +
            "</Relationships>"
    }

    // slideLayout1.xml: minimal VALID CT_SlideLayout of type blank — cSld
    // with an empty spTree (NO placeholder prototypes) and the required
    // masterClrMapping override.
    private fun layoutXml(): String {
        return XML_DECLARATION +
            "<p:sldLayout xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\" xmlns:p=\"" + P_NS + "\" type=\"blank\" showMasterSp=\"0\">" +
            "<p:cSld name=\"Blank\">" +
            "<p:spTree>" + EMPTY_GROUP_TREE + "</p:spTree>" +
            "</p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
            "</p:sldLayout>"
    }

    private fun layoutRelsXml(): String {
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            "<Relationship Id=\"rId1\" Type=\"" + SLIDE_MASTER_REL + "\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
            "</Relationships>"
    }

    private fun slideRelsXml(): String {
        return XML_DECLARATION +
            "<Relationships xmlns=\"" + RELS_NS + "\">" +
            "<Relationship Id=\"rId1\" Type=\"" + SLIDE_LAYOUT_REL + "\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "</Relationships>"
    }

    // theme1.xml: a FIXED minimal constant, VALID against the DrawingML
    // CT_OfficeStyleSheet schema so a strict OPC consumer can walk the
    // whole package: themeElements carries (a) a complete 12-slot clrScheme
    // (dk1/lt1 via sysClr, dk2/lt2 + 6 accents + hlink/folHlink via the
    // standard Office srgbClr palette), (b) a fontScheme whose major/minor
    // collections each declare the required latin/ea/cs typefaces, and
    // (c) a fmtScheme with the schema-required 3 fills / 3 lines / 3 effect
    // styles / 3 background fills (phClr solidFills and flat ln strokes).
    // GUARANTEE: schema-shape validity + walkability only — visual theme
    // fidelity to any real Office theme is explicitly NOT guaranteed.
    private fun themeXml(): String {
        return XML_DECLARATION +
            "<a:theme xmlns:a=\"" + A_NS + "\" name=\"Office\">" +
            "<a:themeElements>" +
            "<a:clrScheme name=\"Office\">" +
            "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
            "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2>" +
            "<a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1>" +
            "<a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3>" +
            "<a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5>" +
            "<a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink>" +
            "<a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
            "</a:clrScheme>" +
            "<a:fontScheme name=\"Office\">" +
            "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>" +
            "</a:fontScheme>" +
            "<a:fmtScheme name=\"Office\">" +
            "<a:fillStyleLst>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"95000\"/></a:schemeClr></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"90000\"/></a:schemeClr></a:solidFill>" +
            "</a:fillStyleLst>" +
            "<a:lnStyleLst>" +
            "<a:ln w=\"9525\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"95000\"/></a:schemeClr></a:solidFill><a:prstDash val=\"solid\"/></a:ln>" +
            "<a:ln w=\"25400\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>" +
            "<a:ln w=\"38100\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>" +
            "</a:lnStyleLst>" +
            "<a:effectStyleLst>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "</a:effectStyleLst>" +
            "<a:bgFillStyleLst>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"95000\"/></a:schemeClr></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"90000\"/></a:schemeClr></a:solidFill>" +
            "</a:bgFillStyleLst>" +
            "</a:fmtScheme>" +
            "</a:themeElements>" +
            "</a:theme>"
    }

    // Slide XML: title shape (ph type="title") carries the FIRST paragraph,
    // body shape (ph type="body") carries one <a:p> per REMAINING paragraph;
    // both carry explicit fixed geometry because the shared layout has no
    // placeholder prototypes. Empty pages keep both shapes with empty
    // endParaRPr paragraphs. Text runs use fixed font sizes (title 4400,
    // body 2800) and a fixed language tag for byte determinism.
    internal fun slideXml(page: ConversionPage): String {
        val paragraphs = ConversionMapping.paragraphsOf(page)
        val titleParagraph = if (paragraphs.isEmpty()) emptyList() else paragraphs[0]
        val bodyText = StringBuilder()
        if (paragraphs.size <= 1) {
            bodyText.append(emptyTextParagraph(BODY_FONT_SIZE))
        } else {
            for (paragraphIndex in 1 until paragraphs.size) {
                bodyText.append(textParagraph(paragraphs[paragraphIndex], BODY_FONT_SIZE))
            }
        }
        return XML_DECLARATION +
            "<p:sld xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\" xmlns:p=\"" + P_NS + "\">" +
            "<p:cSld>" +
            "<p:spTree>" +
            EMPTY_GROUP_TREE +
            titleShapeXml(textParagraph(titleParagraph, TITLE_FONT_SIZE)) +
            bodyShapeXml(bodyText.toString()) +
            "</p:spTree>" +
            "</p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
            "</p:sld>"
    }

    private fun titleShapeXml(titleParagraphXml: String): String {
        return "<p:sp>" +
            "<p:nvSpPr>" +
            "<p:cNvPr id=\"2\" name=\"Title 1\"/>" +
            "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>" +
            "<p:nvPr><p:ph type=\"title\"/></p:nvPr>" +
            "</p:nvSpPr>" +
            "<p:spPr>" +
            "<a:xfrm><a:off x=\"" + TITLE_OFF_X + "\" y=\"" + TITLE_OFF_Y + "\"/>" +
            "<a:ext cx=\"" + TITLE_EXT_CX + "\" cy=\"" + TITLE_EXT_CY + "\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "</p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/>" +
            titleParagraphXml +
            "</p:txBody>" +
            "</p:sp>"
    }

    private fun bodyShapeXml(bodyParagraphsXml: String): String {
        return "<p:sp>" +
            "<p:nvSpPr>" +
            "<p:cNvPr id=\"3\" name=\"Body 2\"/>" +
            "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>" +
            "<p:nvPr><p:ph type=\"body\"/></p:nvPr>" +
            "</p:nvSpPr>" +
            "<p:spPr>" +
            "<a:xfrm><a:off x=\"" + BODY_OFF_X + "\" y=\"" + BODY_OFF_Y + "\"/>" +
            "<a:ext cx=\"" + BODY_EXT_CX + "\" cy=\"" + BODY_EXT_CY + "\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "</p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/>" +
            bodyParagraphsXml +
            "</p:txBody>" +
            "</p:sp>"
    }

    // One <a:p> whose lines are runs separated by <a:br/> — the mirror of
    // the docx adapter's one-w:p-with-w:br rendering discipline.
    private fun textParagraph(lines: List<String>, fontSize: String): String {
        if (lines.isEmpty()) {
            return emptyTextParagraph(fontSize)
        }
        val sb = StringBuilder()
        sb.append("<a:p>")
        for (lineIndex in lines.indices) {
            if (lineIndex > 0) {
                sb.append("<a:br/>")
            }
            sb.append("<a:r><a:rPr lang=\"" + TEXT_LANGUAGE + "\" sz=\"" + fontSize + "\"/><a:t>")
                .append(ConversionXml.escape(lines[lineIndex]))
                .append("</a:t></a:r>")
        }
        sb.append("</a:p>")
        return sb.toString()
    }

    private fun emptyTextParagraph(fontSize: String): String {
        return "<a:p><a:endParaRPr lang=\"" + TEXT_LANGUAGE + "\" sz=\"" + fontSize + "\"/></a:p>"
    }
}
