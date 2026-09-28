package org.payswap.camscan.capture.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAMSCAN-PROD-002 §6.7 — the temporal-stabilization suite (pure JUnit 4, no
 * android.* imports). Pins the §6.3 contract:
 *
 *  - agreement ladder: requiredAgreementFrames-1 agreeing detections are NOT
 *    stable, the required-th one IS;
 *  - disagreement resets the run;
 *  - loss hysteresis: the held quad survives holdoutFrames misses, then null;
 *  - MOTION_UNSTABLE surfaces after more than one corner-tolerance breach;
 *  - constructor parameter overrides (required / tolerance / IoU / holdout)
 *    are honored, illegal values clamped.
 */
class DetectionStabilizerTest {

    // A 200x150 page quad — the stable reference family.
    private val quadA = SyntheticFrames.quad(
        topLeft = 100 to 100,
        topRight = 300 to 100,
        bottomRight = 300 to 250,
        bottomLeft = 100 to 250,
    )

    /** Same shape shifted 3px — comfortably inside the 24px corner tolerance. */
    private val quadAAgreed = SyntheticFrames.shifted(quadA, 3, 2)

    /**
     * Same shape with ONE corner moved 30px: IoU stays high (>= 0.85) but the
     * per-corner distance breaches the 24px tolerance — jitter evidence.
     */
    private val quadABreach = listOf(
        Corner(100, 100),
        Corner(300, 100),
        Corner(300, 250),
        Corner(100, 280), // BL pushed 30px down
    )

    /** A genuinely different page — IoU vs quadA far below 0.85. */
    private val quadB = SyntheticFrames.quad(
        topLeft = 20 to 20,
        topRight = 120 to 20,
        bottomRight = 120 to 90,
        bottomLeft = 20 to 90,
    )

    private fun detection(
        corners: List<Corner>,
        timestamp: Long,
        confidence: Float = 0.9f,
        flags: Set<DetectionQualityFlag> = emptySet(),
    ) = DocumentDetection(
        corners = corners,
        confidence = confidence,
        qualityFlags = flags,
        frameTimestampMs = timestamp,
    )

    // ------------------------------------------------------------------ agreement ladder

    @Test
    fun `two agreeing frames are not stable, the third is`() {
        val stabilizer = DetectionStabilizer() // required = 3

        assertNull(stabilizer.update(detection(quadA, 1)))
        assertNull(stabilizer.update(detection(quadAAgreed, 2)))

        val stable = stabilizer.update(detection(quadAAgreed, 3))
        assertNotNull("the 3rd consecutive agreeing detection must be stable", stable)
        assertEquals(3, stable!!.stableForFrames)
        assertEquals(quadAAgreed, stable.corners)
    }

    @Test
    fun `single frame with required=1 is immediately stable`() {
        val stabilizer = DetectionStabilizer(requiredAgreementFrames = 1)
        val stable = stabilizer.update(detection(quadA, 1))
        assertNotNull(stable)
        assertEquals(1, stable!!.stableForFrames)
    }

