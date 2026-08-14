package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.providers.sync.ReconciliationAction
import com.dd3boh.outertune.providers.sync.ReconciliationActionType
import com.dd3boh.outertune.providers.sync.ReconciliationReason

internal object SyncSafetyGate {
    fun initialState(
        action: ReconciliationAction,
        localSnapshotComplete: Boolean,
        remoteSnapshotComplete: Boolean,
        allowDeletions: Boolean,
    ): SyncActionState = when {
        action.type == ReconciliationActionType.NO_OP -> SyncActionState.NO_OP
        action.type == ReconciliationActionType.MANUAL_REVIEW -> SyncActionState.MANUAL_REVIEW
        action.isDestructive && !allowDeletions -> SyncActionState.SUPPRESSED_DELETION_DISABLED
        action.isDestructive && action.reason != ReconciliationReason.EXPLICIT_TOMBSTONE ->
            SyncActionState.SUPPRESSED_NO_TOMBSTONE

        action.isDestructive && (!localSnapshotComplete || !remoteSnapshotComplete) ->
            SyncActionState.SUPPRESSED_INCOMPLETE_SNAPSHOT

        action.type in remoteAbsenceDependentActions && !remoteSnapshotComplete ->
            SyncActionState.SUPPRESSED_INCOMPLETE_SNAPSHOT

        action.type in localAbsenceDependentActions && !localSnapshotComplete ->
            SyncActionState.SUPPRESSED_INCOMPLETE_SNAPSHOT

        action.type in orderDependentActions && (!localSnapshotComplete || !remoteSnapshotComplete) ->
            SyncActionState.SUPPRESSED_INCOMPLETE_SNAPSHOT

        else -> SyncActionState.PREVIEW
    }

    private val remoteAbsenceDependentActions = setOf(
        ReconciliationActionType.ADD_TO_REMOTE,
        ReconciliationActionType.DELETE_REMOTE,
    )
    private val localAbsenceDependentActions = setOf(
        ReconciliationActionType.ADD_TO_LOCAL,
        ReconciliationActionType.DELETE_LOCAL,
    )
    private val orderDependentActions = setOf(
        ReconciliationActionType.REORDER_LOCAL,
        ReconciliationActionType.REORDER_REMOTE,
    )
}

object SyncTombstoneSource {
    const val LOCAL = "LOCAL"
    const val REMOTE = "REMOTE"
}
