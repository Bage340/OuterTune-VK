package com.dd3boh.outertune.providers.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationTest {
    @Test
    fun `diff is stable and classifies additions content position and unchanged records`() {
        val local = SyncSnapshot(
            listOf(
                record("same", "a", 0),
                record("content", "local", 1),
                record("position", "p", 2),
                record("local-only", "l", 3),
            )
        )
        val remote = SyncSnapshot(
            listOf(
                record("same", "a", 0),
                record("content", "remote", 1),
                record("position", "p", 9),
                record("remote-only", "r", 4),
            )
        )

        val diff = SyncDiffCalculator.calculate(local, remote)

        assertEquals(
            listOf("content", "local-only", "position", "remote-only", "same"),
            diff.differences.map(SyncDifference::stableId),
        )
        assertEquals(
            SyncDifferenceKind.CONTENT_CONFLICT,
            diff.differences.single { it.stableId == "content" }.kind,
        )
        assertEquals(
            SyncDifferenceKind.POSITION_CONFLICT,
            diff.differences.single { it.stableId == "position" }.kind,
        )
        assertEquals(
            SyncDifferenceKind.LOCAL_ONLY,
            diff.differences.single { it.stableId == "local-only" }.kind,
        )
        assertEquals(
            SyncDifferenceKind.REMOTE_ONLY,
            diff.differences.single { it.stableId == "remote-only" }.kind,
        )
        assertEquals(
            SyncDifferenceKind.UNCHANGED,
            diff.differences.single { it.stableId == "same" }.kind,
        )
    }

    @Test
    fun `ADD_ONLY_MERGE adds missing records but never overwrites or deletes`() {
        val diff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(
                records = listOf(record("local", "a"), record("conflict", "local")),
                isComplete = true,
            ),
            remote = SyncSnapshot(
                records = listOf(record("remote", "b"), record("conflict", "remote")),
                isComplete = false,
            ),
        )

        val plan = ReconciliationPlanner.plan(diff, SyncConflictPolicy.ADD_ONLY_MERGE)

        assertTrue(plan.hasIncompleteSnapshot)
        assertTrue(plan.destructiveActions.isEmpty())
        assertEquals(
            ReconciliationActionType.ADD_TO_REMOTE,
            plan.actions.single { it.stableId == "local" }.type,
        )
        assertEquals(
            ReconciliationActionType.ADD_TO_LOCAL,
            plan.actions.single { it.stableId == "remote" }.type,
        )
        assertEquals(
            ReconciliationActionType.MANUAL_REVIEW,
            plan.actions.single { it.stableId == "conflict" }.type,
        )
    }

    @Test
    fun `content and order conflicts follow local and remote policies`() {
        val diff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(listOf(record("content", "local", 0), record("order", "same", 1))),
            remote = SyncSnapshot(listOf(record("content", "remote", 0), record("order", "same", 2))),
        )

        val remotePlan = ReconciliationPlanner.plan(diff, SyncConflictPolicy.REMOTE_WINS)
        assertEquals(
            ReconciliationActionType.UPDATE_LOCAL,
            remotePlan.actions.single { it.stableId == "content" }.type,
        )
        assertEquals(
            ReconciliationActionType.REORDER_LOCAL,
            remotePlan.actions.single { it.stableId == "order" }.type,
        )

        val localPlan = ReconciliationPlanner.plan(diff, SyncConflictPolicy.LOCAL_WINS)
        assertEquals(
            ReconciliationActionType.UPDATE_REMOTE,
            localPlan.actions.single { it.stableId == "content" }.type,
        )
        assertEquals(
            ReconciliationActionType.REORDER_REMOTE,
            localPlan.actions.single { it.stableId == "order" }.type,
        )
    }

    @Test
    fun `destructive actions require an explicit tombstone`() {
        val noTombstoneDiff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(listOf(record("local", "a"))),
            remote = SyncSnapshot(emptyList(), isComplete = true),
        )
        val noTombstonePlan = ReconciliationPlanner.plan(
            noTombstoneDiff,
            SyncConflictPolicy.REMOTE_WINS,
        )
        assertTrue(noTombstonePlan.destructiveActions.isEmpty())
        assertEquals(ReconciliationActionType.ADD_TO_REMOTE, noTombstonePlan.actions.single().type)

        val tombstoneDiff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(listOf(record("deleted", "old"))),
            remote = SyncSnapshot(listOf(record("deleted", "old", deleted = true))),
        )
        val tombstonePlan = ReconciliationPlanner.plan(
            tombstoneDiff,
            SyncConflictPolicy.REMOTE_WINS,
        )
        assertEquals(1, tombstonePlan.destructiveActions.size)
        assertEquals(ReconciliationActionType.DELETE_LOCAL, tombstonePlan.actions.single().type)
    }

    @Test
    fun `ADD_ONLY_MERGE restores the active side of deletion conflicts`() {
        val diff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(listOf(record("track", "old", deleted = true))),
            remote = SyncSnapshot(listOf(record("track", "current"))),
        )

        val plan = ReconciliationPlanner.plan(diff, SyncConflictPolicy.ADD_ONLY_MERGE)

        assertEquals(ReconciliationActionType.ADD_TO_LOCAL, plan.actions.single().type)
        assertTrue(plan.destructiveActions.isEmpty())
    }

    @Test
    fun `duplicate identities are always routed to manual review`() {
        val diff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(listOf(record("duplicate", "a"), record("duplicate", "b"))),
            remote = SyncSnapshot(listOf(record("duplicate", "remote"))),
        )

        val plan = ReconciliationPlanner.plan(diff, SyncConflictPolicy.LOCAL_WINS)

        assertTrue(diff.hasDuplicates)
        assertEquals(setOf("duplicate"), plan.duplicateIds)
        assertEquals(ReconciliationActionType.MANUAL_REVIEW, plan.actions.single().type)
        assertFalse(plan.actions.single().isDestructive)
    }

    private fun record(
        id: String,
        hash: String,
        position: Int? = null,
        deleted: Boolean = false,
    ) = SyncRecord(
        stableId = id,
        contentHash = hash,
        position = position,
        isDeleted = deleted,
    )
}
