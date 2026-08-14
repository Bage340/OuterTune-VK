package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.providers.MusicProvider
import com.dd3boh.outertune.providers.ProviderCapability
import com.dd3boh.outertune.providers.sync.ReconciliationActionType
import com.dd3boh.outertune.providers.sync.ReconciliationPlanner
import com.dd3boh.outertune.providers.sync.RemoteMutationType
import com.dd3boh.outertune.providers.sync.SyncDiffCalculator
import com.dd3boh.outertune.providers.sync.SyncEntityType
import com.dd3boh.outertune.providers.sync.SyncRecord
import com.dd3boh.outertune.providers.sync.SyncSnapshot

internal class LibrarySyncReconciler(
    private val store: ProviderSyncStore,
    private val identityResolver: TrackIdentityResolver,
    private val operationFactory: SyncOperationFactory,
    private val clock: ProviderSyncClock,
) {
    suspend fun reconcile(
        provider: MusicProvider,
        request: ProviderSyncRequest,
    ): AreaSyncResult {
        val remoteLoad = PaginatedProviderLoader.load(
            provider = provider.id,
            pageSize = request.pageSize,
            maxPages = request.maxPages,
            fetch = provider::getLibraryTracks,
        )
        val diagnostics = mutableListOf<SyncDiagnostic>()
        remoteLoad.error?.let { error ->
            diagnostics += SyncDiagnostic(
                code = "LIBRARY_PAGE_${error.code.name}",
                message = "Library page failed with ${error.code.name}",
                retryable = error.isRetryable,
            )
        }

        val now = clock.now()
        val localLibrary = store.libraryTracks()
        val localCandidates = store.trackCandidates()
        val resolvedRemote = identityResolver.resolve(
            provider = provider.id,
            remoteTracks = remoteLoad.items,
            localCandidates = localCandidates,
            now = now,
            persistSafeMappings = !request.dryRun,
        )
        val localMappings = store.trackMappingsByLocalIds(
            provider.id,
            localLibrary.map(LocalSyncTrack::localSongId),
        )
        val tombstones = store.activeTombstones(provider.id, SyncEntityType.TRACK.name)
        val tombstoneMappings = store.trackMappingsByRemoteIds(
            provider.id,
            tombstones.map { it.remoteEntityId },
        )

        val resolvedByLocal = resolvedRemote.groupBy(ResolvedRemoteTrack::localSongId)
        val remoteToLocal = buildMap {
            resolvedRemote.forEach { put(it.remoteTrack.remoteId, it.localSongId) }
            tombstoneMappings.forEach { put(it.remoteTrackId, it.localSongId) }
        }
        val remoteIdsByLocal = buildMap<String, MutableSet<String>> {
            localMappings.forEach { mapping ->
                getOrPut(mapping.localSongId) { linkedSetOf() } += mapping.remoteTrackId
            }
            resolvedRemote.forEach { resolved ->
                getOrPut(resolved.localSongId) { linkedSetOf() } += resolved.remoteTrack.remoteId
            }
        }

        val localRecords = localLibrary.map { local ->
            SyncRecord(
                stableId = local.localSongId,
                contentHash = SyncFingerprint.localTrack(local),
            )
        }.toMutableList()
        val remoteRecords = resolvedRemote.map { resolved ->
            SyncRecord(
                stableId = resolved.localSongId,
                contentHash = SyncFingerprint.remoteTrack(resolved.remoteTrack),
            )
        }.toMutableList()

        tombstones.forEach { tombstone ->
            val stableId = tombstone.localEntityId
                ?: remoteToLocal[tombstone.remoteEntityId]
                ?: return@forEach
            val deletedRecord = SyncRecord(
                stableId = stableId,
                contentHash = tombstone.payloadHash ?: "deleted",
                isDeleted = true,
            )
            when (tombstone.source.uppercase()) {
                SyncTombstoneSource.LOCAL -> {
                    localRecords.removeAll { it.stableId == stableId }
                    localRecords += deletedRecord
                }

                SyncTombstoneSource.REMOTE -> {
                    remoteRecords.removeAll { it.stableId == stableId }
                    remoteRecords += deletedRecord
                }

                else -> diagnostics += SyncDiagnostic(
                    code = "UNKNOWN_TOMBSTONE_SOURCE",
                    message = "Ignored track tombstone with an unknown source",
                )
            }
        }

        val diff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(localRecords, isComplete = true),
            remote = SyncSnapshot(remoteRecords, isComplete = remoteLoad.isComplete),
        )
        val plan = ReconciliationPlanner.plan(diff, request.conflictPolicy)
        val actions = plan.actions.map { planned ->
            val resolved = resolvedByLocal[planned.stableId]?.singleOrNull()
            val remoteIds = remoteIdsByLocal[planned.stableId].orEmpty()
            var action = ProviderSyncAction(
                area = SyncArea.LIBRARY,
                stableId = planned.stableId,
                localEntityId = planned.stableId,
                remoteEntityId = resolved?.remoteTrack?.remoteId ?: remoteIds.singleOrNull(),
                type = planned.type,
                reason = planned.reason,
                state = SyncSafetyGate.initialState(
                    action = planned,
                    localSnapshotComplete = true,
                    remoteSnapshotComplete = remoteLoad.isComplete,
                    allowDeletions = request.allowDeletions,
                ),
            )
            action = validateRemoteMutation(action, provider, remoteIds)
            if (!request.dryRun && action.state == SyncActionState.PREVIEW) {
                action = execute(
                    action = action,
                    provider = provider,
                    resolved = resolved,
                    remoteIds = remoteIds,
                    now = now,
                )
            }
            action
        }

        return AreaSyncResult(
            actions = actions,
            diagnostics = diagnostics,
            pagesRead = remoteLoad.pagesRead,
            isComplete = remoteLoad.isComplete,
            scannedCount = localLibrary.size + remoteLoad.items.size,
        )
    }

    private fun validateRemoteMutation(
        action: ProviderSyncAction,
        provider: MusicProvider,
        remoteIds: Set<String>,
    ): ProviderSyncAction {
        if (action.state != SyncActionState.PREVIEW) return action
        if (action.type !in remoteMutationActions) return action
        if (!provider.supports(ProviderCapability.LIBRARY_WRITE)) {
            return action.copy(state = SyncActionState.MANUAL_REVIEW)
        }
        if (remoteIds.size != 1) {
            return action.copy(state = SyncActionState.SUPPRESSED_NO_MAPPING)
        }
        return action
    }

    private suspend fun execute(
        action: ProviderSyncAction,
        provider: MusicProvider,
        resolved: ResolvedRemoteTrack?,
        remoteIds: Set<String>,
        now: java.time.LocalDateTime,
    ): ProviderSyncAction = when (action.type) {
        ReconciliationActionType.ADD_TO_LOCAL -> {
            val remote = resolved ?: return action.copy(state = SyncActionState.MANUAL_REVIEW)
            val syncedMapping = remote.mapping.copy(lastSyncedAt = now)
            if (remote.localTrackExists) {
                store.linkExistingTrack(syncedMapping, now)
            } else {
                store.importRemoteTrack(
                    localSongId = remote.localSongId,
                    remoteTrack = remote.remoteTrack,
                    mapping = syncedMapping,
                    now = now,
                )
            }
            action.copy(state = SyncActionState.APPLIED_LOCAL)
        }

        ReconciliationActionType.ADD_TO_REMOTE -> enqueueLibraryMutation(
            action = action,
            provider = provider,
            remoteTrackId = remoteIds.single(),
            type = RemoteMutationType.ADD_TO_LIBRARY,
        )

        ReconciliationActionType.UPDATE_LOCAL -> {
            val remote = resolved ?: return action.copy(state = SyncActionState.MANUAL_REVIEW)
            store.overwriteLocalTrackMetadata(
                localSongId = remote.localSongId,
                remoteTrack = remote.remoteTrack,
                mapping = remote.mapping.copy(lastSyncedAt = now),
                now = now,
            )
            action.copy(state = SyncActionState.APPLIED_LOCAL)
        }

        ReconciliationActionType.UPDATE_REMOTE ->
            action.copy(state = SyncActionState.MANUAL_REVIEW)

        ReconciliationActionType.DELETE_LOCAL -> {
            store.removeTrackFromLocalLibrary(action.localEntityId ?: action.stableId)
            action.copy(state = SyncActionState.APPLIED_LOCAL)
        }

        ReconciliationActionType.DELETE_REMOTE -> enqueueLibraryMutation(
            action = action,
            provider = provider,
            remoteTrackId = remoteIds.single(),
            type = RemoteMutationType.REMOVE_FROM_LIBRARY,
        )

        ReconciliationActionType.REORDER_LOCAL,
        ReconciliationActionType.REORDER_REMOTE,
        ReconciliationActionType.MANUAL_REVIEW,
        -> action.copy(state = SyncActionState.MANUAL_REVIEW)

        ReconciliationActionType.NO_OP -> action.copy(state = SyncActionState.NO_OP)
    }

    private suspend fun enqueueLibraryMutation(
        action: ProviderSyncAction,
        provider: MusicProvider,
        remoteTrackId: String,
        type: RemoteMutationType,
    ): ProviderSyncAction {
        store.enqueueOperation(
            operationFactory.create(
                provider = provider.id,
                operationType = type,
                entityType = SyncEntityType.TRACK,
                localEntityId = action.localEntityId,
                remoteEntityId = remoteTrackId,
            )
        )
        return action.copy(
            remoteEntityId = remoteTrackId,
            state = SyncActionState.ENQUEUED_REMOTE,
        )
    }

    private companion object {
        val remoteMutationActions = setOf(
            ReconciliationActionType.ADD_TO_REMOTE,
            ReconciliationActionType.UPDATE_REMOTE,
            ReconciliationActionType.DELETE_REMOTE,
        )
    }
}
