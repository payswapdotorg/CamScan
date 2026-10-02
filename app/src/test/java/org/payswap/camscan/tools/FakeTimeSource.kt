package org.payswap.camscan.tools

import org.payswap.camscan.core.time.TimeSource

// Deterministic fake clock for tools tests (the OCR tree has its own; the
// tools tree owns this copy - no shared test file is touched).
class FakeTimeSource(var now: Long) : TimeSource {

    override fun nowMillis(): Long = now

    fun advance(deltaMillis: Long) {
        now += deltaMillis
    }
}
