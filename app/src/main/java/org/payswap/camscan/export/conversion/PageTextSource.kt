package org.payswap.camscan.export.conversion

// CAMSCAN-VERIFY-002 — the text seam between stored page images and the
// Office conversion adapters. The adapters (tools/conversion, delivered
// engines) consume [ConversionDocument] pages of recognized text lines;
// this seam produces those lines from a page's stored bytes plus its
// right-angle rotation. Pure Kotlin interface: JVM tests inject a fake;
// the production implementation is [MlKitPageTextSource] over the OCR
// engine catalog. A null result is an honest failure (the page's text
// could not be recognized) — never an empty-list stand-in.

/** Producer of recognized text lines for one stored page image. */
interface PageTextSource {

    /**
     * Returns the page's recognized text lines in reading order, or null
     * when recognition failed or is unavailable. Line splitting within a
     * recognized block is the implementation's documented choice.
     */
    suspend fun textLinesFor(pageBytes: ByteArray, rotationDegrees: Int): List<String>?

    /** Releases the underlying engine; safe to call more than once. */
    fun close()
}
