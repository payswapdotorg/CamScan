package org.payswap.camscan.tools.printops

// CAMSCAN-PROD-015 §6.3 — Orientation: the documented swap rule. Given a
// paper's native width/height:
//   PORTRAIT  — if native width > native height, the sides are SWAPPED so
//               that width <= height (no swap when already width <= height);
//   LANDSCAPE — if native width < native height, the sides are SWAPPED so
//               that width >= height (no swap when already width >= height).
// A square paper never swaps under either orientation.

/** Requested orientation of the printed sheet. */
sealed class Orientation {

    /** Uses the paper so that width <= height. */
    object Portrait : Orientation()

    /** Uses the paper so that width >= height. */
    object Landscape : Orientation()

    /** Applies the documented swap rule to a paper. */
    fun apply(paper: PaperSize): OrientedPaper {
        return when (this) {
            is Portrait -> {
                if (paper.widthMm > paper.heightMm) {
                    OrientedPaper(paper.heightMm, paper.widthMm, this)
                } else {
                    OrientedPaper(paper.widthMm, paper.heightMm, this)
                }
            }
            is Landscape -> {
                if (paper.widthMm < paper.heightMm) {
                    OrientedPaper(paper.heightMm, paper.widthMm, this)
                } else {
                    OrientedPaper(paper.widthMm, paper.heightMm, this)
                }
            }
        }
    }
}

/** Paper dimensions after the orientation swap rule has been applied. */
data class OrientedPaper(
    val widthMm: Double,
    val heightMm: Double,
    val orientation: Orientation,
)
