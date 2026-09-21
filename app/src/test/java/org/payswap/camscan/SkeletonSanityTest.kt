package org.payswap.camscan


import org.junit.Assert.assertEquals
import org.junit.Test


/**
 * CAMSCAN-001: trivial unit test so `testDebugUnitTest` has a runnable target in CI.
 * Feature tests arrive with the parity scenarios.
 */
class SkeletonSanityTest {


    @Test
    fun additionIsSane() {
        assertEquals(4, 2 + 2)
    }
}
