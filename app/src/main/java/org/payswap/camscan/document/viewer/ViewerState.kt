package org.payswap.camscan.document.viewer

import org.payswap.camscan.core.model.Document

/**

Android-free viewer state and pure transitions (CAMSCAN-PROD-006).
Unit-tested on the JVM; fragments stay thin.
*/

/** One renderable page in viewer order. */
data class ViewerPageItem(
val pageId: String,
val index: Int,
val processedImageRef: String?,
val rotationDegrees: Int,
)

/** Immutable viewer UI state. /
data class ViewerUiState(
val documentId: String,
val title: String,
val pages: List<ViewerPageItem> = emptyList(),
val currentPosition: Int = 0,
) {
/* One-based "3 / 7" indicator; empty when there are no pages. */
val indicatorText: String
get() = if (pages.isEmpty()) "" else "
currentPosition+1/
{pages.size}"

val isEmpty: Boolean get() = pages.isEmpty()

val currentPage: ViewerPageItem? get() = pages.getOrNull(currentPosition)

fun withPages(pages: List<ViewerPageItem>): ViewerUiState =
copy(pages = pages, currentPosition = currentPosition.coerceIn(0, pages.lastIndexOrZero()))

fun withPosition(position: Int): ViewerUiState =
copy(currentPosition = position.coerceIn(0, pages.lastIndexOrZero()))

private fun List<*>.lastIndexOrZero(): Int = (size - 1).coerceAtLeast(0)

}

object ViewerOps {

/** Builds viewer state from a document, sorting pages by index. */
fun fromDocument(document: Document, currentPosition: Int = 0): ViewerUiState {
val items = document.pages
.sortedBy { it.index }
.map { page ->
ViewerPageItem(
pageId = page.id,
index = page.index,
processedImageRef = page.processedImageRef,
rotationDegrees = page.rotationDegrees,
)
}
return ViewerUiState(
documentId = document.id,
title = document.title,
pages = items,
currentPosition = 0,
).withPosition(currentPosition)
}

/**
* Moves [pageId] by [offset] within [pages] (list order = current order).
* Returns the re-indexed list, or null for zero offset, unknown page, or
* a move past either boundary.
*/
fun moved(pages: List<ViewerPageItem>, pageId: String, offset: Int): List<ViewerPageItem>? {
if (offset == 0 || pages.isEmpty()) return null
val from = pages.indexOfFirst { it.pageId == pageId }
if (from < 0) return null
val to = from + offset
if (to < 0 || to >= pages.size) return null
val mutable = pages.toMutableList()
val moved = mutable.removeAt(from)
mutable.add(to, moved)
return mutable.mapIndexed { index, item -> item.copy(index = index) }
}

