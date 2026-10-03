package org.payswap.camscan.tools.printops

// CAMSCAN-PROD-015 §6.3 — PrintMargins: per-side print margins in
// millimetres, validated NON-NEGATIVE and finite. The planner rejects
// invalid margins with the sealed PrintPlanError.BadMargins (it never
// throws from constructor validation — validation is a planner concern).

/** Per-side print margins in millimetres. */
data class PrintMargins(
    val leftMm: Double,
    val topMm: Double,
    val rightMm: Double,
    val bottomMm: Double,
) {

    /** True when every side is finite and >= 0 (the documented validity rule). */
    val isValid: Boolean
        get() {
            if (!leftMm.isFinite()) return false
            if (!topMm.isFinite()) return false
            if (!rightMm.isFinite()) return false
            if (!bottomMm.isFinite()) return false
            if (leftMm < 0.0) return false
            if (topMm < 0.0) return false
            if (rightMm < 0.0) return false
            if (bottomMm < 0.0) return false
            return true
        }

    companion object {

        /** Uniform margins on all four sides. */
        fun uniform(mm: Double): PrintMargins {
            return PrintMargins(mm, mm, mm, mm)
        }
    }
}
