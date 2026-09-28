package org.payswap.camscan.document.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page

/**

Android-free viewer state tests (CAMSCAN-PROD-006): indicator formatting,

position clamping, and the pure reorder transitions the fragments consume.
*/
class ViewerStateTest {

private fun item(id: String, index: Int) = ViewerPageItem(
pageId = id,
index = index,
processedImageRef = "ref-$id",
rotationDegrees = 0,
)

private fun state(pages: List<ViewerPageItem>, position: Int = 0): ViewerUiState =
ViewerUiState(
documentId = "doc-1",
title = "Title",
pages = pages,
currentPosition = position,
)
private fun documentWithPages(vararg idsAndIndexes: Pair<String, Int>): Document =
Document(
id = "doc-1",
title = "Quarterly",
createdAtMillis = 1L,
updatedAtMillis = 2L,
pages = idsAndIndexes.map { (id, index) -> Page(id = id, index = index) },
)
@Test
fun indicatorText_formatsOneBasedPosition() {
val pages = listOf(item("a", 0), item("b", 1), item("c", 2))
assertEquals("1 / 3", state(pages).indicatorText)
assertEquals("2 / 3", state(pages, position = 1).indicatorText)
assertEquals("3 / 3", state(pages, position = 2).indicatorText)
}

@Test
fun indicatorText_isEmpty_whenNoPages() {
assertEquals("", state(emptyList()).indicatorText)
}

@Test
fun withPosition_clampsToBounds() {
val pages = listOf(item("a", 0), item("b", 1), item("c", 2))
assertEquals(2, state(pages).withPosition(5).currentPosition)
assertEquals(0, state(pages).withPosition(-3).currentPosition)
assertEquals(1, state(pages).withPosition(1).currentPosition)
}

@Test
fun withPages_clampsPosition_whenListShrinks() {
val pages = listOf(item("a", 0), item("b", 1), item("c", 2))
val shrunk = state(pages, position = 2).withPages(listOf(item("x", 0)))
assertEquals(0, shrunk.currentPosition)
assertEquals("1 / 1", shrunk.indicatorText)
}

@Test
fun isEmpty_andCurrentPage_trackPages() {
val empty = state(emptyList())
assertTrue(empty.isEmpty)
assertNull(empty.currentPage)
val full = state(listOf(item("a", 0), item("b", 1)))
assertFalse(full.isEmpty)
assertEquals(item("a", 0), full.currentPage)
}

@Test
fun fromDocument_sortsByIndex_andCarriesTitle() {
val next = ViewerOps.fromDocument(
documentWithPages("a" to 2, "b" to 0, "c" to 1),
)
assertEquals(listOf("b", "c", "a"), next.pages.map { it.pageId })
assertEquals(listOf(0, 1, 2), next.pages.map { it.index })
assertEquals("Quarterly", next.title)
assertEquals("1 / 3", next.indicatorText)
}

@Test
fun moved_relocatesAndReindexes() {
val pages = listOf(item("a", 0), item("b", 1), item("c", 2))
val moved = ViewerOps.moved(pages, "a", +1)!!
assertEquals(listOf("b", "a", "c"), moved.map { it.pageId })
assertEquals(listOf(0, 1, 2), moved.map { it.index })
val movedBack = ViewerOps.moved(moved, "a", -1)!!
assertEquals(listOf("a", "b", "c"), movedBack.map { it.pageId })
}

@Test
fun moved_illegalMovesReturnNull() {
val pages = listOf(item("a", 0), item("b", 1), item("c", 2))
assertNull(ViewerOps.moved(pages, "a", 0))
assertNull(ViewerOps.moved(pages, "zz", 1))
assertNull(ViewerOps.moved(pages, "a", -1))
assertNull(ViewerOps.moved(pages, "c", +1))
assertNull(ViewerOps.moved(emptyList(), "a", 1))
}

@Test
fun fromDocument_honorsInitialPosition_andClamps() {
assertEquals(
1,
ViewerOps.fromDocument(
documentWithPages("a" to 0, "b" to 1), currentPosition = 1,
).currentPosition,
)
assertEquals(
1,
ViewerOps.fromDocument(
documentWithPages("a" to 0, "b" to 1), currentPosition = 9,
).currentPosition,
)
assertEquals(
0,
ViewerOps.fromDocument(
documentWithPages("a" to 0, "b" to 1), currentPosition = -4,
).currentPosition,
)
}

}
