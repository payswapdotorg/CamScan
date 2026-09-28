package org.payswap.camscan.document.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.core.model.Corner
import org.payswap.camscan.core.model.Document
import org.payswap.camscan.core.model.Page
import org.payswap.camscan.core.model.PageEnhancementMode

/**

JVM tests for the hand-rolled, byte-stable index JSON codec.

Aligned to the FROZEN model: [Document] carries no pages (pages travel as

an explicit map keyed by document id) and Page.cropQuad is the frozen

List<Corner>?, serialized as a flat corner array.
*/
class IndexJsonCodecTest {

private fun fullDocument(): Document = Document(
id = "doc-1",
title = "Quarterly "Report" \ Q3\nLine2\tTabbed",
createdAtMillis = 1_000L,
updatedAtMillis = 2_500L,
)

private fun fullPages(): List<Page> = listOf(
Page(
id = "p-0",
index = 0,
sourceCaptureRef = "cap-0.bin",
processedImageRef = "proc-0.bin",
thumbnailRef = "thumb-0.bin",
cropQuad = listOf(
Corner(0.1f, 0.2f),
Corner(0.9f, 0.3f),
Corner(0.8f, 0.7f),
Corner(0.2f, 0.6f),
),
enhancement = PageEnhancementMode.GRAYSCALE,
rotationDegrees = 90,
widthPx = 2480,
heightPx = 3508,
ocrResultId = "ocr-1",
),
Page(id = "p-1", index = 1),
)

@Test
fun fullFieldRoundtrip_preservesEverything() {
val json = IndexJsonCodec.serialize(listOf(fullDocument()), mapOf("doc-1" to fullPages()))

val snapshot = IndexJsonCodec.deserialize(json)

assertEquals(listOf(fullDocument()), snapshot.documents)
assertEquals(mapOf("doc-1" to fullPages()), snapshot.pagesByDocumentId)
}

@Test
fun nastyStrings_roundtrip() {
val title = "quote" back\slash\nnewline\rCR\ttabctrl日本語🙂"
val doc = Document(id = "d", title = title, createdAtMillis = 1, updatedAtMillis = 2)

val json = IndexJsonCodec.serialize(listOf(doc), emptyMap())

assertEquals(listOf(doc), IndexJsonCodec.deserialize(json).documents)
}

@Test
fun serialization_isByteStable_regardlessOfInsertionOrder_andPageOrder() {
val a = Document(id = "a", title = "A", createdAtMillis = 1, updatedAtMillis = 1)
val b = Document(id = "b", title = "B", createdAtMillis = 2, updatedAtMillis = 2)
val c = Document(id = "c", title = "C", createdAtMillis = 3, updatedAtMillis = 3)

val fromCAB = IndexJsonCodec.serialize(listOf(c, a, b), emptyMap()).toByteArray()
val fromBCA = IndexJsonCodec.serialize(listOf(b, c, a), emptyMap()).toByteArray()
assertTrue(fromCAB.contentEquals(fromBCA))

// Pages are canonicalized to index order on write: the same logical
// page set serializes to identical bytes in either insertion order.
val pages = mapOf("a" to listOf(Page(id = "p1", index = 1), Page(id = "p0", index = 0)))
val pagesReversed = mapOf(
"a" to listOf(Page(id = "p0", index = 0), Page(id = "p1", index = 1)),
)
assertTrue(
IndexJsonCodec.serialize(listOf(a), pages).toByteArray()
.contentEquals(IndexJsonCodec.serialize(listOf(a), pagesReversed).toByteArray()),
)
assertTrue(
IndexJsonCodec.serialize(listOf(a), pages).contains(""id":"p0","index":0"),
)
}

@Test
fun garbageAndUnsupportedVersions_rejected() {
assertThrows(IndexJsonCodec.IndexParseException::class.java) {
IndexJsonCodec.deserialize("this is not json {")
}
assertThrows(IndexJsonCodec.IndexParseException::class.java) {
IndexJsonCodec.deserialize("{"documents":[]}")
}
assertThrows(IndexJsonCodec.IndexParseException::class.java) {
IndexJsonCodec.deserialize("{"schemaVersion":99,"documents":[]}")
}
}

@Test
fun unknownFields_ignored_forwardCompatible() {
val json = "{"schemaVersion":1,"documents":[{"id":"d"," +
""futureField":{"nested":[1,2]},"title":"T"," +
""createdAtMillis":1,"updatedAtMillis":2,"pages":[]}]}"

val snapshot = IndexJsonCodec.deserialize(json)

assertEquals("T", snapshot.documents.single().title)
assertTrue(snapshot.pagesByDocumentId.getValue("d").isEmpty())
}

@Test
fun minimalDocument_mapsToSchemaDefaults() {
val json = "{"schemaVersion":1,"documents":[{"id":"d","title":"T"," +
""createdAtMillis":1,"updatedAtMillis":2,"pages":[{"id":"p","index":0}]}]}"

val snapshot = IndexJsonCodec.deserialize(json)
val page = snapshot.pagesByDocumentId.getValue("d").single()

assertEquals(PageEnhancementMode.NONE, page.enhancement)
assertEquals(0, page.rotationDegrees)
assertNull(page.processedImageRef)
assertNull(page.cropQuad)
}

}
