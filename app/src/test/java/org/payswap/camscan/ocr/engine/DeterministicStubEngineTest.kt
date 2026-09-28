package org.payswap.camscan.ocr.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.ocr.FakeTimeSource
import org.payswap.camscan.ocr.harness.SyntheticImages

class DeterministicStubEngineTest {

// ---- helpers -----------------------------------------------------------

private fun newEngine(clock: FakeTimeSource = FakeTimeSource()) =
DeterministicStubEngine(clock)

private fun recognize(
engine: OcrEngine,
image: OcrImage,
settings: OcrSettings = OcrSettings.DEFAULT,
): OcrOutcome = runBlocking { engine.recognize(image, settings) }

private fun successOf(outcome: OcrOutcome): OcrResult {
assertTrue("expected Success, got $outcome", outcome is OcrOutcome.Success)
return (outcome as OcrOutcome.Success).result
}

private fun successStableString(outcome: OcrOutcome): String = successOf(outcome).toStableString()

// ---- determinism -------------------------------------------------------

@Test
fun sameInputTwiceYieldsByteIdenticalStableStrings(): Unit = runBlocking {
val image = SyntheticImages.repeating(128, 128, OcrImageFormat.PNG, patternSeed = 7)
val engine = newEngine(FakeTimeSource(5_000L))

val first = recognize(engine, image)
val second = recognize(engine, image)
assertEquals(successStableString(first), successStableString(second))

// And across fresh engine instances (no hidden per-instance state).
val fresh = newEngine(FakeTimeSource(5_000L))
assertEquals(successStableString(first), successStableString(recognize(fresh, image)))
}

@Test
fun resultIdIsDeterministicAndContentDerived(): Unit = runBlocking {
val image = SyntheticImages.repeating(128, 128, OcrImageFormat.PNG, patternSeed = 7)
val r1 = successOf(recognize(newEngine(), image))
val r2 = successOf(recognize(newEngine(), image))
assertEquals(r1.resultId, r2.resultId)
assertTrue("resultId should be content-derived", r1.resultId.startsWith("stub-"))
assertNull("engines do not know page identity", r1.pageId)
}

@Test
fun singleByteChangeChangesTheOutput(): Unit = runBlocking {
val base = SyntheticImages.repeating(128, 128, OcrImageFormat.PNG, patternSeed = 7)
val mutated = SyntheticImages.withMutatedByte(base, 64, (base.bytes[64] + 1).toByte())

val baseOutcome = recognize(newEngine(), base)
val mutatedOutcome = recognize(newEngine(), mutated)
assertNotEquals(successStableString(baseOutcome), successStableString(mutatedOutcome))
assertNotEquals(successOf(baseOutcome).resultId, successOf(mutatedOutcome).resultId)
}

@Test
fun settingsAndRotationChangeTheOutput(): Unit = runBlocking {
val image = SyntheticImages.repeating(128, 128, OcrImageFormat.PNG, patternSeed = 7)
val baseline = successStableString(recognize(newEngine(), image))

val otherHints = successStableString(
recognize(newEngine(), image, OcrSettings(languageHints = listOf("fr"))),
)
val otherMode = successStableString(
recognize(newEngine(), image, OcrSettings(mode = OcrMode.ACCURATE)),
)
val rotated = successStableString(
recognize(newEngine(), image.copy(rotationDegrees = 90)),
)

assertNotEquals(baseline, otherHints)
assertNotEquals(baseline, otherMode)
assertNotEquals(baseline, rotated)
}

@Test
fun rotationFoldsModulo360(): Unit = runBlocking {
val base = SyntheticImages.repeating(128, 128, OcrImageFormat.PNG, patternSeed = 7)
val folded = successStableString(recognize(newEngine(), base.copy(rotationDegrees = 360)))
val plain = successStableString(recognize(newEngine(), base))
assertEquals(plain, folded)
}

// ---- degenerate inputs -------------------------------------------------

@Test
fun degenerateInputsMapToFailureValuesNeverThrow(): Unit = runBlocking {
val cases: List<Pair<OcrImage, OcrFailure>> = listOf(
SyntheticImages.empty(64, 64, OcrImageFormat.PNG) to OcrFailure.EMPTY_IMAGE,
SyntheticImages.repeating(64, 64, OcrImageFormat.PNG).copy(width = 0)
to OcrFailure.CORRUPT_IMAGE,
SyntheticImages.repeating(64, 64, OcrImageFormat.PNG).copy(height = -1)
to OcrFailure.CORRUPT_IMAGE,
SyntheticImages.repeating(64, 64, OcrImageFormat.UNKNOWN, patternSeed = 5)
to OcrFailure.UNSUPPORTED_FORMAT,
SyntheticImages.withMutatedByte(
SyntheticImages.repeating(64, 64, OcrImageFormat.PNG),
index = 0,
value = 0x00,
) to OcrFailure.CORRUPT_IMAGE,
SyntheticImages.withMutatedByte(
SyntheticImages.repeating(64, 64, OcrImageFormat.JPEG),
index = 1,
value = 0x00,
) to OcrFailure.CORRUPT_IMAGE,
)
cases.forEach { (image, expectedReason) ->
val outcome = recognize(newEngine(), image)
assertTrue("expected Failure for $image, got $outcome", outcome is OcrOutcome.Failure)
assertEquals(expectedReason, (outcome as OcrOutcome.Failure).reason)
}
}

// ---- block invariants --------------------------------------------------

@Test
fun blockInvariantsHold(): Unit = runBlocking {
val image = SyntheticImages.repeating(256, 128, OcrImageFormat.PNG, patternSeed = 3)
val result = successOf(recognize(newEngine(), image))

assertEquals(BLOCK_COUNT, result.blocks.size)
result.blocks.forEachIndexed { index, block ->
assertEquals("blockIndex must equal list position", index, block.blockIndex)
}
result.blocks.forEach { block ->
assertTrue(block.text.isNotEmpty())
assertTrue("confidence in 0..1", block.confidence in 0f..1f)
assertTrue(
"box within page",
block.box.left in 0f..1f && block.box.top in 0f..1f &&
block.box.right in 0f..1f && block.box.bottom in 0f..1f,
)
assertTrue(
"box ordered",
block.box.left <= block.box.right && block.box.top <= block.box.bottom,
)
}
assertEquals(result.blocks.joinToString("\n") { it.text }, result.fullText)
val recomputedMean =
result.blocks.fold(0f) { acc, b -> acc + b.confidence } / result.blocks.size
assertEquals(recomputedMean, result.meanConfidence, 0f)
}

// ---- time seam ---------------------------------------------------------

@Test
fun timestampComesFromInjectedTimeSource(): Unit = runBlocking {
val result = successOf(
recognize(
newEngine(FakeTimeSource(42_000L)),
SyntheticImages.repeating(64, 64, OcrImageFormat.PNG),
),
)
assertEquals(42_000L, result.recognisedAtMillis)
}

// ---- lifecycle ---------------------------------------------------------

@Test
fun closeIsIdempotentAndRecognizeReportsClosedAfterwards(): Unit = runBlocking {
val engine = newEngine()
engine.close()
engine.close() // idempotent — must not throw
val outcome = recognize(engine, SyntheticImages.repeating(64, 64, OcrImageFormat.PNG))
assertTrue(outcome is OcrOutcome.Failure)
assertEquals(OcrFailure.CLOSED, (outcome as OcrOutcome.Failure).reason)
}

private companion object {
const val BLOCK_COUNT = 4 // keep in sync with DeterministicStubEngine.BLOCK_COUNT
}

}
