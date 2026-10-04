package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.annotation.Annotation
import org.payswap.camscan.tools.annotation.toDrawPlan
import org.payswap.camscan.tools.render.DrawOp
import org.payswap.camscan.tools.render.Point
import org.payswap.camscan.tools.render.Rect
import org.payswap.camscan.tools.render.TextGlyphSource

// AnnotationEditorUiTest (CAMSCAN-VERIFY-001): the three annotation model
// drafts (Ink gesture, Highlight rect, TextNote tap+text), clamping, undo,
// and the preview/commit plan builders the applier consumes.

class AnnotationEditorUiTest {

    private var nextId = 0

    private fun editor(): AnnotationEditorUi =
        AnnotationEditorUi(100, 200) { "ann-" + (nextId++) }

    @Test
    fun inkGesture_createsOneInkAnnotationWithTheStroke() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.INK)
        editor.penDown(10, 10)
        editor.penMove(40, 50)
        editor.penUp()
        val annotations = editor.sessionAnnotations()
        assertEquals(1, annotations.size)
        val ink = annotations[0] as Annotation.Ink
        assertEquals(1, ink.strokes.size)
        assertEquals(listOf(Point(10, 10), Point(40, 50)), ink.strokes[0].points)
        assertTrue(editor.hasEdits())
    }

    @Test
    fun multipleInkGestures_accumulateInOrder() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.INK)
        editor.penDown(1, 1)
        editor.penUp()
        editor.penDown(2, 2)
        editor.penUp()
        editor.penDown(3, 3)
        editor.penUp()
        assertEquals(3, editor.sessionAnnotations().size)
        assertEquals(3, editor.annotationCount())
    }

    @Test
    fun penCoordinates_clampToThePageBounds() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.INK)
        editor.penDown(-50, -50)
        editor.penMove(500, 900)
        editor.penUp()
        val stroke = (editor.sessionAnnotations()[0] as Annotation.Ink).strokes[0]
        assertEquals(Point(0, 0), stroke.points[0])
        assertEquals(Point(99, 199), stroke.points[1])
    }

    @Test
    fun highlightDrag_normalizesToAPositiveRect() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.HIGHLIGHT)
        editor.penDown(50, 70)
        editor.penMove(10, 20)
        editor.penUp()
        val highlight = editor.sessionAnnotations()[0] as Annotation.Highlight
        assertEquals(Rect(10, 20, 41, 51), highlight.rect)
    }

    @Test
    fun highlightDrag_clampsToThePageBounds() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.HIGHLIGHT)
        editor.penDown(-20, -20)
        editor.penMove(140, 260)
        editor.penUp()
        val highlight = editor.sessionAnnotations()[0] as Annotation.Highlight
        assertEquals(Rect(0, 0, 100, 200), highlight.rect)
    }

    @Test
    fun highlightTapWithoutMove_isDiscarded() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.HIGHLIGHT)
        editor.penDown(20, 20)
        editor.penUp()
        assertEquals(0, editor.sessionAnnotations().size)
        assertFalse(editor.hasEdits())
    }

    @Test
    fun textNote_requiresAnchorAndNonBlankText() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.TEXT)
        assertNull(editor.confirmText("hello"))
        editor.penDown(30, 40)
        assertNotNull(editor.pendingTextAnchor())
        assertNull(editor.confirmText("   "))
        val note = editor.confirmText("hello")
        assertNotNull(note)
        assertEquals("hello", note!!.text)
        assertEquals(Point(30, 40), note.anchor)
        assertEquals(1, editor.sessionAnnotations().size)
    }

    @Test
    fun undoLast_removesTheMostRecentAnnotationOnly() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.HIGHLIGHT)
        editor.penDown(5, 5)
        editor.penMove(15, 15)
        editor.penUp()
        editor.selectMode(AnnotationEditorUi.Mode.INK)
        editor.penDown(25, 25)
        editor.penUp()
        assertTrue(editor.undoLast())
        assertEquals(1, editor.sessionAnnotations().size)
        assertTrue(editor.sessionAnnotations()[0] is Annotation.Highlight)
        assertTrue(editor.undoLast())
        assertFalse(editor.undoLast())
    }

    @Test
    fun draftPreviewPlan_includesLiveInkAndDraftRect() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.INK)
        editor.penDown(10, 10)
        editor.penMove(30, 30)
        editor.penUp()
        // In-progress stroke (no penUp yet on the second gesture).
        editor.penDown(50, 50)
        editor.penMove(70, 70)
        val plan = editor.draftPreviewPlan(TextGlyphSource())
        val strokeOps = plan.ops.filterIsInstance<DrawOp.Stroke>()
        assertEquals(2, strokeOps.size)
        // Switch to highlight with a live draft: the plan gains a BlendRect.
        editor.selectMode(AnnotationEditorUi.Mode.HIGHLIGHT)
        editor.penDown(5, 5)
        editor.penMove(25, 25)
        val planWithDraft = editor.draftPreviewPlan(TextGlyphSource())
        assertTrue(planWithDraft.ops.any { it is DrawOp.BlendRect })
        assertNotNull(editor.highlightDraftRect())
    }

    @Test
    fun commitPlan_matchesTheEngineExtensionOverTheSession() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.HIGHLIGHT)
        editor.penDown(5, 5)
        editor.penMove(25, 25)
        editor.penUp()
        editor.selectMode(AnnotationEditorUi.Mode.TEXT)
        editor.penDown(30, 30)
        editor.confirmText("note")
        val glyphSource = TextGlyphSource()
        val expected = editor.sessionAnnotations().toDrawPlan(glyphSource)
        assertEquals(expected.ops, editor.commitPlan(glyphSource).ops)
    }

    @Test
    fun clearTextAnchor_dropsThePendingAnchor() {
        val editor = editor()
        editor.selectMode(AnnotationEditorUi.Mode.TEXT)
        editor.penDown(10, 10)
        assertNotNull(editor.pendingTextAnchor())
        editor.clearTextAnchor()
        assertNull(editor.pendingTextAnchor())
        assertNull(editor.confirmText("late"))
    }
}
