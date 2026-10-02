package org.payswap.camscan.tools.watermark

import org.payswap.camscan.tools.render.DrawOp
import org.payswap.camscan.tools.render.DrawPlan
import org.payswap.camscan.tools.render.DrawSurface
import org.payswap.camscan.tools.render.TextGlyphSource

// WatermarkApplier (CAMSCAN-PROD-011 section 6.4): planner placements ->
// glyph blits with integer alpha blend onto the page buffer. Non-mutating
// discipline: the input page buffer is NEVER modified.
//
// Color rule (documented): the op color is the spec's RGB with the alpha
// byte taken from spec.opacity; the spec color's own alpha byte is
// ignored. opacity 0 skips rendering entirely (also short-circuited in
// the planner).

/** Pure watermark-on-page renderer. */
object WatermarkApplier {

    /** Applies the watermark described by [spec] onto a copy of the page. */
    fun apply(
        page: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        spec: WatermarkSpec,
    ): IntArray {
        require(page.size == pageWidth * pageHeight) { "page buffer size mismatch" }
        if (spec.text.isEmpty() || spec.opacity <= 0) return page.copyOf()

        val glyphSource = TextGlyphSource()
        val planner = WatermarkPlanner(glyphSource)
        val placements = planner.plan(spec, pageWidth, pageHeight)

        val effectiveColor = (spec.opacity.toLong() shl 24) or (spec.colorArgb and 0x00FFFFFFL)
        val ops = ArrayList<DrawOp.BlitGlyphs>(placements.size)
        for (origin in placements) {
            ops.add(DrawOp.BlitGlyphs(spec.text, origin, glyphSource.id, effectiveColor))
        }

        val surface = DrawSurface(pageWidth, pageHeight, glyphSource)
        return surface.render(page, DrawPlan(ops))
    }
}
