package org.payswap.camscan.ocr.harness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.payswap.camscan.ocr.FakeTimeSource
import org.payswap.camscan.ocr.engine.DeterministicStubEngine
import org.payswap.camscan.ocr.engine.OcrEngine
import org.payswap.camscan.ocr.engine.OcrImage
import org.payswap.camscan.ocr.engine.OcrOutcome
import org.payswap.camscan.ocr.engine.OcrSettings

class OcrHarnessTest {

private fun newHarness(clock: FakeTimeSource = FakeTimeSource()): OcrHarness =
OcrHarness(DeterministicStubEngine(clock), OcrSettings.DEFAULT, clock)

// ---- determinism -------------------------------------------------------

@Test
fun twoRunsProduceByteIdenticalEndToEndEvidence(): Unit = runBlocking {
val harness = newHarness(FakeTimeSource(1_000_000L))
val inputs = SyntheticImages.defaultHarnessSet()

val first = harness.run(inputs)
val second = harness.run(inputs)

assertEquals(first.toStableString(), second.toStableString())
assertTrue(first.determinismVerified)
assertTrue(second.determinismVerified)
}

@Test
fun steppingClocksDoNotFoolTheDeterminismCheck(): Unit = runBlocking {
// Timestamps legitimately differ between the two passes under a stepping
// clock; the harness masks time lines for comparison and must still pass.
val harness = newHarness(FakeTimeSource(1_000L, stepMillis = 5L))
harness.run(SyntheticImages.defaultHarnessSet()) // must not throw
}

// ---- manifest completeness ---------------------------------------------

@Test
fun reportManifestIsComplete(): Unit = runBlocking {
val inputs = SyntheticImages.defaultHarnessSet()
val report = newHarness().run(inputs)

assertEquals(inputs.size, report.inputCount)
assertEquals(inputs.size, report.records.size)
assertEquals("stub-deterministic", report.engineId)
report.records.forEachIndexed { index, record ->
assertEquals(index, record.inputIndex)
}

assertEquals(
listOf("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "FAILURE", "FAILURE", "FAILURE", "FAILURE"),
report.records.map { it.outcome },
)
assertEquals("UNSUPPORTED_FORMAT", report.records[4].failureReason)
assertEquals("EMPTY_IMAGE", report.records[5].failureReason)
assertEquals("CORRUPT_IMAGE", report.records[6].failureReason)
assertEquals("CORRUPT_IMAGE", report.records[7].failureReason)

report.records.filter { it.outcome == "SUCCESS" }.forEach { record ->
assertNotNull(record.resultId)
assertNotNull(record.blockCount)
assertNotNull(record.meanConfidence)
assertNotNull(record.recognisedAtMillis)
assertNotNull(record.processingDurationMillis)
assertNotNull(record.resultStableString)
assertEquals(4, record.blockCount) // stub always emits 4 blocks
}
report.records.filter { it.outcome == "FAILURE" }.forEach { record ->
assertNull(record.resultId)
assertNull(record.blockCount)
assertNull(record.resultStableString)
}
}

// ---- time seam ---------------------------------------------------------

@Test
fun timestampsComeFromTheInjectedTimeSource(): Unit = runBlocking {
val clock = FakeTimeSource(1_000L, stepMillis = 5L)
val harness = newHarness(clock)
val report = harness.run(
listOf(SyntheticImages.repeating(64, 64, OcrImageFormat.PNG, sizeBytes = 256)),
)
val record = report.records.single()
// tick 1 = harness startedAt, tick 2 = stub's recognisedAt, tick 3 = harness endedAt
assertEquals(1_005L, record.recognisedAtMillis!!)
assertEquals(10L, record.measuredDurationMillis)
}

// ---- non-determinism detection -----------------------------------------

@Test
fun nonDeterministicEnginesAreRejected(): Unit = runBlocking {
val harness = OcrHarness(FlakyEngine(), OcrSettings.DEFAULT, FakeTimeSource())
try {
harness.run(SyntheticImages.defaultHarnessSet())
fail("expected the determinism check to abort the run")
} catch (expected: IllegalStateException) {
assertTrue(expected.message!!.contains("determinism"))
}
}

/**
* Contract-violating double: behaves like the stub for the first 5 calls,
* then silently switches to a different settings derivation — so the
* second harness pass disagrees with the first (switch point is below half
* of the 8-input default set on purpose).
*/
private class FlakyEngine : OcrEngine {
override val engineId: String = "flaky"
private var calls = 0
private val behindIt = DeterministicStubEngine(FakeTimeSource(7L))

override suspend fun recognize(image: OcrImage, settings: OcrSettings): OcrOutcome {
calls += 1
val effective = if (calls <= 5) settings else OcrSettings(languageHints = listOf("xx"))
return behindIt.recognize(image, effective)
}

override fun close() = behindIt.close()
}

// ---- synthetic inputs --------------------------------------------------

@Test
fun defaultHarnessSetIsDeterministic() {
val first = SyntheticImages.defaultHarnessSet()
val second = SyntheticImages.defaultHarnessSet()
assertEquals(first.size, second.size)
first.zip(second).forEach { (a, b) ->
assertEquals(a.width, b.width)
assertEquals(a.height, b.height)
assertEquals(a.format, b.format)
assertEquals(a.rotationDegrees, b.rotationDegrees)
assertTrue(a.bytes.contentEquals(b.bytes))
}
}

}
