package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.signature.SignatureStroke

// SignaturePadUiTest (CAMSCAN-VERIFY-001): pad capture through the frozen
// reducer, empty-save gating, and signature name validation.

class SignaturePadUiTest {

    @Test
    fun cleanPad_hasNoInk_andEmptySketch() {
        val pad = SignaturePadUi()
        assertFalse(pad.hasInk())
        assertTrue(pad.state.isClean)
        assertTrue(pad.sketch().isEmpty)
    }

    @Test
    fun oneGesture_producesOneFinishedStroke() {
        val pad = SignaturePadUi()
        pad.penDown(10, 10)
        pad.penMove(40, 50)
        pad.penUp()
        assertTrue(pad.hasInk())
        val sketch = pad.sketch()
        assertEquals(1, sketch.strokes.size)
        val stroke = sketch.strokes[0]
        assertEquals(listOf(Point(10, 10), Point(40, 50)), stroke.points)
        assertEquals(3, stroke.strokeWidthPx)
    }

    @Test
    fun dotTap_survivesAsSinglePointStroke() {
        val pad = SignaturePadUi()
        pad.penDown(7, 9)
        pad.penUp()
        assertEquals(1, pad.sketch().strokes.size)
        assertEquals(1, pad.sketch().strokes[0].points.size)
        assertEquals(Point(7, 9), pad.sketch().strokes[0].points[0])
    }

    @Test
    fun inProgressStroke_isIncludedInTheSavedSketch() {
        val pad = SignaturePadUi()
        pad.penDown(5, 5)
        pad.penMove(25, 25)
        // No penUp: the fragment saves on button press mid-stroke.
        val sketch = pad.sketch()
        assertEquals(1, sketch.strokes.size)
        assertEquals(2, sketch.strokes[0].points.size)
    }

    @Test
    fun penDown_closesAnAbandonedInProgressStroke() {
        val pad = SignaturePadUi()
        pad.penDown(10, 10)
        pad.penMove(20, 20)
        pad.penDown(30, 30)
        assertEquals(1, pad.state.finishedStrokes.size)
        assertEquals(listOf(Point(30, 30)), pad.state.inProgress!!.points)
        pad.penUp()
        assertEquals(2, pad.sketch().strokes.size)
    }

    @Test
    fun closeMoves_areThinnedByTheFrozenReducer() {
        val pad = SignaturePadUi()
        pad.penDown(10, 10)
        pad.penMove(11, 11)
        pad.penUp()
        val stroke: SignatureStroke = pad.sketch().strokes[0]
        assertEquals(1, stroke.points.size)
    }

    @Test
    fun clear_resetsToCleanState() {
        val pad = SignaturePadUi()
        pad.penDown(10, 10)
        pad.penMove(40, 50)
        pad.penUp()
        pad.clear()
        assertFalse(pad.hasInk())
        assertTrue(pad.sketch().isEmpty)
    }

    @Test
    fun validName_rules() {
        assertFalse(SignaturePadUi.validName(""))
        assertFalse(SignaturePadUi.validName("   "))
        assertFalse(SignaturePadUi.validName("a".repeat(41)))
        assertTrue(SignaturePadUi.validName("My signature"))
        assertTrue(SignaturePadUi.validName("  trimmed  "))
    }
}