    @Test
    fun `stable run keeps incrementing stableForFrames`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.update(detection(quadA, 1))
        stabilizer.update(detection(quadA, 2))
        var stable = stabilizer.update(detection(quadA, 3))
        assertNotNull(stable)
        assertEquals(3, stable!!.stableForFrames)
        stable = stabilizer.update(detection(quadAAgreed, 4))
        assertEquals(4, stable!!.stableForFrames)
        stable = stabilizer.update(detection(quadAAgreed, 5))
        assertEquals(5, stable!!.stableForFrames)
    }

    // ------------------------------------------------------------------ disagreement

    @Test
    fun `disagreement resets the agreement run`() {
        val stabilizer = DetectionStabilizer()

        assertNull(stabilizer.update(detection(quadA, 1)))
        assertNull(stabilizer.update(detection(quadAAgreed, 2)))
        // A page jump restarts the run from the new quad — the reset counts
        // the new detection as run member 1.
        assertNull(stabilizer.update(detection(quadB, 3)))
        // Two more agreeing B-detections: run 2, then run 3 = stable.
        assertNull(stabilizer.update(detection(quadB, 4)))
        val stable = stabilizer.update(detection(quadB, 5))
        assertNotNull("the third consecutive B detection must be stable", stable)
        assertEquals(quadB, stable!!.corners)
        assertEquals(3, stable.stableForFrames)
    }

    @Test
    fun `misses during SEARCHING reset the run`() {
        val stabilizer = DetectionStabilizer()
        assertNull(stabilizer.update(detection(quadA, 1)))
        assertNull(stabilizer.update(null))
        // The run restarts: 1, 2 after the miss.
        assertNull(stabilizer.update(detection(quadA, 2)))
        assertNull(stabilizer.update(detection(quadAAgreed, 3)))
        val stable = stabilizer.update(detection(quadAAgreed, 4))
        assertNotNull("run must restart after a miss", stable)
        assertEquals(3, stable!!.stableForFrames)
    }

    // ------------------------------------------------------------------ holdout hysteresis

    @Test
    fun `holdout keeps the last stable quad for holdoutFrames misses then emits null`() {
        val stabilizer = DetectionStabilizer() // holdout = 5
        val stable = stabilizer.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))!!
        }
        assertEquals(3, stable.stableForFrames)

        // Five misses: the held quad (frozen run length) is re-emitted.
        for (i in 1..5) {
            val held = stabilizer.update(null)
            assertNotNull("miss $i within the 5-frame holdout must hold", held)
            assertEquals(3, held!!.stableForFrames)
            assertEquals(quadA, held.corners)
        }

        // The sixth miss: holdout expired — stable detection lost.
        assertNull("after holdoutFrames misses the stabilizer emits null", stabilizer.update(null))
    }

    @Test
    fun `agreeing detection during holdout resumes stability`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }
        // Enter holdout with one miss.
        val held = stabilizer.update(null)
        assertNotNull(held)

        // The page re-appears agreeing with the held quad: resume immediately.
        val resumed = stabilizer.update(detection(quadAAgreed, 5))
        assertNotNull("an agreeing frame during holdout resumes stability", resumed)
        assertEquals(4, resumed!!.stableForFrames) // frozen 3 + 1
        assertEquals(quadAAgreed, resumed.corners)
    }

    @Test
    fun `holdout=0 drops the stable quad immediately`() {
        val stabilizer = DetectionStabilizer(holdoutFrames = 0)
        stabilizer.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }
        assertNull("zero holdout must emit null on the first miss", stabilizer.update(null))
    }

    @Test
    fun `holdout counts disagreeing frames as misses`() {
        val stabilizer = DetectionStabilizer() // holdout = 5
        stabilizer.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }
        // Five frames of a completely different page = holdout countdown.
        for (i in 1..5) {
            assertNotNull("disagreeing frame $i within holdout must hold", stabilizer.update(detection(quadB, 10L + i)))
        }
        assertNull(stabilizer.update(detection(quadB, 16)))
    }

    @Test
    fun `stable quad is re-earned after the holdout expires`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }
        // Expire the holdout.
        repeat(6) { stabilizer.update(null) }
        assertNull(stabilizer.update(null))

        // A fresh agreeing run re-earns stability from scratch.
        assertNull(stabilizer.update(detection(quadB, 20)))
        assertNull(stabilizer.update(detection(quadB, 21)))
        val stable = stabilizer.update(detection(quadB, 22))
        assertNotNull(stable)
        assertEquals(quadB, stable!!.corners)
        assertTrue(
            "breach bookkeeping resets when stability is re-earned",
            DetectionQualityFlag.MOTION_UNSTABLE !in stable.qualityFlags,
        )
    }

    // ------------------------------------------------------------------ MOTION_UNSTABLE

    @Test
    fun `corner-tolerance breaches surface MOTION_UNSTABLE once more than one accumulated`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }

        // First breach: held quad, no flag yet (limit is "more than once").
        val afterFirstBreach = stabilizer.update(detection(quadABreach, 4))
        assertNotNull(afterFirstBreach)
        assertTrue(
            DetectionQualityFlag.MOTION_UNSTABLE !in afterFirstBreach!!.qualityFlags,
        )

        // A miss keeps the held quad (still within holdout).
        assertNotNull(stabilizer.update(null))

        // Second breach: now more than one in the stable window.
        val afterSecondBreach = stabilizer.update(detection(quadABreach, 6))
        assertNotNull(afterSecondBreach)
        assertTrue(
            "two breaches in the stable window must surface MOTION_UNSTABLE",
            DetectionQualityFlag.MOTION_UNSTABLE in afterSecondBreach!!.qualityFlags,
        )
    }

    @Test
    fun `no breach no MOTION_UNSTABLE`() {
        val stabilizer = DetectionStabilizer()
        var emission = stabilizer.update(detection(quadA, 1))
        emission = stabilizer.update(detection(quadAAgreed, 2))
        emission = stabilizer.update(detection(quadAAgreed, 3))
        assertNotNull(emission)
        assertTrue(DetectionQualityFlag.MOTION_UNSTABLE !in emission!!.qualityFlags)
    }

    // ------------------------------------------------------------------ malformed input

    @Test
    fun `malformed detection with three corners is ignored as a miss`() {
        val stabilizer = DetectionStabilizer()
        stabilizer.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }
        val malformed = DocumentDetection(
            corners = quadA.take(3),
            confidence = 0.9f,
            qualityFlags = emptySet(),
            frameTimestampMs = 4,
        )
        // Ignored as a miss: holdout holds the quad instead of crashing.
        val held = stabilizer.update(malformed)
        assertNotNull(held)
        assertTrue(stabilizer.diagnostics.isNotEmpty())
    }

    // ------------------------------------------------------------------ constructor parameter overrides

    @Test
    fun `requiredAgreementFrames override is honored`() {
        val fast = DetectionStabilizer(requiredAgreementFrames = 2)
        assertNull(fast.update(detection(quadA, 1)))
        assertNotNull(fast.update(detection(quadAAgreed, 2)))

        val slow = DetectionStabilizer(requiredAgreementFrames = 5)
        assertNull(slow.update(detection(quadA, 1)))
        assertNull(slow.update(detection(quadA, 2)))
        assertNull(slow.update(detection(quadA, 3)))
        assertNull(slow.update(detection(quadA, 4)))
        assertNotNull(slow.update(detection(quadA, 5)))
    }

    @Test
    fun `cornerTolerance override is honored`() {
        // 30px corner move: breach at the 24px default, agreement at 50px.
        val tight = DetectionStabilizer(cornerTolerancePx = 24.0)
        tight.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }
        val held = tight.update(detection(quadABreach, 4))
        // Breach entered holdout: emission is the OLD quad, not the breach.
        assertNotNull(held)
        assertEquals(quadA, held!!.corners)

        val loose = DetectionStabilizer(cornerTolerancePx = 50.0)
        loose.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
            update(detection(quadA, 3))
        }
        val moved = loose.update(detection(quadABreach, 4))
        assertNotNull(moved)
        assertEquals(
            "a 30px move inside a 50px tolerance is agreement, not a breach",
            quadABreach,
            moved!!.corners,
        )
    }

    @Test
    fun `iouThreshold override is honored`() {
        val quadShiftedFar = SyntheticFrames.shifted(quadA, 60, 0)
        // IoU of quadA vs 60px-right shift is well below 0.85.
        assertTrue(QuadGeometry.iou(quadA, quadShiftedFar) < 0.85)

        val strict = DetectionStabilizer(iouThreshold = 0.85)
        strict.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
        }
        val held = strict.update(detection(quadShiftedFar, 3))
        // Disagreement: run reset — no stability yet, emission null.
        assertNull("a far shift must disagree at the default IoU gate", held)

        val lenient = DetectionStabilizer(iouThreshold = 0.01, cornerTolerancePx = 80.0)
        lenient.run {
            update(detection(quadA, 1))
            update(detection(quadA, 2))
        }
        val stable = lenient.update(detection(quadShiftedFar, 3))
        assertNotNull("corner distance dominates when the IoU gate is lenient", stable)
    }

    @Test
    fun `illegal constructor values are clamped with a diagnostic`() {
        val clamped = DetectionStabilizer(
            requiredAgreementFrames = 0, // -> 1
            cornerTolerancePx = -5.0, // -> default 24
            iouThreshold = 5.0, // -> 1.0
            holdoutFrames = -1, // -> 0
        )
        assertTrue(clamped.diagnostics.isNotEmpty())

        // required clamped to 1: a single detection is stable.
        val stable = clamped.update(detection(quadA, 1))
        assertNotNull(stable)
        // holdout clamped to 0: the first miss drops the quad.
        assertNull(clamped.update(null))
    }

    @Test
    fun `quality flags pass through to the stable emission`() {
        val stabilizer = DetectionStabilizer()
        val flagged = detection(
            quadA,
            1,
            flags = setOf(DetectionQualityFlag.LOW_CONTRAST, DetectionQualityFlag.PARTIAL_PAGE),
        )
        stabilizer.update(flagged)
        stabilizer.update(flagged)
        val stable = stabilizer.update(flagged)
        assertNotNull(stable)
        assertEquals(
            setOf(DetectionQualityFlag.LOW_CONTRAST, DetectionQualityFlag.PARTIAL_PAGE),
            stable!!.qualityFlags,
        )
    }
}
