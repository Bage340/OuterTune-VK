/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.sync

object SyncDiffCalculator {
    fun calculate(local: SyncSnapshot, remote: SyncSnapshot): SyncDiff {
        val duplicateLocalIds = duplicateIds(local.records)
        val duplicateRemoteIds = duplicateIds(remote.records)
        val localById = local.records.associateBy(SyncRecord::stableId)
        val remoteById = remote.records.associateBy(SyncRecord::stableId)
        val allIds = (localById.keys + remoteById.keys).sorted()

        val differences = allIds.map { stableId ->
            val localRecord = localById[stableId]
            val remoteRecord = remoteById[stableId]
            SyncDifference(
                stableId = stableId,
                kind = classify(localRecord, remoteRecord),
                local = localRecord,
                remote = remoteRecord,
            )
        }
        return SyncDiff(
            differences = differences,
            duplicateLocalIds = duplicateLocalIds,
            duplicateRemoteIds = duplicateRemoteIds,
            localSnapshotComplete = local.isComplete,
            remoteSnapshotComplete = remote.isComplete,
        )
    }

    private fun duplicateIds(records: List<SyncRecord>): Set<String> = records
        .groupingBy(SyncRecord::stableId)
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys

    private fun classify(
        local: SyncRecord?,
        remote: SyncRecord?,
    ): SyncDifferenceKind = when {
        local == null && remote?.isDeleted == true -> SyncDifferenceKind.TOMBSTONE_ONLY
        local == null -> SyncDifferenceKind.REMOTE_ONLY
        remote == null && local.isDeleted -> SyncDifferenceKind.TOMBSTONE_ONLY
        remote == null -> SyncDifferenceKind.LOCAL_ONLY
        local.isDeleted && remote.isDeleted -> SyncDifferenceKind.UNCHANGED
        local.isDeleted != remote.isDeleted -> SyncDifferenceKind.DELETE_CONFLICT
        local.contentHash != remote.contentHash -> SyncDifferenceKind.CONTENT_CONFLICT
        local.position != remote.position -> SyncDifferenceKind.POSITION_CONFLICT
        else -> SyncDifferenceKind.UNCHANGED
    }
}

object ReconciliationPlanner {
    fun plan(diff: SyncDiff, policy: SyncConflictPolicy): ReconciliationPlan {
        val duplicateIds = diff.duplicateLocalIds + diff.duplicateRemoteIds
        val actions = diff.differences.map { difference ->
            if (difference.stableId in duplicateIds) {
                ReconciliationAction(
                    stableId = difference.stableId,
                    type = ReconciliationActionType.MANUAL_REVIEW,
                    reason = ReconciliationReason.DUPLICATE_IDENTITY,
                )
            } else {
                resolve(difference, policy)
            }
        }
        return ReconciliationPlan(
            policy = policy,
            actions = actions,
            hasIncompleteSnapshot = !diff.localSnapshotComplete || !diff.remoteSnapshotComplete,
            duplicateIds = duplicateIds,
        )
    }

    private fun resolve(
        difference: SyncDifference,
        policy: SyncConflictPolicy,
    ): ReconciliationAction = when (difference.kind) {
        SyncDifferenceKind.LOCAL_ONLY -> action(
            difference,
            ReconciliationActionType.ADD_TO_REMOTE,
            ReconciliationReason.LOCAL_ITEM_MISSING_REMOTELY,
        )

        SyncDifferenceKind.REMOTE_ONLY -> action(
            difference,
            ReconciliationActionType.ADD_TO_LOCAL,
            ReconciliationReason.REMOTE_ITEM_MISSING_LOCALLY,
        )

        SyncDifferenceKind.CONTENT_CONFLICT -> resolveContentConflict(difference, policy)
        SyncDifferenceKind.POSITION_CONFLICT -> resolvePositionConflict(difference, policy)
        SyncDifferenceKind.DELETE_CONFLICT -> resolveDeleteConflict(difference, policy)
        SyncDifferenceKind.TOMBSTONE_ONLY,
        SyncDifferenceKind.UNCHANGED,
        -> action(
            difference,
            ReconciliationActionType.NO_OP,
            ReconciliationReason.ALREADY_RECONCILED,
        )
    }

    private fun resolveContentConflict(
        difference: SyncDifference,
        policy: SyncConflictPolicy,
    ): ReconciliationAction = action(
        difference = difference,
        type = when (policy) {
            SyncConflictPolicy.ADD_ONLY_MERGE,
            SyncConflictPolicy.MANUAL,
            -> ReconciliationActionType.MANUAL_REVIEW

            SyncConflictPolicy.REMOTE_WINS -> ReconciliationActionType.UPDATE_LOCAL
            SyncConflictPolicy.LOCAL_WINS -> ReconciliationActionType.UPDATE_REMOTE
        },
        reason = ReconciliationReason.CONTENT_DIFFERS,
    )

    private fun resolvePositionConflict(
        difference: SyncDifference,
        policy: SyncConflictPolicy,
    ): ReconciliationAction = action(
        difference = difference,
        type = when (policy) {
            SyncConflictPolicy.ADD_ONLY_MERGE,
            SyncConflictPolicy.MANUAL,
            -> ReconciliationActionType.MANUAL_REVIEW

            SyncConflictPolicy.REMOTE_WINS -> ReconciliationActionType.REORDER_LOCAL
            SyncConflictPolicy.LOCAL_WINS -> ReconciliationActionType.REORDER_REMOTE
        },
        reason = ReconciliationReason.POSITION_DIFFERS,
    )

    private fun resolveDeleteConflict(
        difference: SyncDifference,
        policy: SyncConflictPolicy,
    ): ReconciliationAction {
        val localDeleted = difference.local?.isDeleted == true
        val remoteDeleted = difference.remote?.isDeleted == true
        val type = when (policy) {
            SyncConflictPolicy.ADD_ONLY_MERGE -> when {
                localDeleted -> ReconciliationActionType.ADD_TO_LOCAL
                remoteDeleted -> ReconciliationActionType.ADD_TO_REMOTE
                else -> ReconciliationActionType.MANUAL_REVIEW
            }

            SyncConflictPolicy.REMOTE_WINS -> when {
                remoteDeleted -> ReconciliationActionType.DELETE_LOCAL
                else -> ReconciliationActionType.ADD_TO_LOCAL
            }

            SyncConflictPolicy.LOCAL_WINS -> when {
                localDeleted -> ReconciliationActionType.DELETE_REMOTE
                else -> ReconciliationActionType.ADD_TO_REMOTE
            }

            SyncConflictPolicy.MANUAL -> ReconciliationActionType.MANUAL_REVIEW
        }
        return action(
            difference = difference,
            type = type,
            reason = ReconciliationReason.EXPLICIT_TOMBSTONE,
        )
    }

    private fun action(
        difference: SyncDifference,
        type: ReconciliationActionType,
        reason: ReconciliationReason,
    ) = ReconciliationAction(
        stableId = difference.stableId,
        type = type,
        reason = reason,
    )
}
