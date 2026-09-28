package org.payswap.camscan.ocr.engine.mlkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.payswap.camscan.ocr.engine.OcrSettings
import org.payswap.camscan.ocr.engine.OcrMode

/**
 * PURE-mapper tests on SYNTHETIC DTOs — no ML Kit, no android anywhere in
 * this tree. Each documented policy gets its own test: reading order ->
 * blockIndex, box normalization + clamp, confidence aggregation +
 * DEFAULT_CONFIDENCE, fullText join == OcrResult's own rule, determinism.
 */
class MlKitMapperTest {

// ---- helpers -----------------------------------------------------------

private fun line(
    text: String,
    box: MlKitBoxDto = MlKitBoxDto(0, 0, 10, 10),
    confidence: Float? = null,
): MlKitLineDto = MlKitLineDto(text = text, box = box, confidence = confidence)

private fun block(
    text: String,
    box: MlKitBoxDto,
    lines: List<MlKitLineDto>,
): MlKitBlockDto = MlKitBlockDto(text = text, box = box, lines = lines)

private fun map(
    text: MlKitTextDto,
    width: Int = 200,
    height: Int = 400,
    engineId: String = MlKitOcrEngine.ENGINE_ID,
    settings: OcrSettings = OcrSettings.DEFAULT,
    recognisedAtMillis: Long = 5_000L,
    processingDurationMillis: Long = 9L,
) = MlKitMapper.toResult(
    text = text,
    imageWidth = width,
    imageHeight = height,
    engineId = engineId,
    settingsEcho = settings,
    recognisedAtMillis = recognisedAtMillis,
    processingDurationMillis = processingDurationMillis,
)

// ---- reading order -----------------------------------------------------

@Test
fun readingOrderMapsToStrictlyIncreasingBlockIndices() {
val result = map(
MlKitTextDto(
blocks = listOf(
block("first", MlKitBoxDto(0, 0, 10, 10), listOf(line("first"))),
block("second", MlKitBoxDto(0, 20, 10, 30), listOf(line("second"))),
block("third", MlKitBoxDto(0, 40, 10, 50), listOf(line("third"))),
),
),
)
assertEquals(listOf("first", "second", "third"), result.blocks.map { it.text })
assertEquals(listOf(0, 1, 2), result.blocks.map { it.blockIndex })
}

// ---- box normalization -------------------------------------------------

@Test
fun boxesNormalizeAgainstDeclaredDims() {
val result = map(
MlKitTextDto(
blocks = listOf(
block("boxed", MlKitBoxDto(left = 50, top = 100, right = 150, bottom = 200), listOf(line("boxed"))),
),
),
width = 200,
height = 400,
)
val box = result.blocks.single().box
assertEquals(0.25f, box.left, 1e-6f)
assertEquals(0.25f, box.top, 1e-6f)
assertEquals(0.75f, box.right, 1e-6f)
assertEquals(0.50f, box.bottom, 1e-6f)
}

@Test
fun boxesClampToUnitPageSpace() {
// ML Kit boxes may exceed the frame by a few pixels; degenerate upstream
// boxes must never break OcrBox's 0..1 invariants.
val result = map(
MlKitTextDto(
blocks = listOf(
block(
"overflowing",
MlKitBoxDto(left = -10, top = -5, right = 210, bottom = 410),
listOf(line("overflowing")),
),
block(
"partial",
MlKitBoxDto(left = 10, top = 10, right = 220, bottom = 390),
listOf(line("partial")),
),
),
),
width = 200,
height = 400,
)
val overflowing = result.blocks[0].box
assertEquals(0f, overflowing.left, 0f)
assertEquals(0f, overflowing.top, 0f)
assertEquals(1f, overflowing.right, 0f)
assertEquals(1f, overflowing.bottom, 0f)
val partial = result.blocks[1].box
assertEquals(0.05f, partial.left, 1e-6f)
assertEquals(0.025f, partial.top, 1e-6f)
assertEquals("only the overflowing edge clamps", 1f, partial.right, 0f)
assertEquals("in-range edges pass through untouched", 0.975f, partial.bottom, 1e-6f)
}

// ---- confidence policy -------------------------------------------------

@Test
fun confidenceAggregatesMeanOfLineConfidences() {
val result = map(
MlKitTextDto(
blocks = listOf(
block("two lines", MlKitBoxDto(0, 0, 10, 10), listOf(line("a", confidence = 0.8f), line("b", confidence = 0.6f))),
),
),
)
assertEquals(0.7f, result.blocks.single().confidence, 1e-6f)
}

@Test
fun nullLineConfidenceCountsAsDefaultConfidence() {
val result = map(
MlKitTextDto(
blocks = listOf(
block(
"mixed",
MlKitBoxDto(0, 0, 10, 10),
listOf(line("a", confidence = null), line("b", confidence = 0.5f)),
),
),
),
)
// (DEFAULT_CONFIDENCE + 0.5) / 2 == 0.75 — missing is not low.
assertEquals(MlKitMapper.DEFAULT_CONFIDENCE, 1.0f, 0f)
assertEquals(0.75f, result.blocks.single().confidence, 1e-6f)
}

@Test
fun allNullConfidencesAndZeroLineBlocksTakeDefaultConfidence() {
val result = map(
MlKitTextDto(
blocks = listOf(
block("all null", MlKitBoxDto(0, 0, 10, 10), listOf(line("a", confidence = null), line("b", confidence = null))),
block("no lines", MlKitBoxDto(0, 0, 10, 10), lines = emptyList()),
),
),
)
assertEquals(MlKitMapper.DEFAULT_CONFIDENCE, result.blocks[0].confidence, 0f)
assertEquals(MlKitMapper.DEFAULT_CONFIDENCE, result.blocks[1].confidence, 0f)
}

@Test
fun confidenceSanitizesOutOfRangeAndNaNValues() {
val result = map(
MlKitTextDto(
blocks = listOf(
block(
"hostile",
MlKitBoxDto(0, 0, 10, 10),
listOf(
line("high", confidence = 1.5f),
line("low", confidence = -0.5f),
line("nan", confidence = Float.NaN),
),
),
),
),
)
val confidence = result.blocks.single().confidence
assertTrue("sanitized confidence must be in 0..1 (got $confidence)", confidence in 0f..1f)
// NaN counts as missing -> DEFAULT; (1.0 + 0.0 + 1.0) / 3 == 2/3.
assertEquals(2f / 3f, confidence, 1e-6f)
}

// ---- fullText join -----------------------------------------------------

@Test
fun fullTextJoinMatchesOcrResultOwnRule() {
val result = map(
MlKitTextDto(
blocks = listOf(
block("alpha", MlKitBoxDto(0, 0, 10, 10), listOf(line("alpha"))),
block("beta", MlKitBoxDto(0, 20, 10, 30), listOf(line("beta"))),
),
),
)
// The rule lives IN OcrResult (blocks joined with one newline, reading
// order) and the mapper must not invent a second one.
assertEquals(result.blocks.joinToString("\n") { it.text }, result.fullText)
assertEquals("alpha\nbeta", result.fullText)
}

@Test
fun emptyTextIsAnHonestBlankPage() {
val result = map(MlKitTextDto(blocks = emptyList()))
assertTrue(result.blocks.isEmpty())
assertEquals("", result.fullText)
assertEquals(0f, result.meanConfidence, 0f)
}

// ---- determinism -------------------------------------------------------

@Test
fun sameDtoAndFixedTimeYieldByteIdenticalStableStrings() {
val text = MlKitTextDto(
blocks = listOf(
block(
"deterministic",
MlKitBoxDto(10, 20, 110, 220),
listOf(line("deterministic", confidence = 0.9f)),
),
block(
"second",
MlKitBoxDto(10, 300, 110, 380),
listOf(line("second", confidence = null)),
),
),
)
val first = map(text)
val second = map(text)
assertEquals(first.resultId, second.resultId)
assertEquals(first.toStableString(), second.toStableString())
}

@Test
fun resultIdFollowsTheStubConventionAndTracksContentAndInstant() {
val text = MlKitTextDto(blocks = listOf(block("tracked", MlKitBoxDto(0, 0, 10, 10), listOf(line("tracked")))))
val resultId = map(text).resultId
assertTrue("resultId convention is prefix + 8 hex (got $resultId)", resultId.matches(Regex("mlkit-[0-9a-f]{8}")))

assertNotEquals(resultId, map(text, recognisedAtMillis = 5_001L).resultId)
assertNotEquals(
resultId,
map(MlKitTextDto(blocks = listOf(block("changed", MlKitBoxDto(0, 0, 10, 10), listOf(line("changed")))))).resultId,
)
}

// ---- echo contract -----------------------------------------------------

@Test
fun echoesEngineIdSettingsAndTimesAndCarriesNoPageIdentity() {
val settings = OcrSettings(languageHints = listOf("en"), mode = OcrMode.ACCURATE)
val result = map(MlKitTextDto(blocks = emptyList()), settings = settings, recognisedAtMillis = 42_000L, processingDurationMillis = 17L)
assertEquals(MlKitOcrEngine.ENGINE_ID, result.engineId)
assertEquals(settings, result.settingsEcho)
assertEquals(42_000L, result.recognisedAtMillis)
assertEquals(17L, result.processingDurationMillis)
assertNull("engines do not know page identity", result.pageId)
}

@Test
fun rejectsNonPositiveImageDimensions() {
try {
map(MlKitTextDto(blocks = emptyList()), width = 0)
fail("expected IllegalArgumentException for width=0")
} catch (expected: IllegalArgumentException) {
// documented fail-fast: engines guarantee positive dims via pre-flight
}
try {
map(MlKitTextDto(blocks = emptyList()), height = -1)
fail("expected IllegalArgumentException for height=-1")
} catch (expected: IllegalArgumentException) {
// documented fail-fast
}
}
}
