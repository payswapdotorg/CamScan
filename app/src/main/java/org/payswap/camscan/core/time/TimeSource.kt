package org.payswap.camscan.core.time


/**
 * Deterministic time seam (PRODUCT-ARCHITECTURE-LOCK §3, core/time). Product
 * code never calls System.currentTimeMillis() directly; it reads time through
 * an injected TimeSource so tests and evidence pipelines stay deterministic.
 * Lead-owned.
 */
fun interface TimeSource {
    fun nowMillis(): Long


    companion object {
        val SYSTEM: TimeSource = TimeSource { System.currentTimeMillis() }
    }
}
