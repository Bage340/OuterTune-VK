package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.providers.CapabilityUnavailableReasonCode
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.sync.ReconciliationActionType
import com.dd3boh.outertune.providers.sync.ReconciliationReason
import com.dd3boh.outertune.providers.sync.SyncConflictPolicy

enum class SyncTrigger {
    PREVIEW,
    MANUAL,
    STARTUP,
    RETRY,
}

enum class SyncArea {
    LIBRARY,
    PLAYLIST,
    PLAYLIST_ITEM,
}

data class ProviderSyncRequest(
    val provider: ProviderId,
    val conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.ADD_ONLY_MERGE,
    val dryRun: Boolean = false,
    val allowDeletions: Boolean = false,
    val syncLibrary: Boolean = true,
    val syncPlaylists: Boolean = true,
    val pageSize: Int = 50,
    val maxPages: Int = 200,
    val maxOutboxOperations: Int = 100,
    val trigger: SyncTrigger = SyncTrigger.MANUAL,
) {
    init {
        require(syncLibrary || syncPlaylists) { "At least one sync area must be enabled" }
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxPages > 0) { "maxPages must be positive" }
        require(maxOutboxOperations >= 0) { "maxOutboxOperations must not be negative" }
    }
}

enum class SyncActionState {
    PREVIEW,
    APPLIED_LOCAL,
    ENQUEUED_REMOTE,
    MANUAL_REVIEW,
    SUPPRESSED_INCOMPLETE_SNAPSHOT,
    SUPPRESSED_DELETION_DISABLED,
    SUPPRESSED_NO_TOMBSTONE,
    SUPPRESSED_NO_MAPPING,
    NO_OP,
}

data class ProviderSyncAction(
    val area: SyncArea,
    val localContainerId: String? = null,
    val remoteContainerId: String? = null,
    val stableId: String,
    val localEntityId: String? = null,
    val remoteEntityId: String? = null,
    val type: ReconciliationActionType,
    val reason: ReconciliationReason,
    val state: SyncActionState,
)

data class ProviderSyncCounts(
    val total: Int,
    val addToLocal: Int,
    val addToRemote: Int,
    val updateLocal: Int,
    val updateRemote: Int,
    val deleteLocal: Int,
    val deleteRemote: Int,
    val reorderLocal: Int,
    val reorderRemote: Int,
    val manualReview: Int,
    val noOp: Int,
    val suppressed: Int,
    val appliedLocal: Int,
    val enqueuedRemote: Int,
) {
    companion object {
        fun from(actions: List<ProviderSyncAction>): ProviderSyncCounts = ProviderSyncCounts(
            total = actions.size,
            addToLocal = actions.count { it.type == ReconciliationActionType.ADD_TO_LOCAL },
            addToRemote = actions.count { it.type == ReconciliationActionType.ADD_TO_REMOTE },
            updateLocal = actions.count { it.type == ReconciliationActionType.UPDATE_LOCAL },
            updateRemote = actions.count { it.type == ReconciliationActionType.UPDATE_REMOTE },
            deleteLocal = actions.count { it.type == ReconciliationActionType.DELETE_LOCAL },
            deleteRemote = actions.count { it.type == ReconciliationActionType.DELETE_REMOTE },
            reorderLocal = actions.count { it.type == ReconciliationActionType.REORDER_LOCAL },
            reorderRemote = actions.count { it.type == ReconciliationActionType.REORDER_REMOTE },
            manualReview = actions.count { it.state == SyncActionState.MANUAL_REVIEW },
            noOp = actions.count { it.state == SyncActionState.NO_OP },
            suppressed = actions.count { it.state.name.startsWith("SUPPRESSED_") },
            appliedLocal = actions.count { it.state == SyncActionState.APPLIED_LOCAL },
            enqueuedRemote = actions.count { it.state == SyncActionState.ENQUEUED_REMOTE },
        )
    }
}

data class SyncDiagnostic(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

data class ProviderSyncReport(
    val runId: String,
    val provider: ProviderId,
    val policy: SyncConflictPolicy,
    val dryRun: Boolean,
    val allowDeletions: Boolean,
    val actions: List<ProviderSyncAction>,
    val counts: ProviderSyncCounts,
    val diagnostics: List<SyncDiagnostic>,
    val pagesRead: Int,
    val snapshotsComplete: Boolean,
)

sealed interface ProviderSyncOutcome {
    val report: ProviderSyncReport

    data class Completed(override val report: ProviderSyncReport) : ProviderSyncOutcome

    data class Partial(
        override val report: ProviderSyncReport,
        val retryable: Boolean,
    ) : ProviderSyncOutcome

    data class Unavailable(
        override val report: ProviderSyncReport,
        val reasonCode: CapabilityUnavailableReasonCode,
    ) : ProviderSyncOutcome

    data class Failed(
        override val report: ProviderSyncReport,
        val retryable: Boolean,
    ) : ProviderSyncOutcome
}
