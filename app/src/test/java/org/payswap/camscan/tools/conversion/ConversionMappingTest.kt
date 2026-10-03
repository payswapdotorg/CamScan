package org.payswap.camscan.tools.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — ConversionMapping coverage: the documented
// blank-line-delimited paragraph segmentation rule and text joins.

class ConversionMappingTest {

    @Test
    fun paragraphsSplitOnBlankLines() {
        val page = ConversionPage(listOf("alpha line 1", "alpha line 2", "", "beta line 1"))
        val paragraphs = ConversionMapping.paragraphsOf(page)
        assertEquals(2, paragraphs.size)
        assertEquals(listOf("alpha line 1", "alpha line 2"), paragraphs[0])
        assertEquals(listOf("beta line 1"), paragraphs[1])
    }

    @Test
    fun consecutiveBlankLinesCollapseIntoOneSplit() {
        val page = ConversionPage(listOf("a", "", "", "", "b"))
        val paragraphs = ConversionMapping.paragraphsOf(page)
        assertEquals(2, paragraphs.size)
        assertEquals(listOf("a"), paragraphs[0])
        assertEquals(listOf("b"), paragraphs[1])
    }

    @Test
    fun leadingAndTrailingBlankLinesAreIgnored() {
        val page = ConversionPage(listOf("", "a", "", "b", "", ""))
        val paragraphs = ConversionMapping.paragraphsOf(page)
        assertEquals(2, paragraphs.size)
    }

    @Test
    fun allBlankPageYieldsNoParagraphs() {
        val page = ConversionPage(listOf("", "   ", "\t"))
        assertEquals(0, ConversionMapping.paragraphsOf(page).size)
    }

    @Test
    fun emptyPageYieldsNoParagraphs() {
        assertEquals(0, ConversionMapping.paragraphsOf(ConversionPage(emptyList())).size)
    }

    @Test
    fun whitespaceOnlyLinesAreBlankUnderTheDocumentedRule() {
        assertTrue(ConversionMapping.isBlankLine(" "))
        assertTrue(ConversionMapping.isBlankLine("\t "))
        assertTrue(!ConversionMapping.isBlankLine(" x "))
    }

    @Test
    fun paragraphTextsJoinLinesWithSingleLf() {
        val page = ConversionPage(listOf("one", "two", "", "three"))
        val texts = ConversionMapping.paragraphTexts(page)
        assertEquals(listOf("one\ntwo", "three"), texts)
    }

    @Test
    fun paragraphTextsOfEmptyPageIsEmpty() {
        assertEquals(0, ConversionMapping.paragraphTexts(ConversionPage(emptyList())).size)
    }

    @Test
    fun documentCarriesTitleAndOrderedPages() {
        val document = ConversionDocument(
            title = "Contract",
            pages = listOf(ConversionPage(listOf("p1")), ConversionPage(listOf("p2"))),
        )
        assertEquals("Contract", document.title)
        assertEquals(2, document.pages.size)
        assertEquals("p2", document.pages[1].lines[0])
    }
}
