package org.payswap.camscan.ocr.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.ocr.harness.SyntheticImages

class UnavailableEngineTest {

private suspend fun reasonFor(engine: UnavailableEngine, image: OcrImage): OcrFailure {
val outcome = engine.recognize(image, OcrSettings.DEFAULT)
assertTrue("expected Failure, got $outcome", outcome is OcrOutcome.Failure)
return (outcome as OcrOutcome.Failure).reason
}

@Test
fun everyRecognitionFailsWithTheDocumentedReason(): Unit = runBlocking {
val engine = UnavailableEngine()
assertEquals("unavailable", engine.engineId)
SyntheticImages.defaultHarnessSet().forEach { image ->
val reason = reasonFor(engine, image)
assertTrue(reason is OcrFailure.ENGINE_ERROR)
assertEquals("ocr unavailable", (reason as OcrFailure.ENGINE_ERROR).message)
}
}

@Test
fun closeIsANoOpAndRecognitionStillFailsAfterwards(): Unit = runBlocking {
val engine = UnavailableEngine()
engine.close()
val reason = reasonFor(engine, SyntheticImages.repeating(64, 64, OcrImageFormat.PNG))
assertEquals("ocr unavailable", (reason as OcrFailure.ENGINE_ERROR).message)
}

}
