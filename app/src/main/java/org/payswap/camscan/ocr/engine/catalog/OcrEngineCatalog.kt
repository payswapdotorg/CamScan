package org.payswap.camscan.ocr.engine.catalog

import org.payswap.camscan.core.time.TimeSource
import org.payswap.camscan.ocr.engine.DeterministicStubEngine
import org.payswap.camscan.ocr.engine.OcrEngine
import org.payswap.camscan.ocr.engine.UnavailableEngine
import org.payswap.camscan.ocr.engine.mlkit.MlKitEngineFactory

/**
 * Engine catalog (library seam) — the ONE factory callers use to obtain an
 * [OcrEngine] under an explicit [OcrEnginePolicy]. Documented composition
 * rule, nothing ambient:
 *
 * - PREFER_LIVE -> the ML Kit live engine when [liveEngineProvider]
 *   constructs one (dependency resolved + recognizer client built), else
 *   [UnavailableEngine];
 * - STUB_ONLY -> [DeterministicStubEngine] (harness / JVM tests / parity
 *   runs);
 * - UNAVAILABLE -> [UnavailableEngine].
 *
 * This class is JVM-clean and fully fake-testable: the live-engine
 * construction is injected as [liveEngineProvider]. The production provider
 * ([MlKitEngineFactory.createOrNull], defined in the android-side adapter
 * file) is wired by [default]; it is only ever invoked on a real build, so
 * JVM tests never load the ML Kit classes.
 *
 * RUNTIME WIRING IS NOT THIS ORDER'S (PROD-010) WORK: the catalog ships as a
 * library seam. The PROD-014 integration point is MainActivity, with
 * something like:
 *
 * `val engine = OcrEngineCatalog.default(TimeSource.SYSTEM).create(OcrEnginePolicy.PREFER_LIVE)`
 *
 * …recognized on a worker dispatcher (the ML Kit path decodes the image
 * synchronously on the calling coroutine's thread — see MlKitAdapter's KDoc)
 * and closed from the same scope that created it.
 */
class OcrEngineCatalog(
    private val timeSource: TimeSource,
    private val liveEngineProvider: () -> OcrEngine?,
) {

    /** The engine composed for [policy]; never null, never throws. */
    fun create(policy: OcrEnginePolicy): OcrEngine = when (policy) {
        OcrEnginePolicy.PREFER_LIVE -> liveEngineProvider() ?: UnavailableEngine()
        OcrEnginePolicy.STUB_ONLY -> DeterministicStubEngine(timeSource)
        OcrEnginePolicy.UNAVAILABLE -> UnavailableEngine()
    }

    companion object {
        /**
         * Production catalog: the live provider attempts ML Kit engine
         * construction and reports null on failure, letting the catalog fall
         * back to UnavailableEngine.
         */
        fun default(timeSource: TimeSource): OcrEngineCatalog =
            OcrEngineCatalog(
                timeSource = timeSource,
                liveEngineProvider = { MlKitEngineFactory.createOrNull(timeSource) },
            )
    }
}
