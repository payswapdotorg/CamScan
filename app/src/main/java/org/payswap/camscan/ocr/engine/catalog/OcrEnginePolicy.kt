package org.payswap.camscan.ocr.engine.catalog

/**
 * Caller-stated engine selection policy — NO environment sniffing, no
 * capability probing, no magic: the caller says which posture it wants and
 * [OcrEngineCatalog] composes the engine accordingly.
 */
enum class OcrEnginePolicy {
    /**
     * Live engine first: the ML Kit engine when the dependency resolves and
     * recognizer-client construction succeeds; otherwise the unavailable
     * placeholder — OCR NEVER blocks the scan path.
     */
    PREFER_LIVE,

    /**
     * Deterministic stub only: the harness, the JVM tests, and station parity
     * runs stay stub-driven unless a live run is explicitly requested.
     */
    STUB_ONLY,

    /**
     * OCR disabled: every recognition reports ENGINE_ERROR("ocr unavailable")
     * as a value; scanning and saving work unchanged.
     */
    UNAVAILABLE,
}
