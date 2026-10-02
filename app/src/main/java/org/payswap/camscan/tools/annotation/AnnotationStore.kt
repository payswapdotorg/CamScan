package org.payswap.camscan.tools.annotation

import org.payswap.camscan.tools.render.DrawPlan
import org.payswap.camscan.tools.render.TextGlyphSource

// AnnotationStore (CAMSCAN-PROD-011 section 6.3): a per-document ordered
// annotation set. The z order is TOTAL and deterministic: after every
// mutation the stored zIndex values are re-assigned densely as
// 0, 1, 2, ... in the current order, so there are never gaps and never
// ties. Document ids are opaque keys (the durable Document model ids).

/** Per-document ordered annotation store with total z ordering. */
class AnnotationStore {

    private val byDocument = LinkedHashMap<String, MutableList<Annotation>>()

     // Adds [annotation] for [documentId]; its zIndex is REASSIGNED to the
     // current tail of that document's order (the caller-supplied zIndex on
     // the passed instance is ignored and the stored instance is returned).
     // /
    fun add(documentId: String, annotation: Annotation): Annotation {
        val list = listFor(documentId)
        val stored = withZIndex(annotation, list.size)
        list.add(stored)
        return stored
    }

    /** Removes the annotation; the remaining z order is re-assigned densely. */
    fun remove(documentId: String, annotationId: String): Boolean {
        val list = byDocument[documentId] ?: return false
        val removed = list.removeAll { it.annotationId == annotationId }
        if (removed && list.isEmpty()) {
            byDocument.remove(documentId)
        } else if (removed) {
            reindex(list)
        }
        return removed
    }

     // Moves [annotationId] to [newZIndex] (clamped into 0..n-1) inside its
     // document's order; all zIndex values are then re-assigned densely.
     // Returns the moved annotation or null when the id is unknown.
     // /
    fun reorder(documentId: String, annotationId: String, newZIndex: Int): Annotation? {
        val list = byDocument[documentId] ?: return null
        val index = list.indexOfFirst { it.annotationId == annotationId }
        if (index < 0) return null
        val clamped = Math.max(0, Math.min(newZIndex, list.size - 1))
        val moved = list.removeAt(index)
        list.add(clamped, moved)
        reindex(list)
        return moved
    }

    /** All annotations for the document in z order (copy; mutation-safe). */
    fun annotationsFor(documentId: String): List<Annotation> =
        byDocument[documentId]?.toList() ?: emptyList()

    /** Merges the document's annotations (z order) into one DrawPlan. */
    fun plan(documentId: String): DrawPlan =
        plan(documentId, TextGlyphSource())

    /** plan() with an explicit glyph source (the TextNote renderer). */
    fun plan(documentId: String, glyphSource: TextGlyphSource): DrawPlan =
        annotationsFor(documentId).toDrawPlan(glyphSource)

    val documentCount: Int get() = byDocument.size

    private fun listFor(documentId: String): MutableList<Annotation> =
        byDocument.getOrPut(documentId) { ArrayList() }

    /** Re-assigns zIndex densely 0..n-1 in list order (total order). */
    private fun reindex(list: MutableList<Annotation>) {
        for (index in list.indices) {
            list[index] = withZIndex(list[index], index)
        }
    }

    private fun withZIndex(annotation: Annotation, zIndex: Int): Annotation = when (annotation) {
        is Annotation.Ink -> Annotation.Ink(annotation.strokes, annotation.annotationId, zIndex)
        is Annotation.Highlight ->
            Annotation.Highlight(annotation.rect, annotation.colorArgb, annotation.annotationId, zIndex)
        is Annotation.TextNote ->
            Annotation.TextNote(annotation.text, annotation.anchor, annotation.colorArgb, annotation.annotationId, zIndex)
    }
}
