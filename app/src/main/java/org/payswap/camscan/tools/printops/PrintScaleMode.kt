package org.payswap.camscan.tools.printops

// CAMSCAN-PROD-015 §6.3 — PrintScaleMode: how page content is scaled
// into the margin-bounded content area of the sheet. The content-rect
// math (per mode, fully documented in PrintJobPlanner):
//   Fit    — CONTAIN: scale = min(areaW / contentW, areaH / contentH);
//            the content is fully inside the area and may letterbox.
//   Fill   — COVER: scale = max(areaW / contentW, areaH / contentH);
//            the area is fully covered, content may be cropped.
//   Actual — 100%: scale = 1.0; content is centered at original size and
//            may overflow the area (and the sheet).

/** How page content is scaled onto the printable area. */
sealed class PrintScaleMode {

    /** Contain: content fully inside the area; may letterbox. */
    object Fit : PrintScaleMode()

    /** Cover: area fully covered; content may be cropped. */
    object Fill : PrintScaleMode()

    /** 100% scale, centered; may overflow. */
    object Actual : PrintScaleMode()
}
