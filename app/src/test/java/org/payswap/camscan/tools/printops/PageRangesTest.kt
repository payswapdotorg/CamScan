package org.payswap.camscan.tools.printops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// CAMSCAN-PROD-015 §6.6 — PageRanges coverage: the strict parser table —
// valid forms, invalid forms, duplicate handling, ascending order.

class PageRangesTest {

    // ------------------------------------------------ valid forms

    @Test
    fun allExpandsToEveryPage() {
        assertEquals(listOf(1, 2, 3), PageRanges.parse("all", 3))
    }

    @Test
    fun allIsTrimmed() {
        assertEquals(listOf(1), PageRanges.parse(" all ", 1))
    }

    @Test
    fun singleNumberSelectsOnePage() {
        assertEquals(listOf(2), PageRanges.parse("2", 5))
    }

    @Test
    fun inclusiveRangeExpands() {
        assertEquals(listOf(1, 2, 3), PageRanges.parse("1-3", 5))
    }

    @Test
    fun commaListMixesSinglesAndRanges() {
        assertEquals(listOf(1, 2, 3, 5), PageRanges.parse("1-3,5", 5))
    }

    @Test
    fun tokensAreTrimmed() {
        assertEquals(listOf(1, 2, 3, 5), PageRanges.parse(" 1-3 , 5 ", 5))
    }

    @Test
    fun duplicatesAreAllowedAndPreserved() {
        assertEquals(listOf(1, 2, 3, 3, 5), PageRanges.parse("5,1-3,3", 5))
    }

    @Test
    fun outputIsSortedAscendingRegardlessOfInputOrder() {
        assertEquals(listOf(1, 2, 4), PageRanges.parse("4,2,1", 5))
    }

    @Test
    fun singlePageRangeWithEqualBoundsSelectsOnePage() {
        assertEquals(listOf(3), PageRanges.parse("3-3", 5))
    }

    @Test
    fun lastPageIsSelectable() {
        assertEquals(listOf(5), PageRanges.parse("5", 5))
    }

    // ------------------------------------------------ invalid forms

    @Test
    fun emptyExpressionIsInvalid() {
        assertNull(PageRanges.parse("", 5))
    }

    @Test
    fun blankExpressionIsInvalid() {
        assertNull(PageRanges.parse("   ", 5))
    }

    @Test
    fun zeroPageIsInvalid() {
        assertNull(PageRanges.parse("0", 5))
    }

    @Test
    fun outOfRangeHighPageIsInvalid() {
        assertNull(PageRanges.parse("6", 5))
    }

    @Test
    fun rangeEndBeyondPageCountIsInvalid() {
        assertNull(PageRanges.parse("4-6", 5))
    }

    @Test
    fun reversedRangeIsInvalid() {
        assertNull(PageRanges.parse("3-1", 5))
    }

    @Test
    fun doubleDashedTokenIsInvalid() {
        assertNull(PageRanges.parse("1-3-5", 5))
    }

    @Test
    fun leadingDashIsInvalid() {
        assertNull(PageRanges.parse("-3", 5))
    }

    @Test
    fun trailingDashIsInvalid() {
        assertNull(PageRanges.parse("3-", 5))
    }

    @Test
    fun internalWhitespaceInsideTokenIsInvalid() {
        assertNull(PageRanges.parse("1 -3", 5))
        assertNull(PageRanges.parse("1- 3", 5))
    }

    @Test
    fun nonNumericTokenIsInvalid() {
        assertNull(PageRanges.parse("a", 5))
        assertNull(PageRanges.parse("1,b", 5))
    }

    @Test
    fun signedNumberIsInvalid() {
        assertNull(PageRanges.parse("+3", 5))
        assertNull(PageRanges.parse("-3", 5))
    }

    @Test
    fun allCombinedWithAnythingIsInvalid() {
        assertNull(PageRanges.parse("all,3", 5))
        assertNull(PageRanges.parse("1,all", 5))
    }

    @Test
    fun emptyTokenFromDoubleCommaIsInvalid() {
        assertNull(PageRanges.parse("1,,3", 5))
    }

    @Test
    fun trailingCommaIsInvalid() {
        assertNull(PageRanges.parse("1,", 5))
    }

    @Test
    fun absurdlyLongDigitStringIsInvalid() {
        assertNull(PageRanges.parse("123456789012345", 5))
    }

    @Test
    fun zeroPageCountMakesEverythingInvalid() {
        assertNull(PageRanges.parse("all", 0))
        assertNull(PageRanges.parse("1", 0))
    }

    @Test
    fun caseSensitiveAllKeyword() {
        // "ALL" is not the keyword — strict lowercase only.
        assertNull(PageRanges.parse("ALL", 3))
    }
}
