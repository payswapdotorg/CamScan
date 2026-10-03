package org.payswap.camscan.tools.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.payswap.camscan.tools.FakeTimeSource

// CAMSCAN-PROD-015 §6.6 — RestorePlanner coverage: the full policy truth
// table (KeepExisting / KeepIncoming / NewerWins x add / replace / keep),
// tie conflicts under NewerWins, duplicate-id ambiguity, deterministic
// ordering, manifest derivation, and never-throws behavior.

class RestorePlannerTest {

    private fun incoming(id: String, stamp: Long): RestoreIncomingDocument =
        RestoreIncomingDocument(id, stamp)

    private fun existing(id: String, stamp: Long): RestoreDocumentRef =
        RestoreDocumentRef(id, stamp)

    private fun manifestWithDocs(vararg ids: String, createdAt: Long = 1000L): BackupManifest {
        val entries = ids.map { id ->
            BackupEntry(
                BackupPaths.documentIndexPath(id),
                BackupEntryKind.DOCUMENT_INDEX,
                1L,
                "ab".repeat(32),
            )
        }
        return BackupManifest(1, createdAt, "", entries)
    }

    // ------------------------------------------------ add path

    @Test
    fun unknownIdPlansAddUnderEveryPolicy() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-keep", 10L)))
        val incoming = listOf(incoming("doc-new", 20L))
        for (policy in listOf(
            RestorePolicy.KeepExisting,
            RestorePolicy.KeepIncoming,
            RestorePolicy.NewerWins(FakeTimeSource(0L)),
        )) {
            val plan = RestorePlanner.plan(current, incoming, policy)
            assertTrue(plan is RestorePlan.Planned)
            val decisions = (plan as RestorePlan.Planned).decisions
            assertEquals(1, decisions.size)
            assertTrue(decisions[0] is RestoreDecision.AddDocument)
            assertEquals("doc-new", (decisions[0] as RestoreDecision.AddDocument).documentId)
        }
    }

    // ------------------------------------------------ policy truth table

    @Test
    fun keepExistingPolicyAlwaysKeepsTheIndexedDocument() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 10L)))
        val incomingDocs = listOf(incoming("doc-1", 999L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.KeepExisting)
        val decisions = (plan as RestorePlan.Planned).decisions
        assertTrue(decisions[0] is RestoreDecision.KeepExisting)
    }

    @Test
    fun keepIncomingPolicyAlwaysReplacesTheIndexedDocument() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 999L)))
        val incomingDocs = listOf(incoming("doc-1", 1L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.KeepIncoming)
        val decisions = (plan as RestorePlan.Planned).decisions
        assertTrue(decisions[0] is RestoreDecision.ReplaceDocument)
    }

    @Test
    fun newerWinsReplacesWhenIncomingIsStrictlyNewer() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 100L)))
        val incomingDocs = listOf(incoming("doc-1", 101L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.NewerWins(FakeTimeSource(0L)))
        assertTrue((plan as RestorePlan.Planned).decisions[0] is RestoreDecision.ReplaceDocument)
    }

    @Test
    fun newerWinsKeepsExistingWhenExistingIsStrictlyNewer() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 200L)))
        val incomingDocs = listOf(incoming("doc-1", 199L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.NewerWins(FakeTimeSource(0L)))
        assertTrue((plan as RestorePlan.Planned).decisions[0] is RestoreDecision.KeepExisting)
    }

    @Test
    fun newerWinsTieIsAnUnresolvableConflictStampedByTheClock() {
        val clock = FakeTimeSource(777L)
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 150L)))
        val incomingDocs = listOf(incoming("doc-1", 150L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.NewerWins(clock))
        assertTrue(plan is RestorePlan.Conflicts)
        val conflict = (plan as RestorePlan.Conflicts).conflicts[0]
        assertEquals("doc-1", conflict.documentId)
        assertEquals(150L, conflict.existingLastModifiedMillis)
        assertEquals(150L, conflict.incomingLastModifiedMillis)
        assertEquals(777L, conflict.detectedAtMillis)
    }

    @Test
    fun tieConflictCarriesThePolicyReason() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 150L)))
        val incomingDocs = listOf(incoming("doc-1", 150L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.NewerWins(FakeTimeSource(1L)))
        val conflict = (plan as RestorePlan.Conflicts).conflicts[0]
        assertTrue(conflict.reason.contains("tie"))
    }

    // ------------------------------------------------ mixed documents

    @Test
    fun mixedSnapshotResolvesEachIdIndependently() {
        val current = RestoreIndexSnapshot(
            listOf(existing("doc-a", 10L), existing("doc-b", 10L), existing("doc-c", 100L)),
        )
        val incomingDocs = listOf(
            incoming("doc-a", 5L),
            incoming("doc-b", 50L),
            incoming("doc-new", 1L),
        )
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.NewerWins(FakeTimeSource(0L)))
        val decisions = (plan as RestorePlan.Planned).decisions
        assertEquals(3, decisions.size)
        assertTrue(decisions[0] is RestoreDecision.KeepExisting) // doc-a
        assertTrue(decisions[1] is RestoreDecision.ReplaceDocument) // doc-b
        assertTrue(decisions[2] is RestoreDecision.AddDocument) // doc-new
    }

    @Test
    fun decisionsAreSortedByDocumentIdAscending() {
        val incomingDocs = listOf(
            incoming("doc-z", 1L),
            incoming("doc-a", 1L),
            incoming("doc-m", 1L),
        )
        val plan = RestorePlanner.plan(RestoreIndexSnapshot(emptyList()), incomingDocs, RestorePolicy.KeepExisting)
        val ids = (plan as RestorePlan.Planned).decisions.map { d -> d.documentId }
        assertEquals(listOf("doc-a", "doc-m", "doc-z"), ids)
    }

    @Test
    fun conflictsAreSortedByDocumentIdAscending() {
        val current = RestoreIndexSnapshot(
            listOf(existing("doc-z", 1L), existing("doc-a", 1L)),
        )
        val incomingDocs = listOf(incoming("doc-z", 1L), incoming("doc-a", 1L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.NewerWins(FakeTimeSource(0L)))
        val ids = (plan as RestorePlan.Conflicts).conflicts.map { c -> c.documentId }
        assertEquals(listOf("doc-a", "doc-z"), ids)
    }

    // ------------------------------------------------ duplicates

    @Test
    fun duplicateIncomingIdIsAnAmbiguityConflict() {
        val incomingDocs = listOf(incoming("doc-1", 10L), incoming("doc-1", 20L))
        val plan = RestorePlanner.plan(RestoreIndexSnapshot(emptyList()), incomingDocs, RestorePolicy.KeepExisting)
        assertTrue(plan is RestorePlan.Conflicts)
        val conflict = (plan as RestorePlan.Conflicts).conflicts[0]
        assertEquals("doc-1", conflict.documentId)
        assertTrue(conflict.reason.contains("duplicate incoming"))
    }

    @Test
    fun duplicateCurrentIdTargetedByIncomingIsAnAmbiguityConflict() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 10L), existing("doc-1", 20L)))
        val incomingDocs = listOf(incoming("doc-1", 15L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.KeepExisting)
        assertTrue(plan is RestorePlan.Conflicts)
        assertTrue((plan as RestorePlan.Conflicts).conflicts[0].reason.contains("duplicate current"))
    }

    @Test
    fun duplicateCurrentIdNotTouchedByIncomingDoesNotConflict() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-dup", 10L), existing("doc-dup", 20L)))
        val incomingDocs = listOf(incoming("doc-other", 15L))
        val plan = RestorePlanner.plan(current, incomingDocs, RestorePolicy.KeepExisting)
        assertTrue(plan is RestorePlan.Planned)
        assertEquals(1, (plan as RestorePlan.Planned).decisions.size)
    }

    // ------------------------------------------------ empty cases

    @Test
    fun emptyIncomingYieldsAnEmptyPlan() {
        val plan = RestorePlanner.plan(
            RestoreIndexSnapshot(listOf(existing("doc-1", 1L))),
            emptyList(),
            RestorePolicy.KeepIncoming,
        )
        assertTrue(plan is RestorePlan.Planned)
        assertEquals(0, (plan as RestorePlan.Planned).decisions.size)
    }

    @Test
    fun emptyEverythingYieldsAnEmptyPlan() {
        val plan = RestorePlanner.plan(
            RestoreIndexSnapshot(emptyList()),
            emptyList(),
            RestorePolicy.NewerWins(FakeTimeSource(0L)),
        )
        assertTrue(plan is RestorePlan.Planned)
    }

    // ------------------------------------------------ manifest derivation

    @Test
    fun incomingFromManifestDerivesCanonicalDocumentIds() {
        val manifest = manifestWithDocs("doc-1", "doc-2", createdAt = 4321L)
        val derived = RestorePlanner.incomingFrom(manifest)
        assertEquals(listOf("doc-1", "doc-2"), derived.map { d -> d.documentId })
    }

    @Test
    fun incomingFromManifestUsesBackupCreatedAtAsLastModified() {
        val manifest = manifestWithDocs("doc-1", createdAt = 4321L)
        val derived = RestorePlanner.incomingFrom(manifest)
        assertEquals(4321L, derived[0].lastModifiedMillis)
    }

    @Test
    fun incomingFromManifestIgnoresNonIndexKindsAndNonCanonicalPaths() {
        val entries = listOf(
            BackupEntry("documents/doc-1/index.json", BackupEntryKind.DOCUMENT_INDEX, 1L, "ab".repeat(32)),
            BackupEntry("documents/doc-1/pages/p1.jpg", BackupEntryKind.PAGE_IMAGE, 1L, "ab".repeat(32)),
            BackupEntry("stray.txt", BackupEntryKind.DOCUMENT_INDEX, 1L, "ab".repeat(32)),
            BackupEntry("documents/nested/deep/index.json", BackupEntryKind.DOCUMENT_INDEX, 1L, "ab".repeat(32)),
        )
        val derived = RestorePlanner.incomingFrom(BackupManifest(1, 9L, "", entries))
        assertEquals(listOf("doc-1"), derived.map { d -> d.documentId })
    }

    @Test
    fun planFromManifestCombinesDerivationAndPlanning() {
        val current = RestoreIndexSnapshot(listOf(existing("doc-1", 99999L)))
        val manifest = manifestWithDocs("doc-1", "doc-2", createdAt = 5L)
        val plan = RestorePlanner.planFromManifest(current, manifest, RestorePolicy.NewerWins(FakeTimeSource(0L)))
        val decisions = (plan as RestorePlan.Planned).decisions
        assertEquals(2, decisions.size)
        assertTrue(decisions[0] is RestoreDecision.KeepExisting) // doc-1: existing newer
        assertTrue(decisions[1] is RestoreDecision.AddDocument) // doc-2: unknown
    }

    // ------------------------------------------------ backup paths

    @Test
    fun backupPathsFollowTheDocumentedConventions() {
        assertEquals("documents/doc-9/index.json", BackupPaths.documentIndexPath("doc-9"))
        assertEquals("documents/doc-9/", BackupPaths.documentPrefix("doc-9"))
        assertEquals("doc-9", BackupPaths.documentIdFromIndexPath("documents/doc-9/index.json"))
        assertEquals(null, BackupPaths.documentIdFromIndexPath("documents/doc-9/page.json"))
        assertEquals(null, BackupPaths.documentIdFromIndexPath("other/doc-9/index.json"))
    }
}
