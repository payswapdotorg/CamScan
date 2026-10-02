package org.payswap.camscan.tools.annotation

import org.payswap.camscan.tools.render.DrawPlan
import org.payswap.camscan.tools.render.DrawSurface
import org.payswap.camscan.tools.render.TextGlyphSource

// AnnotationApplier (CAMSCAN-PROD-011 section 6.3): renders a document's
// annotation plan onto a page buffer through the DrawSurface. Non-mutating
// discipline: the input page buffer is NEVER modified.

/** Pure annotation-on-page renderer. */
object AnnotationApplier {

    /** Applies the store's plan for [documentId] onto a copy of the page. */
    fun apply(
        page: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        store: AnnotationStore,
        documentId: String,
    ): IntArray {
        return applyPlan(page, pageWidth, pageHeight, store.plan(documentId, TextGlyphSource()))
    }

    /** Applies an arbitrary annotation [plan] onto a copy of the page. */
    fun applyPlan(
        page: IntArray,
        pageWidth: Int,
        pageHeight: Int,
        plan: DrawPlan,
    ): IntArray {
        require(page.size == pageWidth * pageHeight) { "page buffer size mismatch" }
        val surface = DrawSurface(pageWidth, pageHeight)
        return surface.render(page, plan)
    }
}
