package org.payswap.camscan.tools.ui

import org.payswap.camscan.tools.printops.Orientation
import org.payswap.camscan.tools.printops.PaperSizes
import org.payswap.camscan.tools.printops.PrintMargins
import org.payswap.camscan.tools.printops.PrintRequest
import org.payswap.camscan.tools.printops.PrintScaleMode

// CAMSCAN-VERIFY-002 — pure UI state of the print options row on the
// export surface. Holds the user's paper + scale-mode selection and
// builds the planner's [PrintRequest] from it. Honest defaults: A4
// portrait, uniform 10 mm margins, Fit, one copy, all pages — the
// system print dialog still offers its own final options. No android
// imports: JVM-testable.

/** Paper choices the print row offers (semantic ids of the paper catalog). */
object PrintPaperChoices {

    /** Semantic ids offered, in render order. */
    val ALL: List<String> = listOf(PaperSizes.ID_A4, PaperSizes.ID_LETTER)

    /** True when the id is one of the offered papers. */
    fun isOffered(paperId: String): Boolean = paperId in ALL
}

/** Scale-mode choices the print row offers. */
object PrintScaleChoices {

    /** Semantic id of the Fit scale mode (letterboxing allowed). */
    const val FIT = "fit"

    /** Semantic id of the Fill scale mode (cropping allowed). */
    const val FILL = "fill"

    /** Semantic id of the Actual-size scale mode. */
    const val ACTUAL = "actual"

    /** Semantic ids offered, in render order. */
    val ALL: List<String> = listOf(FIT, FILL, ACTUAL)

    /** True when the id is one of the offered scale modes. */
    fun isOffered(scaleModeId: String): Boolean = scaleModeId in ALL
}

/** State holder + request builder of the print options row. */
class PrintPickerUi(
    initialPaperId: String = PaperSizes.ID_A4,
    initialScaleModeId: String = PrintScaleChoices.FIT,
) {

    private var paperIdValue: String = initialPaperId
    private var scaleModeIdValue: String = initialScaleModeId

    /** Selected paper semantic id (a [PaperSizes] id). */
    fun paperId(): String = paperIdValue

    /** Selected scale-mode semantic id (a [PrintScaleChoices] id). */
    fun scaleModeId(): String = scaleModeIdValue

    /**
     * Selects a paper. Returns false (no state change) when the id is not
     * one of the offered papers.
     */
    fun selectPaper(paperId: String): Boolean {
        if (!PrintPaperChoices.isOffered(paperId)) return false
        paperIdValue = paperId
        return true
    }

    /**
     * Selects a scale mode. Returns false (no state change) when the id is
     * not one of the offered scale modes.
     */
    fun selectScaleMode(scaleModeId: String): Boolean {
        if (!PrintScaleChoices.isOffered(scaleModeId)) return false
        scaleModeIdValue = scaleModeId
        return true
    }

    /** Builds the planner request from the current selection. */
    fun buildRequest(): PrintRequest {
        return PrintRequest(
            paperId = paperIdValue,
            orientation = Orientation.Portrait,
            margins = PrintMargins.uniform(DEFAULT_MARGIN_MM),
            scaleMode = toScaleMode(scaleModeIdValue),
            pageRange = PageRanges_ALL,
            copies = 1,
        )
    }

    /** Maps the semantic scale id onto the planner's sealed mode. */
    fun toScaleMode(scaleModeId: String): PrintScaleMode {
        return when (scaleModeId) {
            PrintScaleChoices.FILL -> PrintScaleMode.Fill
            PrintScaleChoices.ACTUAL -> PrintScaleMode.Actual
            else -> PrintScaleMode.Fit
        }
    }

    companion object {
        /** Uniform default margins in millimetres (documented choice). */
        const val DEFAULT_MARGIN_MM = 10.0

        // Spelled without a reference so this file imports one thing less.
        private const val PageRanges_ALL = "all"
    }
}
