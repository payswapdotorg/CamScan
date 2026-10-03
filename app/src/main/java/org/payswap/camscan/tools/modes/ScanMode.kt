package org.payswap.camscan.tools.modes

// CAMSCAN-PROD-012 §6.1 — the specialist scan-mode catalog: pure scan-mode
// intelligence only, NO UI and NO camera wiring (wave-4 lead-owned
// handoffs). Physical dimensions are the AUTHORITY for every derived
// aspect:
//   - ID-1 (ISO/IEC 7810):            85.60 x 53.98 mm
//   - Business card, US variant:      88.9  x 50.8  mm
//   - Business card, EU variant:      85    x 55    mm
//   - Book spread, per face (A5):     148.5 x 210   mm (spread = 2 faces)
// Aspect convention (documented): targetAspect is width / height in the
// LANDSCAPE capture orientation the preview sees — except BookSpreadMode,
// whose observed quad at capture time is the FULL spread (two A5 faces
// side by side = 297 x 210 mm), so its matching aspect is the spread
// aspect (2 * per-face width / per-face height); the per-face millimetre
// dimensions remain the post-split page authority.

/** Stable semantic id strings for the specialist scan modes (future-UI). */
object ScanModeIds {
    const val ID_CARD = "mode-id-card"
    const val BUSINESS_CARD = "mode-business-card"
    const val BOOK_SPREAD = "mode-book-spread"
}

/**
 * Page-split policy a mode implies. NONE: one page per capture.
 * BOOK_SPREAD_MIDLINE: the capture is a two-page spread that the
 * [SpreadSplitPlanner] splits along its midpoint line.
 */
sealed class SplitPolicy {
    object NONE : SplitPolicy()
    object BOOK_SPREAD_MIDLINE : SplitPolicy()
}

/** Regional business-card variants (both ship in the fixed catalog). */
enum class BusinessCardVariant {
    US,
    EU,
}

/**
 * A specialist scan mode: the physical target the user is asked to fill.
 * Pure data; no UI, no capture coupling. tolerance is the RELATIVE aspect
 * tolerance (0.12 = 12 percent) used by [ModeSelector].
 */
sealed class ScanMode {

    /** Stable machine id (e.g. "mode-id-card"); never localized. */
    abstract val modeId: String

    /** Physical target width in millimetres (per-face for spreads). */
    abstract val targetWidthMm: Double

    /** Physical target height in millimetres (per-face for spreads). */
    abstract val targetHeightMm: Double

    /**
     * Aspect the selection machine matches an observed quad against:
     * width / height for single-page modes; the full-spread aspect
     * (2 * width / height) for [BookSpreadMode] — documented above.
     */
    abstract val targetAspect: Double

    /** Relative aspect tolerance; 0.12 (12 percent) for every catalog mode. */
    abstract val tolerance: Double

    /**
     * Stable semantic label id for FUTURE UI (wave-4); equals [modeId] by
     * the §6.1 convention — a label resource stem, never user-visible text.
     */
    abstract val displayLabelId: String

    /** How captures in this mode split into pages. */
    abstract val splitPolicy: SplitPolicy
}

/** ID-1 card scan mode (ISO/IEC 7810 ID-1: 85.60 x 53.98 mm). */
object IdCardMode : ScanMode() {
    override val modeId: String = ScanModeIds.ID_CARD
    override val targetWidthMm: Double = 85.60
    override val targetHeightMm: Double = 53.98
    override val targetAspect: Double = targetWidthMm / targetHeightMm
    override val tolerance: Double = 0.12
    override val displayLabelId: String = ScanModeIds.ID_CARD
    override val splitPolicy: SplitPolicy = SplitPolicy.NONE
}

/**
 * Business-card scan mode; the two regional variants (US 88.9 x 50.8 mm,
 * EU 85 x 55 mm) share the [ScanModeIds.BUSINESS_CARD] semantic id — the
 * catalog-order default for that id is the US variant.
 */
data class BusinessCardMode(val variant: BusinessCardVariant) : ScanMode() {
    override val modeId: String get() = ScanModeIds.BUSINESS_CARD
    override val targetWidthMm: Double
        get() = when (variant) {
            BusinessCardVariant.US -> 88.9
            BusinessCardVariant.EU -> 85.0
        }
    override val targetHeightMm: Double
        get() = when (variant) {
            BusinessCardVariant.US -> 50.8
            BusinessCardVariant.EU -> 55.0
        }
    override val targetAspect: Double get() = targetWidthMm / targetHeightMm
    override val tolerance: Double get() = 0.12
    override val displayLabelId: String get() = ScanModeIds.BUSINESS_CARD
    override val splitPolicy: SplitPolicy get() = SplitPolicy.NONE
}

/**
 * Book-spread scan mode: per-face A5 (148.5 x 210 mm); one capture covers
 * the full spread (two faces side by side) and is split along the midpoint
 * line into LEFT and RIGHT pages.
 */
object BookSpreadMode : ScanMode() {
    override val modeId: String = ScanModeIds.BOOK_SPREAD
    override val targetWidthMm: Double = 148.5
    override val targetHeightMm: Double = 210.0
    override val targetAspect: Double = (2.0 * targetWidthMm) / targetHeightMm
    override val tolerance: Double = 0.12
    override val displayLabelId: String = ScanModeIds.BOOK_SPREAD
    override val splitPolicy: SplitPolicy = SplitPolicy.BOOK_SPREAD_MIDLINE
}

/**
 * The FIXED specialist scan-mode catalog (declaration order is the
 * deterministic iteration and tie-break order: IdCard, BusinessCard US,
 * BusinessCard EU, BookSpread). Pure object — no state, no UI.
 */
object ScanModeCatalog {

    val modes: List<ScanMode> = listOf(
        IdCardMode,
        BusinessCardMode(BusinessCardVariant.US),
        BusinessCardMode(BusinessCardVariant.EU),
        BookSpreadMode,
    )

    val size: Int get() = modes.size

    /**
     * Lookup by [ScanMode.modeId]. "mode-business-card" matches BOTH
     * variants; the catalog-order default returned here is the US variant
     * (deterministic tie-break: catalog order). Use [businessCardVariants]
     * to enumerate both.
     */
    fun byModeId(modeId: String): ScanMode? =
        modes.firstOrNull { it.modeId == modeId }

    /** Both business-card variants, in catalog order (US first). */
    fun businessCardVariants(): List<BusinessCardMode> =
        modes.filterIsInstance<BusinessCardMode>()
}
