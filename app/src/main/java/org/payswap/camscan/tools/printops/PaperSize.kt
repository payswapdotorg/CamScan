package org.payswap.camscan.tools.printops

// CAMSCAN-PROD-015 §6.3 — the paper catalog. EXACT millimetre dimensions
// (the reference table of the planner); stable semantic paperIds
// (future-UI strings, no res change): paper-a3, paper-a4, paper-a5,
// paper-letter, paper-legal, paper-ledger. All values are portrait-native
// (short side first). Lookup by paperId is exact-match.

/** Exact paper dimensions in millimetres, with its stable semantic id. */
data class PaperSize(
    val paperId: String,
    val widthMm: Double,
    val heightMm: Double,
)

/** The frozen paper catalog of the print planner. */
object PaperSizes {

    /** Stable semantic id of A3 paper. */
    const val ID_A3 = "paper-a3"

    /** Stable semantic id of A4 paper. */
    const val ID_A4 = "paper-a4"

    /** Stable semantic id of A5 paper. */
    const val ID_A5 = "paper-a5"

    /** Stable semantic id of US Letter paper. */
    const val ID_LETTER = "paper-letter"

    /** Stable semantic id of US Legal paper. */
    const val ID_LEGAL = "paper-legal"

    /** Stable semantic id of US Ledger paper. */
    const val ID_LEDGER = "paper-ledger"

    /** The catalog, in stable documented order. */
    val ALL: List<PaperSize> = listOf(
        PaperSize(ID_A3, 297.0, 420.0),
        PaperSize(ID_A4, 210.0, 297.0),
        PaperSize(ID_A5, 148.0, 210.0),
        PaperSize(ID_LETTER, 215.9, 279.4),
        PaperSize(ID_LEGAL, 215.9, 355.6),
        PaperSize(ID_LEDGER, 279.4, 431.8),
    )

    /** Exact lookup by paperId; null when unknown. */
    fun byId(paperId: String): PaperSize? {
        for (paper in ALL) {
            if (paper.paperId == paperId) return paper
        }
        return null
    }
}
