package org.payswap.camscan.ocr.engine.mlkit

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.ocr.FakeTimeSource
import org.payswap.camscan.ocr.engine.OcrFailure
import org.payswap.camscan.ocr.engine.OcrImage
import org.payswap.camscan.ocr.engine.OcrImageFormat
import org.payswap.camscan.ocr.engine.OcrMode
import org.payswap.camscan.ocr.engine.OcrOutcome
import org.payswap.camscan.ocr.engine.OcrResult
import org.payswap.camscan.ocr.engine.OcrSettings
import org.payswap.camscan.ocr.harness.SyntheticImages

/**
 * MlKitOcrEngine control-flow tests against FAKE recognizer handles — no ML
 * Kit and no android anywhere in this tree. The taxonomy table from
 * MlKitOcrEngine's KDoc is exercised case by case, plus the coroutine
 * bridge, lifecycle, and never-throws discipline.
 */
class MlKitOcrEngineTest {

// ---- helpers -----------------------------------------------------------

private fun validImage(): OcrImage =
SyntheticImages.repeating(width = 200, height = 400, format = OcrImageFormat.PNG, patternSeed = 1)

private fun successDto(): MlKitTextDto = MlKitTextDto(
blocks = listOf(
MlKitBlockDto(
text = "first block",
box = MlKitBoxDto(left = 10, top = 20, right = 110, bottom = 220),
lines = listOf(
MlKitLineDto(
text = "first block",
box = MlKitBoxDto(left = 10, top = 20, right = 110, bottom = 220),
confidence = 0.8f,
),
),
),
MlKitBlockDto(
text = "second block",
box = MlKitBoxDto(left = 10, top = 240, right = 110, bottom = 380),
lines = listOf(
MlKitLineDto(
text = "second block",
box = MlKitBoxDto(left = 10, top = 240, right = 110, bottom = 380),
confidence = null,
),
),
),
),
)

private fun newEngine(
handle: FakeRecognizerHandle,
timeSource: FakeTimeSource = FakeTimeSource(),
): MlKitOcrEngine = MlKitOcrEngine(handle, timeSource)

private fun recognize(
engine: MlKitOcrEngine,
image: OcrImage,
settings: OcrSettings = OcrSettings.DEFAULT,
): OcrOutcome = runBlocking { engine.recognize(image, settings) }

private fun successOf(outcome: OcrOutcome): OcrResult {
assertTrue("expected Success, got $outcome", outcome is OcrOutcome.Success)
return (outcome as OcrOutcome.Success).result
}

// ---- success path ------------------------------------------------------

@Test
fun successMapsDtoIntoSuccessResult() {
val task = FakeRecognizerTask(script = FakeScript.Deliver(successDto()))
val handle = FakeRecognizerHandle { task }
val engine = newEngine(handle)

val result = successOf(recognize(engine, validImage()))

assertEquals(MlKitOcrEngine.ENGINE_ID, result.engineId)
assertTrue(result.resultId.startsWith("mlkit-"))
assertNull("engines do not know page identity", result.pageId)
assertEquals(listOf("first block", "second block"), result.blocks.map { it.text })
assertEquals(listOf(0, 1), result.blocks.map { it.blockIndex })
// Boxes normalized against the DECLARED dims (200x400), DTO text verbatim.
val first = result.blocks[0].box
assertEquals(0.05f, first.left, 1e-6f)
assertEquals(0.05f, first.top, 1e-6f)
assertEquals(0.55f, first.right, 1e-6f)
assertEquals(0.55f, first.bottom, 1e-6f)
// Confidence aggregation: 0.8 line, and a null line -> DEFAULT_CONFIDENCE.
assertEquals(0.8f, result.blocks[0].confidence, 1e-6f)
assertEquals(MlKitMapper.DEFAULT_CONFIDENCE, result.blocks[1].confidence, 0f)
// The image reached the handle unchanged.
assertEquals(listOf(validImage()), handle.processedImages)
}

@Test
fun recognisedAtAndDurationComeFromTheInjectedTimeSource() {
val task = FakeRecognizerTask(script = FakeScript.Deliver(successDto()))
val engine = newEngine(
FakeRecognizerHandle { task },
FakeTimeSource(startMillis = 5_000L, stepMillis = 7L),
)
val result = successOf(recognize(engine, validImage()))
assertEquals(5_000L, result.recognisedAtMillis)
assertEquals(7L, result.processingDurationMillis)
}

@Test
fun settingsEchoCarriesTheRequest() {
val task = FakeRecognizerTask(script = FakeScript.Deliver(MlKitTextDto(blocks = emptyList())))
val engine = newEngine(FakeRecognizerHandle { task })
val settings = OcrSettings(languageHints = listOf("en"), mode = OcrMode.ACCURATE)
val result = successOf(recognize(engine, validImage(), settings))
assertEquals(settings, result.settingsEcho)
}

@Test
fun zeroBlocksIsAnHonestBlankPageSuccess() {
val task = FakeRecognizerTask(script = FakeScript.Deliver(MlKitTextDto(blocks = emptyList())))
val engine = newEngine(FakeRecognizerHandle { task })
val result = successOf(recognize(engine, validImage()))
assertTrue(result.blocks.isEmpty())
assertEquals("", result.fullText)
assertEquals(0f, result.meanConfidence, 0f)
}

// ---- pre-flight: degenerate inputs never reach the handle --------------

@Test
fun preflightFailuresNeverReachTheHandle() {
val cases: List<Pair<OcrImage, OcrFailure>> = listOf(
SyntheticImages.empty(64, 64, OcrImageFormat.PNG) to OcrFailure.EMPTY_IMAGE,
SyntheticImages.repeating(64, 64, OcrImageFormat.PNG).copy(width = 0) to OcrFailure.EMPTY_IMAGE,
SyntheticImages.repeating(64, 64, OcrImageFormat.PNG).copy(height = -1) to OcrFailure.EMPTY_IMAGE,
SyntheticImages.repeating(64, 64, OcrImageFormat.UNKNOWN, patternSeed = 5) to OcrFailure.UNSUPPORTED_FORMAT,
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
val handle = FakeRecognizerHandle { FakeRecognizerTask(script = FakeScript.Deliver(successDto())) }
val engine = newEngine(handle)
cases.forEach { (image, expectedReason) ->
val outcome = recognize(engine, image)
assertTrue("expected Failure for $image, got $outcome", outcome is OcrOutcome.Failure)
assertEquals("for $image", expectedReason, (outcome as OcrOutcome.Failure).reason)
}
assertEquals("degenerate inputs must never reach the recognizer", 0, handle.processCount)
}

@Test
fun nonQuarterTurnRotationReportsEngineErrorBeforeTheHandle() {
val handle = FakeRecognizerHandle { FakeRecognizerTask(script = FakeScript.Deliver(successDto())) }
val engine = newEngine(handle)
val outcome = recognize(engine, validImage().copy(rotationDegrees = 45))
assertTrue(outcome is OcrOutcome.Failure)
val reason = (outcome as OcrOutcome.Failure).reason
assertTrue("expected ENGINE_ERROR, got $reason", reason is OcrFailure.ENGINE_ERROR)
assertTrue(
"message should name the rotation restriction: $reason",
(reason as OcrFailure.ENGINE_ERROR).message.contains("rotationDegrees"),
)
assertEquals(0, handle.processCount)
}

// ---- task failure taxonomy ---------------------------------------------

@Test
fun inputConstructionRejectionIsCorruptImage() {
val handle = FakeRecognizerHandle { null } // decode/construction failed
val engine = newEngine(handle)
val outcome = recognize(engine, validImage())
assertTrue(outcome is OcrOutcome.Failure)
assertEquals(OcrFailure.CORRUPT_IMAGE, (outcome as OcrOutcome.Failure).reason)
assertEquals(1, handle.processCount)
}

@Test
fun decodeClassFailureIsCorruptImage() {
val task = FakeRecognizerTask(script = FakeScript.Fail(RecognizerError.ImageDecode))
val engine = newEngine(FakeRecognizerHandle { task })
val outcome = recognize(engine, validImage())
assertTrue(outcome is OcrOutcome.Failure)
assertEquals(OcrFailure.CORRUPT_IMAGE, (outcome as OcrOutcome.Failure).reason)
}

@Test
fun otherFailureIsEngineErrorWithTheMessagePreserved() {
val task = FakeRecognizerTask(script = FakeScript.Fail(RecognizerError.Other("mlkit exploded")))
val engine = newEngine(FakeRecognizerHandle { task })
val outcome = recognize(engine, validImage())
assertTrue(outcome is OcrOutcome.Failure)
assertEquals(OcrFailure.ENGINE_ERROR("mlkit exploded"), (outcome as OcrOutcome.Failure).reason)
}

@Test
fun nullTextIsCorruptImage() {
val task = FakeRecognizerTask(script = FakeScript.Deliver(null))
val engine = newEngine(FakeRecognizerHandle { task })
val outcome = recognize(engine, validImage())
assertTrue(outcome is OcrOutcome.Failure)
assertEquals(OcrFailure.CORRUPT_IMAGE, (outcome as OcrOutcome.Failure).reason)
}

@Test
fun handleMisbehaviorNeverThrowsAcrossTheSeam() {
val handle = FakeRecognizerHandle { throw IllegalStateException("boom") }
val engine = newEngine(handle)
val outcome = recognize(engine, validImage())
assertTrue(outcome is OcrOutcome.Failure)
val reason = (outcome as OcrOutcome.Failure).reason
assertTrue("expected ENGINE_ERROR, got $reason", reason is OcrFailure.ENGINE_ERROR)
val message = (reason as OcrFailure.ENGINE_ERROR).message
assertTrue("message should carry the cause honestly: $message", message.contains("IllegalStateException") && message.contains("boom"))
}

// ---- coroutine bridge: cancellation ------------------------------------

@Test
fun cancellationCancelsTheCoroutineAndInvokesTaskCancel() = runBlocking {
val task = FakeRecognizerTask(script = FakeScript.Hang)
val engine = newEngine(FakeRecognizerHandle { task })

val job = launch { engine.recognize(validImage(), OcrSettings.DEFAULT) }
yield() // let recognize reach the suspension point
assertEquals("task must be listened before it can be cancelled", 1, task.listenCount)
assertEquals("nothing cancelled yet", 0, task.cancelCount)

job.cancelAndJoin()
assertTrue("coroutine must resume as cancelled", job.isCancelled)
assertEquals("underlying task cancel must be invoked", 1, task.cancelCount)
}

@Test
fun lateTaskCompletionAfterCancellationIsDiscarded() = runBlocking {
val task = FakeRecognizerTask(script = FakeScript.Hang)
val engine = newEngine(FakeRecognizerHandle { task })

val job = launch { engine.recognize(validImage(), OcrSettings.DEFAULT) }
yield()
job.cancelAndJoin()

// A late (already-cancelled) delivery must be dropped, never crash.
task.complete(successDto())
task.fail(RecognizerError.Other("late failure"))
assertTrue(job.isCancelled)
}

// ---- lifecycle ---------------------------------------------------------

@Test
fun closeThenRecognizeReportsClosed() {
val task = FakeRecognizerTask(script = FakeScript.Deliver(successDto()))
val handle = FakeRecognizerHandle { task }
val engine = newEngine(handle)

engine.close()
val outcome = recognize(engine, validImage())
assertTrue(outcome is OcrOutcome.Failure)
assertEquals(OcrFailure.CLOSED, (outcome as OcrOutcome.Failure).reason)
assertEquals("closed engine must not reach the recognizer", 0, handle.processCount)
}

@Test
fun closeIsIdempotentAndReleasesTheHandleExactlyOnce() {
val handle = FakeRecognizerHandle { FakeRecognizerTask(script = FakeScript.Deliver(successDto())) }
val engine = newEngine(handle)
engine.close()
engine.close()
engine.close()
assertEquals("client released exactly once", 1, handle.closeCount)
}
}
