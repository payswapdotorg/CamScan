package org.payswap.camscan.export.print

import org.payswap.camscan.tools.printops.PrintPage

// CAMSCAN-VERIFY-002 — pure geometry of the print flow. Scanned pages
// carry PIXEL dimensions only; the print planner works in millimetres.
// The bridge is the documented ASSUMED_SCAN_DPI (300 — the standard
// scan density): mm = px / dpi * 25.4. This is an assumption, honestly
// declared: pages captured at other densities print proportionally
// larger/smaller under Actual-size mode (Fit/Fill are unaffected —
// they scale into the paper anyway). No android imports.

/** Pixel-to-millimetre bridge of the print flow (documented 300 dpi). */
object PrintGeometry {

    /** The assumed scan density of stored page images. */
    const val ASSUMED_SCAN_DPI = 300.0

    /** Millimetres per inch (exact definition). */
    const val MM_PER_INCH = 25.4

    /** Converts a pixel dimension at [ASSUMED_SCAN_DPI] into millimetres. */
    fun mmFromPx(px: Int): Double = px.toDouble() / ASSUMED_SCAN_DPI * MM_PER_INCH

    /**
     * Builds the planner's page list from pixel geometry: one [PrintPage]
     * per input, 90/270-rotated inputs swap sides (rotation is folded to
     * 0..359 first), every dimension converted at the assumed dpi.
     */
    fun printPagesFrom(
        pages: List<Triple<String, Int, Int>>,
        rotationDegreesOf: (String) -> Int = { 0 },
    ): List<PrintPage> {
        val result = ArrayList<PrintPage>(pages.size)
        for ((pageId, widthPx, heightPx) in pages) {
            val rotation = ((rotationDegreesOf(pageId) % 360) + 360) % 360
            val swapSides = rotation == 90 || rotation == 270
            val width = if (swapSides) heightPx else widthPx
            val height = if (swapSides) widthPx else heightPx
            result.add(
                PrintPage(
                    pageId = pageId,
                    widthMm = mmFromPx(width),
                    heightMm = mmFromPx(height),
                ),
            )
        }
        return result
    }
}
