package org.payswap.camscan.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CAMSCAN-VERIFY-002 — JVM tests of the batch-selection state machine:
// long-press entry, toggling, pruning vanished documents, wired-action
// gates, and the pending-action catalog the surface renders disabled.

class BatchSelectionUiTest {

    @Test
    fun longPress_entersSelectionModeWithTheRowPreselected() {
        val ui = BatchSelectionUi()
        assertTrue("first entry changes the mode", ui.enterSelection("doc-a"))
        assertTrue(ui.isSelectionMode())
        assertEquals(setOf("doc-a"), ui.selectedIds())
        assertFalse("re-entering changes nothing", ui.enterSelection("doc-b"))
        assertTrue(ui.isSelectionMode())
    }

    @Test
    fun enterSelection_toleratesMissingInitialId() {
        val ui = BatchSelectionUi()
        assertTrue(ui.enterSelection(null))
        assertTrue(ui.isSelectionMode())
        assertEquals(0, ui.selectedCount())
        assertFalse("re-entering while already selecting changes nothing", ui.enterSelection(""))
        assertEquals(0, ui.selectedCount())
    }

    @Test
    fun toggle_outsideSelectionMode_isANoOp() {
        val ui = BatchSelectionUi()
        assertFalse(ui.toggle("doc-a"))
        assertEquals(0, ui.selectedCount())
        assertFalse(ui.isSelectionMode())
    }

    @Test
    fun toggle_insideSelectionMode_selectsAndDeselects() {
        val ui = BatchSelectionUi()
        ui.enterSelection()
        assertTrue(ui.toggle("doc-a"))
        assertTrue(ui.toggle("doc-b"))
        assertEquals(setOf("doc-a", "doc-b"), ui.selectedIds())
        assertFalse("second toggle deselects", ui.toggle("doc-a"))
        assertEquals(setOf("doc-b"), ui.selectedIds())
    }

    @Test
    fun actionGates_requireSelectionModeAndAtLeastOneSelection() {
        val ui = BatchSelectionUi()
        ui.toggle("doc-a")
        assertFalse("toggles outside the mode do not enable actions", ui.canDelete())
        assertFalse(ui.canShare())
        ui.enterSelection("doc-a")
        assertTrue(ui.canDelete())
        assertTrue(ui.canShare())
        ui.toggle("doc-a")
        assertFalse("an empty selection disables both actions", ui.canDelete())
        assertFalse(ui.canShare())
    }

    @Test
    fun retainAll_prunesVanishedDocumentsAndReportsTheCount() {
        val ui = BatchSelectionUi()
        ui.enterSelection()
        ui.toggle("doc-a")
        ui.toggle("doc-b")
        ui.toggle("doc-c")
        val pruned = ui.retainAll(setOf("doc-b", "doc-c"))
        assertEquals(1, pruned)
        assertEquals(setOf("doc-b", "doc-c"), ui.selectedIds())
        assertEquals(0, ui.retainAll(setOf("doc-b", "doc-c")))
    }

    @Test
    fun retainAll_keepsSelectionModeOnAnEmptySelection() {
        val ui = BatchSelectionUi()
        ui.enterSelection()
        ui.toggle("doc-a")
        ui.retainAll(emptySet())
        assertTrue("an empty selection is a legal rendered state", ui.isSelectionMode())
        assertEquals(0, ui.selectedCount())
    }

    @Test
    fun exitSelection_clearsModeAndSelection_andReportsTheChange() {
        val ui = BatchSelectionUi()
        ui.enterSelection()
        ui.toggle("doc-a")
        assertTrue(ui.exitSelection())
        assertFalse(ui.isSelectionMode())
        assertEquals(0, ui.selectedCount())
        assertFalse("a second exit changes nothing", ui.exitSelection())
    }

    @Test
    fun reentry_afterExit_startsClean() {
        val ui = BatchSelectionUi()
        ui.enterSelection("doc-a")
        ui.toggle("doc-b")
        ui.exitSelection()
        assertTrue(ui.enterSelection("doc-c"))
        assertEquals(setOf("doc-c"), ui.selectedIds())
    }

    @Test
    fun pendingActionCatalog_declaresTheUnimplementedActions() {
        assertEquals(
            listOf(BatchActionCatalog.PENDING_MOVE_TO_FOLDER, BatchActionCatalog.PENDING_TAG),
            BatchActionCatalog.PENDING_ACTION_IDS,
        )
    }
}
