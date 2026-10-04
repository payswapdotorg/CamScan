package org.payswap.camscan.tools.ui

// CAMSCAN-VERIFY-002 — pure UI state of the library batch-selection
// mode. Long-press enters selection; taps toggle rows; batch actions
// operate on repository ops that exist (delete, share-as-multiple).
// Actions with NO backing repository op are declared here so the
// surface can render them visibly DISABLED with a "pending" label —
// never a dead button. No android imports: JVM-testable.

/** Batch actions that have no repository operation behind them yet. */
object BatchActionCatalog {

    /** Id of the (not yet existing) move-to-folder batch action. */
    const val PENDING_MOVE_TO_FOLDER = "move-to-folder"

    /** Id of the (not yet existing) tagging batch action. */
    const val PENDING_TAG = "tag"

    /** Every pending batch action id, in render order. */
    val PENDING_ACTION_IDS: List<String> = listOf(PENDING_MOVE_TO_FOLDER, PENDING_TAG)
}

/** State machine of the library batch-selection mode. */
class BatchSelectionUi {

    private var selectionMode = false
    private val selected = LinkedHashSet<String>()

    /** True while the surface is in selection mode. */
    fun isSelectionMode(): Boolean = selectionMode

    /** Snapshot of the selected document ids (insertion order). */
    fun selectedIds(): Set<String> = selected.toSet()

    /** Number of currently selected documents. */
    fun selectedCount(): Int = selected.size

    /**
     * Enters selection mode (a long-press). The optional document id is
     * toggled into the selection when given. Returns true when the mode
     * actually changed to selection mode.
     */
    fun enterSelection(initialDocumentId: String? = null): Boolean {
        val wasInMode = selectionMode
        selectionMode = true
        if (initialDocumentId != null && initialDocumentId.isNotEmpty()) {
            selected.add(initialDocumentId)
        }
        return !wasInMode
    }

    /**
     * Exits selection mode and clears the selection. Returns true when the
     * mode actually changed.
     */
    fun exitSelection(): Boolean {
        val wasInMode = selectionMode
        selectionMode = false
        selected.clear()
        return wasInMode
    }

    /**
     * Toggles one document id. Returns true when the id is selected after
     * the call. Outside selection mode this is a no-op returning false.
     */
    fun toggle(documentId: String): Boolean {
        if (!selectionMode) return false
        if (!selected.remove(documentId)) {
            selected.add(documentId)
            return true
        }
        return false
    }

    /** True when the wired delete action can run (at least one selected). */
    fun canDelete(): Boolean = selectionMode && selected.isNotEmpty()

    /** True when the wired share-as-multiple action can run. */
    fun canShare(): Boolean = selectionMode && selected.isNotEmpty()

    /**
     * Prunes ids that no longer exist (documents deleted elsewhere).
     * Returns the number of pruned ids. Never exits selection mode — an
     * empty selection is a legal, honestly-rendered state.
     */
    fun retainAll(validIds: Set<String>): Int {
        val before = selected.size
        selected.retainAll(validIds)
        return before - selected.size
    }
}
