package org.payswap.camscan.ocr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OcrResultTest {

// ---- fixtures ----------------------------------------------------------

private fun block(text: String, confidence: Float, box: OcrBox, index: Int) =
OcrTextBlock(text = text, confidence = confidence, box = box, blockIndex = index)

private fun result(
blocks: List<OcrTextBlock> = listOf(
block("alpha", 0.5f, OcrBox(0.1f, 0.1f, 0.9f, 0.2f), 0),
block("beta", 1.0f, OcrBox(0.1f, 0.3f, 0.9f, 0.4f), 1),
),
resultId: String = "res-1",
pageId: String? = "page-1",
engineId: String = "stub-deterministic",
settings: OcrSettings = OcrSettings.DEFAULT,
recognisedAtMillis: Long = 1_000L,
processingDurationMillis: Long = 25L,
): OcrResult = OcrResult(
resultId = resultId,
pageId = pageId,
blocks = blocks,
engineId = engineId,
settingsEcho = settings,
recognisedAtMillis = recognisedAtMillis,
processingDurationMillis = processingDurationMillis,
)

// ---- fullText ----------------------------------------------------------

@Test
fun fullTextJoinsBlocksInReadingOrderWithNewlines() {
val r = result(
blocks = listOf(
block("alpha", 0.9f, OcrBox.FULL_PAGE, 0),
block("beta\nline", 0.8f, OcrBox.FULL_PAGE, 1),
block("gamma", 0.7f, OcrBox.FULL_PAGE, 2),
),
)
assertEquals("alpha\nbeta\nline\ngamma", r.fullText)
}

@Test
fun emptyBlockListGivesEmptyFullTextAndZeroMeanConfidence() {
val r = result(blocks = emptyList())
assertEquals("", r.fullText)
assertEquals(0f, r.meanConfidence, 0f)
}

// ---- meanConfidence ----------------------------------------------------

@Test
fun meanConfidenceIsTheExactBlockAverage() {
assertEquals(0.75f, result().meanConfidence, 0f) // (0.5 + 1.0) / 2, exactly representable
assertEquals(
0.5f,
result(
blocks = listOf(
block("a", 0.25f, OcrBox.FULL_PAGE, 0),
block("b", 0.75f, OcrBox.FULL_PAGE, 1),
),
).meanConfidence,
0f,
)
}

// ---- model invariants --------------------------------------------------

@Test
fun unsortedBlockIndicesAreRejected() {
try {
result(
blocks = listOf(
block("second", 0.5f, OcrBox.FULL_PAGE, 1),
block("first", 0.5f, OcrBox.FULL_PAGE, 0),
),
)
fail("expected IllegalArgumentException for unsorted blocks")
} catch (expected: IllegalArgumentException) {
assertTrue(expected.message!!.contains("reading order"))
}
}

@Test
fun duplicateBlockIndicesAreRejected() {
try {
result(
blocks = listOf(
block("a", 0.5f, OcrBox.FULL_PAGE, 0),
block("b", 0.5f, OcrBox.FULL_PAGE, 0),
),
)
fail("expected IllegalArgumentException for duplicate blockIndex")
} catch (expected: IllegalArgumentException) {
assertTrue(expected.message!!.contains("reading order"))
}
}

@Test
fun outOfRangeConfidenceIsRejected() {
try {
block("x", 1.5f, OcrBox.FULL_PAGE, 0)
fail("expected IllegalArgumentException for confidence > 1")
} catch (expected: IllegalArgumentException) {
assertTrue(expected.message!!.contains("confidence"))
}
}

// ---- stable string -----------------------------------------------------

@Test
fun stableStringIsByteIdenticalForIdenticalInstances() {
val r = result()
assertEquals(r.toStableString(), r.toStableString())
assertEquals(r.toStableString(), result().toStableString())
}

@Test
fun stableStringReflectsContentChanges() {
val base = result()
assertNotEquals(base.toStableString(), result(pageId = null).toStableString())
assertNotEquals(base.toStableString(), result(recognisedAtMillis = 2_000L).toStableString())
assertNotEquals(
base.toStableString(),
result(settings = OcrSettings(languageHints = listOf("en"))).toStableString(),
)
}

@Test
fun stableStringEscapesLineBreaksAndBackslashes() {
val r = result(
blocks = listOf(block("l1\nl2\\end", 0.5f, OcrBox.FULL_PAGE, 0)),
)
val s = r.toStableString()
// every stable-string line is a key=value line — no raw line breaks inside values
assertTrue(s.lines().all { it.contains('=') })
// newline escaped to literal backslash-n, backslash escaped to double backslash
assertTrue(s.contains("block[0].text=l1\\nl2\\\\end"))
}

@Test
fun stableStringUsesFixedLocaleSafeFloatFormatting() {
val r = result(
blocks = listOf(block("x", 0.5f, OcrBox(0.25f, 0f, 1f, 0.75f), 0)),
)
val s = r.toStableString()
assertTrue(s.contains("meanConfidence=0.750000"))
assertTrue(s.contains("block[0].confidence=0.500000"))
assertTrue(s.contains("block[0].box=0.250000,0.000000,1.000000,0.750000"))
}

}
