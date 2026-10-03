package org.payswap.camscan.tools.printops

import kotlin.math.ceil
import kotlin.math.floor

// CAMSCAN-PROD-015 §6.3 — unit discipline of the print planner. Internal
// math is in millimetres (Double). Print points = mm * 72 / 25.4 (Double)
// — the exact documented conversion (72 points per inch, 25.4 mm per
// inch). The px output view is the point value rounded HALF-UP at
// exactly .5 (ties round away from zero, documented; print geometry is
// non-negative in practice).

/** Unit conversion and rounding helpers of the print planner. */
object PrintUnits {

    /** Millimetres per inch (exact definition). */
    const val MM_PER_INCH = 25.4

    /** Print points per inch (exact definition). */
    const val POINTS_PER_INCH = 72.0

    /** Converts millimetres to print points: mm * 72 / 25.4. */
    fun mmToPoints(mm: Double): Double {
        return mm * POINTS_PER_INCH / MM_PER_INCH
    }

    /** Rounds half-up at exactly .5 (ties away from zero; documented). */
    fun roundHalfUp(value: Double): Long {
        if (value >= 0.0) {
            return floor(value + 0.5).toLong()
        }
        return ceil(value - 0.5).toLong()
    }
}
