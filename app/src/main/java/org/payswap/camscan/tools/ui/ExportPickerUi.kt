package org.payswap.camscan.tools.ui

// CAMSCAN-VERIFY-002 — pure UI state for the export/convert picker.
// No android imports: JVM-testable. The format catalog is the single
// enumeration the export surface renders from; the busy flag is the
// single "one conversion at a time" guard; the recorded outcome is the
// last run's honest result. Merge-safety: this filename is outside the
// parallel VERIFY-001 tools/ui set by work-order convention.

/** One export format offered by the export picker. */
data class ExportFormatSpec(
    val formatId: String,
    val extension: String,
    val requiresRecognizedText: Boolean,
    val textLevelOnly: Boolean,
)

/** Ordered catalog of the export picker's formats (render order). */
object ExportFormatCatalog {

    /** PowerPoint export (PPTX-017 engine, board-assigned flagship). */
    val PPTX = ExportFormatSpec(
        formatId = "pptx",
        extension = ".pptx",
        requiresRecognizedText = true,
        textLevelOnly = true,
    )

    /** Word export (PROD-015 text-level adapter; layout parity UNVERIFIED). */
    val DOCX = ExportFormatSpec(
        formatId = "docx",
        extension = ".docx",
        requiresRecognizedText = true,
        textLevelOnly = true,
    )

    /** Excel export (PROD-015 text-level adapter; layout parity UNVERIFIED). */
    val XLSX = ExportFormatSpec(
        formatId = "xlsx",
        extension = ".xlsx",
        requiresRecognizedText = true,
        textLevelOnly = true,
    )

    /** Long-image export (strip planner + raster applier). */
    val LONG_IMAGE = ExportFormatSpec(
        formatId = "long-image-png",
        extension = ".png",
        requiresRecognizedText = false,
        textLevelOnly = false,
    )

    /** Every offered format, in picker render order. */
    val ALL: List<ExportFormatSpec> = listOf(PPTX, DOCX, XLSX, LONG_IMAGE)

    /** Exact lookup by format id; null when unknown. */
    fun byId(formatId: String): ExportFormatSpec? {
        for (format in ALL) {
            if (format.formatId == formatId) return format
        }
        return null
    }
}

/** The honest per-run outcome the picker records for the last export. */
data class ExportRunOutcome(
    val formatId: String,
    val succeeded: Boolean,
)

/** Busy/outcome state holder of the export picker (one run at a time). */
class ExportPickerUi {

    private var busyFormatId: String? = null
    private var lastRunOutcome: ExportRunOutcome? = null

    /** The format id currently running, or null when idle. */
    fun busyFormat(): String? = busyFormatId

    /** True while any export run is in flight. */
    fun isBusy(): Boolean = busyFormatId != null

    /** True when the format's button is actionable (known format, not busy). */
    fun isFormatEnabled(format: ExportFormatSpec): Boolean {
        if (busyFormatId != null) return false
        return ExportFormatCatalog.byId(format.formatId) != null
    }

    /**
     * Starts a run for the format. Returns false (no state change) when a
     * run is already in flight or the format id is unknown.
     */
    fun begin(formatId: String): Boolean {
        if (busyFormatId != null) return false
        if (ExportFormatCatalog.byId(formatId) == null) return false
        busyFormatId = formatId
        return true
    }

    /**
     * Finishes the running format. Returns true when the call cleared the
     * busy flag; false when idle or a different format was running.
     */
    fun finish(formatId: String, succeeded: Boolean): Boolean {
        val running = busyFormatId ?: return false
        if (running != formatId) return false
        busyFormatId = null
        lastRunOutcome = ExportRunOutcome(formatId, succeeded)
        return true
    }

    /** The last finished run's outcome (survives later runs starting). */
    fun lastOutcome(): ExportRunOutcome? = lastRunOutcome
}
