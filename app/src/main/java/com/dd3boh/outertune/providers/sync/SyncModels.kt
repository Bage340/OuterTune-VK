/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.sync

enum class SyncConflictPolicy {
    ADD_ONLY_MERGE,
    REMOTE_WINS,
    LOCAL_WINS,
    MANUAL,
}

/**
 * Provider-independent reconciliation record. [stableId] is a canonical or
 * mapped identity, never a playlist title guessed at execution time.
 */
data class SyncRecord(
    val stableId: String,
    val contentHash: String,
    val position: Int? = null,
    val modifiedAtEpochMillis: Long? = null,
    /** Deletions are actionable only when represented by an explicit tombstone. */
    val isDeleted: Boolean = false,
)

data class SyncSnapshot(
    val records: List<SyncRecord>,
    /** False means absence cannot be interpreted as any remote or local deletion. */
    val isComplete: Boolean = true,
)

enum class SyncDifferenceKind {
    LOCAL_ONLY,
    REMOTE_ONLY,
    CONTENT_CONFLICT,
    POSITION_CONFLICT,
    DELETE_CONFLICT,
    TOMBSTONE_ONLY,
    UNCHANGED,
}

data class SyncDifference(
    val stableId: String,
    val kind: SyncDifferenceKind,
    val local: SyncRecord? = null,
    val remote: SyncRecord? = null,
)

data class SyncDiff(
    val differences: List<SyncDifference>,
    val duplicateLocalIds: Set<String> = emptySet(),
    val duplicateRemoteIds: Set<String> = emptySet(),
    val localSnapshotComplete: Boolean,
    val remoteSnapshotComplete: Boolean,
) {
    val hasDuplicates: Boolean
        get() = duplicateLocalIds.isNotEmpty() || duplicateRemoteIds.isNotEmpty()
}

enum class ReconciliationActionType {
    ADD_TO_LOCAL,
    ADD_TO_REMOTE,
    UPDATE_LOCAL,
    UPDATE_REMOTE,
    DELETE_LOCAL,
    DELETE_REMOTE,
    REORDER_LOCAL,
    REORDER_REMOTE,
    MANUAL_REVIEW,
    NO_OP,
}

enum class ReconciliationReason {
    LOCAL_ITEM_MISSING_REMOTELY,
    REMOTE_ITEM_MISSING_LOCALLY,
    CONTENT_DIFFERS,
    POSITION_DIFFERS,
    EXPLICIT_TOMBSTONE,
    DUPLICATE_IDENTITY,
    ALREADY_RECONCILED,
}

data class ReconciliationAction(
    val stableId: String,
    val type: ReconciliationActionType,
    val reason: ReconciliationReason,
) {
    val isDestructive: Boolean
        get() = type == ReconciliationActionType.DELETE_LOCAL ||
            type == ReconciliationActionType.DELETE_REMOTE
}

data class ReconciliationPlan(
    val policy: SyncConflictPolicy,
    val actions: List<ReconciliationAction>,
    val hasIncompleteSnapshot: Boolean,
    val duplicateIds: Set<String>,
) {
    val destructiveActions: List<ReconciliationAction>
        get() = actions.filter(ReconciliationAction::isDestructive)
}
