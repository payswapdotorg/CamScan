package org.payswap.camscan.ocr.engine.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.ocr.FakeTimeSource
import org.payswap.camscan.ocr.engine.OcrFailure
import org.payswap.camscan.ocr.engine.OcrEngine
import org.payswap.camscan.ocr.engine.OcrImageFormat
import org.payswap.camscan.ocr.engine.OcrOutcome
import org.payswap.camscan.ocr.engine.OcrSettings
import org.payswap.camscan.ocr.engine.mlkit.FakeRecognizerHandle
import org.payswap.camscan.ocr.engine.mlkit.FakeScript
import org.payswap.camscan.ocr.engine.mlkit.FakeRecognizerTask
import org.payswap.camscan.ocr.engine.mlkit.MlKitOcrEngine
import org.payswap.camscan.ocr.harness.SyntheticImages

/**
 * Catalog policy matrix with fakes — the composition rule, not the real ML
 * Kit construction (that is exercised only on-device by the station smoke
 * test): PREFER_LIVE + constructing provider -> live engine; PREFER_LIVE +
 * failing provider -> unavailable; STUB_ONLY -> deterministic stub;
 * UNAVAILABLE -> unavailable.
 */
class OcrEngineCatalogTest {

private val timeSource = FakeTimeSource(startMillis = 90_000L)

private fun liveEngine(): OcrEngine =
MlKitOcrEngine(
FakeRecognizerHandle { FakeRecognizerTask(script = FakeScript.Hang) },
timeSource,
)

@Test
fun preferLiveReturnsTheProviderEngineWhenConstructionSucceeds() {
val catalog = OcrEngineCatalog(timeSource, liveEngineProvider = { liveEngine() })
val engine = catalog.create(OcrEnginePolicy.PREFER_LIVE)
assertEquals("mlkit-text-v2-latin", engine.engineId)
}

@Test
fun preferLiveFallsBackToUnavailableWhenConstructionFails() {
val catalog = OcrEngineCatalog(timeSource, liveEngineProvider = { null })
val engine = catalog.create(OcrEnginePolicy.PREFER_LIVE)
assertEquals("unavailable", engine.engineId)
val outcome = runBlocking { engine.recognize(validImage(), OcrSettings.DEFAULT) }
assertTrue(outcome is OcrOutcome.Failure)
assertEquals(OcrFailure.ENGINE_ERROR("ocr unavailable"), (outcome as OcrOutcome.Failure).reason)
}

@Test
fun stubOnlyReturnsTheDeterministicStubWiredToTheCatalogTimeSource() {
val catalog = OcrEngineCatalog(timeSource, liveEngineProvider = { null })
val engine = catalog.create(OcrEnginePolicy.STUB_ONLY)
assertEquals("stub-deterministic", engine.engineId)
val outcome = runBlocking { engine.recognize(validImage(), OcrSettings.DEFAULT) }
assertTrue(outcome is OcrOutcome.Success)
assertEquals("catalog timeSource feeds the stub", 90_000L, (outcome as OcrOutcome.Success).result.recognisedAtMillis)
}

@Test
fun unavailablePolicyReturnsTheUnavailableEngine() {
val catalog = OcrEngineCatalog(timeSource, liveEngineProvider = { liveEngine() })
val engine = catalog.create(OcrEnginePolicy.UNAVAILABLE)
assertEquals("unavailable", engine.engineId)
}

private fun validImage() =
SyntheticImages.repeating(width = 128, height = 128, format = OcrImageFormat.PNG, patternSeed = 3)
}
