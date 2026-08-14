package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.PlaylistSyncMode
import com.dd3boh.outertune.db.entities.ProviderPlaylistItem
import com.dd3boh.outertune.db.entities.RemotePlaylistMapping
import com.dd3boh.outertune.providers.MusicProvider
import com.dd3boh.outertune.providers.ProviderCapability
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemotePlaylistTrack
import com.dd3boh.outertune.providers.matching.TrackNormalizer
import com.dd3boh.outertune.providers.sync.ReconciliationActionType
import com.dd3boh.outertune.providers.sync.ReconciliationPlanner
import com.dd3boh.outertune.providers.sync.RemoteMutationType
import com.dd3boh.outertune.providers.sync.SyncDiffCalculator
import com.dd3boh.outertune.providers.sync.SyncEntityType
import com.dd3boh.outertune.providers.sync.SyncRecord
import com.dd3boh.outertune.providers.sync.SyncSnapshot
import java.time.LocalDateTime

internal data class ResolvedRemotePlaylist(
    val remotePlaylist: RemotePlaylist,
    val localPlaylistId: String,
    val localPlaylistExists: Boolean,
    val mapping: RemotePlaylistMapping,
)

internal class PlaylistSyncReconciler(
    private val store: ProviderSyncStore,
    private val identityResolver: TrackIdentityResolver,
    private val operationFactory: SyncOperationFactory,
    private val clock: ProviderSyncClock,
) {
    suspend fun reconcile(
        provider: MusicProvider,
        request: ProviderSyncRequest,
    ): AreaSyncResult {
        val diagnostics = mutableListOf<SyncDiagnostic>()
        val playlistLoad = PaginatedProviderLoader.load(
            provider = provider.id,
            pageSize = request.pageSize,
            maxPages = request.maxPages,
            fetch = provider::getPlaylists,
        )
        playlistLoad.error?.let { error ->
            diagnostics += SyncDiagnostic(
                code = "PLAYLIST_PAGE_${error.code.name}",
                message = "Playlist page failed with ${error.code.name}",
                retryable = error.isRetryable,
            )
        }

        val now = clock.now()
        val localPlaylists = store.playlists()
        val localsById = localPlaylists.associateBy(LocalSyncPlaylist::localPlaylistId)
        val resolved = resolvePlaylists(
            provider = provider,
            remotePlaylists = playlistLoad.items,
            localPlaylists = localPlaylists,
            now = now,
            persistSafeMappings = !request.dryRun,
        )
        val mappingsByLocal = store.playlistMappingsByLocalIds(
            provider.id,
            localPlaylists.map(LocalSyncPlaylist::localPlaylistId),
        ).groupBy(RemotePlaylistMapping::localPlaylistId)
        val resolvedByLocal = resolved.groupBy(ResolvedRemotePlaylist::localPlaylistId)
        val remoteIdsByLocal = buildMap<String, MutableSet<String>> {
            mappingsByLocal.forEach { (localId, mappings) ->
                getOrPut(localId) { linkedSetOf() } += mappings.map { it.remotePlaylistId }
            }
            resolved.forEach { playlist ->
                getOrPut(playlist.localPlaylistId) { linkedSetOf() } += playlist.remotePlaylist.remoteId
            }
        }

        val localRecords = localPlaylists.map { playlist ->
            SyncRecord(
                stableId = playlist.localPlaylistId,
                contentHash = SyncFingerprint.localPlaylist(playlist),
            )
        }.toMutableList()
        val remoteRecords = resolved.map { playlist ->
            SyncRecord(
                stableId = playlist.localPlaylistId,
                contentHash = SyncFingerprint.remotePlaylist(playlist.remotePlaylist),
            )
        }.toMutableList()
        applyPlaylistTombstones(
            provider = provider,
            resolved = resolved,
            localRecords = localRecords,
            remoteRecords = remoteRecords,
            diagnostics = diagnostics,
        )

        val diff = SyncDiffCalculator.calculate(
            local = SyncSnapshot(localRecords, isComplete = true),
            remote = SyncSnapshot(remoteRecords, isComplete = playlistLoad.isComplete),
        )
        val plan = ReconciliationPlanner.plan(diff, request.conflictPolicy)
        val topLevelActions = plan.actions.map { planned ->
            val remote = resolvedByLocal[planned.stableId]?.singleOrNull()
            val remoteIds = remoteIdsByLocal[planned.stableId].orEmpty()
            var action = ProviderSyncAction(
                area = SyncArea.PLAYLIST,
                localContainerId = planned.stableId,
                remoteContainerId = remote?.remotePlaylist?.remoteId ?: remoteIds.singleOrNull(),
                stableId = planned.stableId,
                localEntityId = planned.stableId,
                remoteEntityId = remote?.remotePlaylist?.remoteId ?: remoteIds.singleOrNull(),
                type = planned.type,
                reason = planned.reason,
                state = SyncSafetyGate.initialState(
                    action = planned,
                    localSnapshotComplete = true,
                    remoteSnapshotComplete = playlistLoad.isComplete,
                    allowDeletions = request.allowDeletions,
                ),
            )
            action = validateTopLevelRemoteMutation(action, provider, remoteIds)
            if (!request.dryRun && action.state == SyncActionState.PREVIEW) {
                action = executeTopLevel(
                    action = action,
                    provider = provider,
                    remote = remote,
                    local = localsById[planned.stableId],
                    remoteIds = remoteIds,
                    now = now,
                )
            }
            action
        }

        val trackCandidates = store.trackCandidates()
        val itemResults = mutableListOf<AreaSyncResult>()
        val actionByLocalId = topLevelActions.associateBy(ProviderSyncAction::stableId)
        resolved.forEach { playlist ->
            val topAction = actionByLocalId[playlist.localPlaylistId]
            if (topAction?.state == SyncActionState.MANUAL_REVIEW ||
                topAction?.type == ReconciliationActionType.DELETE_LOCAL ||
                topAction?.type == ReconciliationActionType.DELETE_REMOTE
            ) {
                return@forEach
            }
            itemResults += reconcilePlaylistItems(
                provider = provider,
                request = request,
                playlist = playlist,
                localPlaylist = localsById[playlist.localPlaylistId]
                    ?: LocalSyncPlaylist(
                        localPlaylistId = playlist.localPlaylistId,
                        title = playlist.remotePlaylist.title,
                    ),
                trackCandidates = trackCandidates,
                now = now,
            )
        }

        return AreaSyncResult(
            actions = topLevelActions + itemResults.flatMap(AreaSyncResult::actions),
            diagnostics = diagnostics + itemResults.flatMap(AreaSyncResult::diagnostics),
            pagesRead = playlistLoad.pagesRead + itemResults.sumOf(AreaSyncResult::pagesRead),
            isComplete = playlistLoad.isComplete && itemResults.all(AreaSyncResult::isComplete),
            scannedCount = localPlaylists.size + playlistLoad.items.size +
                itemResults.sumOf(AreaSyncResult::scannedCount),
        )
    }

    private suspend fun resolvePlaylists(
        provider: MusicProvider,
        remotePlaylists: List<RemotePlaylist>,
        localPlaylists: List<LocalSyncPlaylist>,
        now: LocalDateTime,
        persistSafeMappings: Boolean,
    ): List<ResolvedRemotePlaylist> {
        val persisted = store.playlistMappingsByRemoteIds(
            provider.id,
            remotePlaylists.map(RemotePlaylist::remoteId),
        ).associateBy(RemotePlaylistMapping::remotePlaylistId)
        val localIds = localPlaylists.map(LocalSyncPlaylist::localPlaylistId).toSet()
        val mappedLocalIds = store.playlistMappingsByLocalIds(provider.id, localIds.toList())
            .map(RemotePlaylistMapping::localPlaylistId)
            .toSet()
        val unmappedLocals = localPlaylists.filterNot { it.localPlaylistId in mappedLocalIds }
        val localTitles = unmappedLocals.groupBy { normalizedTitle(it.title) }
        val remoteTitleCounts = remotePlaylists.groupingBy { normalizedTitle(it.title) }.eachCount()

        return remotePlaylists.map { remote ->
            val existing = persisted[remote.remoteId]
            val uniqueTitleMatch = if (existing == null &&
                remoteTitleCounts[normalizedTitle(remote.title)] == 1
            ) {
                localTitles[normalizedTitle(remote.title)]?.singleOrNull()
            } else {
                null
            }
            val localPlaylistId = existing?.localPlaylistId
                ?: uniqueTitleMatch?.localPlaylistId
                ?: StableSyncIds.localPlaylist(provider.id, remote.remoteId)
            val mapping = existing?.copy(
                remoteRevision = remote.revision,
                lastSeenAt = now,
            ) ?: RemotePlaylistMapping(
                provider = provider.id.name,
                remotePlaylistId = remote.remoteId,
                localPlaylistId = localPlaylistId,
                remoteRevision = remote.revision,
                lastSeenAt = now,
                syncMode = PlaylistSyncMode.ADD_ONLY,
            )
            val localExists = localPlaylistId in localIds
            if (persistSafeMappings && localExists) store.savePlaylistMapping(mapping)
            ResolvedRemotePlaylist(
                remotePlaylist = remote,
                localPlaylistId = localPlaylistId,
                localPlaylistExists = localExists,
                mapping = mapping,
            )
        }
    }

    private suspend fun applyPlaylistTombstones(
        provider: MusicProvider,
        resolved: List<ResolvedRemotePlaylist>,
        localRecords: MutableList<SyncRecord>,
        remoteRecords: MutableList<SyncRecord>,
        diagnostics: MutableList<SyncDiagnostic>,
    ) {
        val tombstones = store.activeTombstones(provider.id, SyncEntityType.PLAYLIST.name)
        val remoteToLocal = resolved.associate { it.remotePlaylist.remoteId to it.localPlaylistId }
        tombstones.forEach { tombstone ->
            val stableId = tombstone.localEntityId
                ?: remoteToLocal[tombstone.remoteEntityId]
                ?: return@forEach
            val deleted = SyncRecord(
                stableId = stableId,
                contentHash = tombstone.payloadHash ?: "deleted",
                isDeleted = true,
            )
            when (tombstone.source.uppercase()) {
                SyncTombstoneSource.LOCAL -> {
                    localRecords.removeAll { it.stableId == stableId }
                    localRecords += deleted
                }

                SyncTombstoneSource.REMOTE -> {
                    remoteRecords.removeAll { it.stableId == stableId }
                    remoteRecords += deleted
                }

                else -> diagnostics += SyncDiagnostic(
                    code = "UNKNOWN_TOMBSTONE_SOURCE",
                    message = "Ignored playlist tombstone with an unknown source",
                )
            }
        }
    }

    private fun validateTopLevelRemoteMutation(
        action: ProviderSyncAction,
        provider: MusicProvider,
        remoteIds: Set<String>,
    ): ProviderSyncAction {
        if (action.state != SyncActionState.PREVIEW || action.type !in topLevelRemoteActions) {
            return action
        }
        if (!provider.supports(ProviderCapability.PLAYLIST_WRITE)) {
            return action.copy(state = SyncActionState.MANUAL_REVIEW)
        }
        if (action.type != ReconciliationActionType.ADD_TO_REMOTE && remoteIds.size != 1) {
            return action.copy(state = SyncActionState.SUPPRESSED_NO_MAPPING)
        }
        return action
    }

    private suspend fun executeTopLevel(
        action: ProviderSyncAction,
        provider: MusicProvider,
        remote: ResolvedRemotePlaylist?,
        local: LocalSyncPlaylist?,
        remoteIds: Set<String>,
        now: LocalDateTime,
    ): ProviderSyncAction = when (action.type) {
        ReconciliationActionType.ADD_TO_LOCAL -> {
            val value = remote ?: return action.copy(state = SyncActionState.MANUAL_REVIEW)
            store.importRemotePlaylist(
                localPlaylistId = value.localPlaylistId,
                remotePlaylist = value.remotePlaylist,
                mapping = value.mapping.copy(lastSyncedAt = now),
                now = now,
            )
            action.copy(state = SyncActionState.APPLIED_LOCAL)
        }

        ReconciliationActionType.ADD_TO_REMOTE -> {
            val value = local ?: return action.copy(state = SyncActionState.MANUAL_REVIEW)
            store.enqueueOperation(
                operationFactory.create(
                    provider = provider.id,
                    operationType = RemoteMutationType.CREATE_PLAYLIST,
                    entityType = SyncEntityType.PLAYLIST,
                    localEntityId = value.localPlaylistId,
                    payload = SyncMutationPayload(title = value.title),
                )
            )
            action.copy(state = SyncActionState.ENQUEUED_REMOTE)
        }

        ReconciliationActionType.UPDATE_LOCAL -> {
            val value = remote ?: return action.copy(state = SyncActionState.MANUAL_REVIEW)
            store.updateLocalPlaylist(value.localPlaylistId, value.remotePlaylist)
            action.copy(state = SyncActionState.APPLIED_LOCAL)
        }

        ReconciliationActionType.UPDATE_REMOTE -> {
            val value = local ?: return action.copy(state = SyncActionState.MANUAL_REVIEW)
            val remoteId = remoteIds.single()
            store.enqueueOperation(
                operationFactory.create(
                    provider = provider.id,
                    operationType = RemoteMutationType.UPDATE_PLAYLIST,
                    entityType = SyncEntityType.PLAYLIST,
                    localEntityId = value.localPlaylistId,
                    remoteEntityId = remoteId,
                    payload = SyncMutationPayload(title = value.title),
                )
            )
            action.copy(remoteEntityId = remoteId, state = SyncActionState.ENQUEUED_REMOTE)
        }

        ReconciliationActionType.DELETE_LOCAL -> {
            store.deleteLocalPlaylist(action.localEntityId ?: action.stableId)
            action.copy(state = SyncActionState.APPLIED_LOCAL)
        }

        ReconciliationActionType.DELETE_REMOTE -> {
            val remoteId = remoteIds.single()
            store.enqueueOperation(
                operationFactory.create(
                    provider = provider.id,
                    operationType = RemoteMutationType.DELETE_PLAYLIST,
                    entityType = SyncEntityType.PLAYLIST,
                    localEntityId = action.localEntityId,
                    remoteEntityId = remoteId,
                )
            )
            action.copy(remoteEntityId = remoteId, state = SyncActionState.ENQUEUED_REMOTE)
        }

        ReconciliationActionType.REORDER_LOCAL,
        ReconciliationActionType.REORDER_REMOTE,
        ReconciliationActionType.MANUAL_REVIEW,
        -> action.copy(state = SyncActionState.MANUAL_REVIEW)

        ReconciliationActionType.NO_OP -> action.copy(state = SyncActionState.NO_OP)
    }

    private suspend fun reconcilePlaylistItems(
        provider: MusicProvider,
        request: ProviderSyncRequest,
        playlist: ResolvedRemotePlaylist,
        localPlaylist: LocalSyncPlaylist,
        trackCandidates: List<LocalSyncTrack>,
        now: LocalDateTime,
    ): AreaSyncResult {
        val load = PaginatedProviderLoader.load(
            provider = provider.id,
            pageSize = request.pageSize,
            maxPages = request.maxPages,
        ) { page -> provider.getPlaylistTracks(playlist.remotePlaylist.remoteId, page) }
        if (!load.isComplete) {
            return AreaSyncResult(
                diagnostics = listOf(
                    SyncDiagnostic(
                        code = "PLAYLIST_TRACK_PAGE_${load.error?.code?.name ?: "INCOMPLETE"}",
                        message = load.error?.code?.let { "Playlist track page failed with ${it.name}" }
                            ?: "Playlist track snapshot is incomplete",
                        retryable = load.error?.isRetryable == true,
                    )
                ),
                pagesRead = load.pagesRead,
                isComplete = false,
                scannedCount = localPlaylist.items.size + load.items.size,
            )
        }

        val resolvedTracks = identityResolver.resolve(
            provider = provider.id,
            remoteTracks = load.items.map(RemotePlaylistTrack::track),
            localCandidates = trackCandidates,
            now = now,
            persistSafeMappings = !request.dryRun,
        )
        val resolvedByLocal = resolvedTracks.groupBy(ResolvedRemoteTrack::localSongId)
        val localItemMappings = store.trackMappingsByLocalIds(
            provider.id,
            localPlaylist.items.map(LocalSyncPlaylistItem::localSongId),
        )
        val remoteIdsByLocal = buildMap<String, MutableSet<String>> {
            localItemMappings.forEach { mapping ->
                getOrPut(mapping.localSongId) { linkedSetOf() } += mapping.remoteTrackId
            }
            resolvedTracks.forEach { resolved ->
                getOrPut(resolved.localSongId) { linkedSetOf() } += resolved.remoteTrack.remoteId
            }
        }
        val candidatesById = trackCandidates.associateBy(LocalSyncTrack::localSongId)
        val localRecords = localPlaylist.items.map { item ->
            SyncRecord(
                stableId = item.localSongId,
                contentHash = candidatesById[item.localSongId]
                    ?.let(SyncFingerprint::localTrack)
                    ?: "missing:${item.localSongId}",
                position = item.position,
            )
        }.toMutableList()
        val remoteRecords = load.items.zip(resolvedTracks).map { (item, resolved) ->
            SyncRecord(
                stableId = resolved.localSongId,
                contentHash = SyncFingerprint.remoteTrack(item.track),
                position = item.position,
            )
        }.toMutableList()
        val diagnostics = mutableListOf<SyncDiagnostic>()
        applyPlaylistItemTombstones(
            provider = provider,
            remotePlaylistId = playlist.remotePlaylist.remoteId,
            localRecords = localRecords,
            remoteRecords = remoteRecords,
            diagnostics = diagnostics,
        )

        val plan = ReconciliationPlanner.plan(
            SyncDiffCalculator.calculate(
                local = SyncSnapshot(localRecords, isComplete = true),
                remote = SyncSnapshot(remoteRecords, isComplete = true),
            ),
            request.conflictPolicy,
        )
        var orderedLocalIds = localPlaylist.items
            .sortedBy(LocalSyncPlaylistItem::position)
            .map(LocalSyncPlaylistItem::localSongId)
            .toMutableList()
        val remoteOrder = load.items.zip(resolvedTracks)
            .sortedBy { (item, _) -> item.position }
            .map { (_, resolved) -> resolved.localSongId }
        val desiredRemoteTrackOrder = localPlaylist.items
            .sortedBy(LocalSyncPlaylistItem::position)
            .mapNotNull { item -> remoteIdsByLocal[item.localSongId]?.singleOrNull() }
        var localOrderChanged = false

        val actions = plan.actions.map { planned ->
            val remote = resolvedByLocal[planned.stableId]?.singleOrNull()
            val remoteIds = remoteIdsByLocal[planned.stableId].orEmpty()
            var action = ProviderSyncAction(
                area = SyncArea.PLAYLIST_ITEM,
                localContainerId = playlist.localPlaylistId,
                remoteContainerId = playlist.remotePlaylist.remoteId,
                stableId = planned.stableId,
                localEntityId = planned.stableId,
                remoteEntityId = remoteIds.singleOrNull(),
                type = planned.type,
                reason = planned.reason,
                state = SyncSafetyGate.initialState(
                    action = planned,
                    localSnapshotComplete = true,
                    remoteSnapshotComplete = true,
                    allowDeletions = request.allowDeletions,
                ),
            )
            action = validateItemRemoteMutation(
                action,
                provider,
                remoteIds,
                desiredRemoteTrackOrder,
                localPlaylist.items.size,
            )
            if (!request.dryRun && action.state == SyncActionState.PREVIEW) {
                when (action.type) {
                    ReconciliationActionType.ADD_TO_LOCAL -> {
                        val value = remote
                        if (value == null) {
                            action = action.copy(state = SyncActionState.MANUAL_REVIEW)
                        } else {
                            val mapping = value.mapping.copy(lastSyncedAt = now)
                            if (value.localTrackExists) {
                                store.linkExistingTrack(mapping, now)
                            } else {
                                store.importRemoteTrack(
                                    value.localSongId,
                                    value.remoteTrack,
                                    mapping,
                                    now,
                                )
                            }
                            if (value.localSongId !in orderedLocalIds) {
                                val position = load.items
                                    .firstOrNull { it.track.remoteId == value.remoteTrack.remoteId }
                                    ?.position
                                    ?.coerceIn(0, orderedLocalIds.size)
                                    ?: orderedLocalIds.size
                                orderedLocalIds.add(position, value.localSongId)
                                localOrderChanged = true
                            }
                            action = action.copy(state = SyncActionState.APPLIED_LOCAL)
                        }
                    }

                    ReconciliationActionType.ADD_TO_REMOTE -> {
                        action = enqueuePlaylistItemMutation(
                            action,
                            provider,
                            playlist,
                            remoteIds.single(),
                            RemoteMutationType.ADD_TRACK_TO_PLAYLIST,
                            position = localPlaylist.items
                                .firstOrNull { it.localSongId == planned.stableId }
                                ?.position,
                        )
                    }

                    ReconciliationActionType.UPDATE_LOCAL -> {
                        val value = remote
                        if (value == null) {
                            action = action.copy(state = SyncActionState.MANUAL_REVIEW)
                        } else {
                            store.overwriteLocalTrackMetadata(
                                value.localSongId,
                                value.remoteTrack,
                                value.mapping.copy(lastSyncedAt = now),
                                now,
                            )
                            action = action.copy(state = SyncActionState.APPLIED_LOCAL)
                        }
                    }

                    ReconciliationActionType.UPDATE_REMOTE ->
                        action = action.copy(state = SyncActionState.MANUAL_REVIEW)

                    ReconciliationActionType.DELETE_LOCAL -> {
                        orderedLocalIds.removeAll { it == planned.stableId }
                        localOrderChanged = true
                        action = action.copy(state = SyncActionState.APPLIED_LOCAL)
                    }

                    ReconciliationActionType.DELETE_REMOTE -> {
                        action = enqueuePlaylistItemMutation(
                            action,
                            provider,
                            playlist,
                            remoteIds.single(),
                            RemoteMutationType.REMOVE_TRACK_FROM_PLAYLIST,
                        )
                    }

                    ReconciliationActionType.REORDER_LOCAL -> {
                        orderedLocalIds = remoteOrder.toMutableList()
                        localOrderChanged = true
                        action = action.copy(state = SyncActionState.APPLIED_LOCAL)
                    }

                    ReconciliationActionType.REORDER_REMOTE -> {
                        store.enqueueOperation(
                            operationFactory.create(
                                provider = provider.id,
                                operationType = RemoteMutationType.REORDER_PLAYLIST,
                                entityType = SyncEntityType.PLAYLIST_TRACK,
                                localEntityId = playlist.localPlaylistId,
                                remoteEntityId = playlist.remotePlaylist.remoteId,
                                payload = SyncMutationPayload(
                                    orderedTrackIds = desiredRemoteTrackOrder,
                                ),
                            )
                        )
                        action = action.copy(state = SyncActionState.ENQUEUED_REMOTE)
                    }

                    ReconciliationActionType.MANUAL_REVIEW ->
                        action = action.copy(state = SyncActionState.MANUAL_REVIEW)

                    ReconciliationActionType.NO_OP ->
                        action = action.copy(state = SyncActionState.NO_OP)
                }
            }
            action
        }

        if (!request.dryRun && localOrderChanged) {
            store.replaceLocalPlaylistItems(playlist.localPlaylistId, orderedLocalIds)
        }
        val importedLocalIds = actions
            .filter {
                it.type == ReconciliationActionType.ADD_TO_LOCAL &&
                    it.state == SyncActionState.APPLIED_LOCAL
            }
            .map(ProviderSyncAction::stableId)
            .toSet()
        val allMembershipTracksExist = resolvedTracks.all { resolved ->
            resolved.localTrackExists || resolved.localSongId in importedLocalIds
        }
        if (!request.dryRun && allMembershipTracksExist) {
            val persistedItems = load.items.zip(resolvedTracks).map { (item, resolved) ->
                ProviderPlaylistItem(
                    provider = provider.id.name,
                    remotePlaylistId = playlist.remotePlaylist.remoteId,
                    membershipId = item.track.metadata["membershipId"]
                        ?.takeIf(String::isNotBlank)
                        ?: StableSyncIds.fallbackMembership(
                            provider.id,
                            playlist.remotePlaylist.remoteId,
                            item.track.remoteId,
                            item.position,
                        ),
                    remoteTrackId = item.track.remoteId,
                    localSongId = resolved.localSongId,
                    position = item.position,
                    lastSeenAt = now,
                    metadataHash = SyncFingerprint.remoteTrack(item.track),
                )
            }
            store.replaceProviderPlaylistItems(
                provider.id,
                playlist.remotePlaylist.remoteId,
                persistedItems,
            )
        } else if (!request.dryRun && !allMembershipTracksExist) {
            diagnostics += SyncDiagnostic(
                code = "PLAYLIST_MEMBERSHIP_NOT_PERSISTED",
                message = "Playlist membership contains unresolved duplicate or manual-review tracks",
            )
        }

        return AreaSyncResult(
            actions = actions,
            diagnostics = diagnostics,
            pagesRead = load.pagesRead,
            isComplete = true,
            scannedCount = localPlaylist.items.size + load.items.size,
        )
    }

    private suspend fun applyPlaylistItemTombstones(
        provider: MusicProvider,
        remotePlaylistId: String,
        localRecords: MutableList<SyncRecord>,
        remoteRecords: MutableList<SyncRecord>,
        diagnostics: MutableList<SyncDiagnostic>,
    ) {
        store.activeTombstones(provider.id, SyncEntityType.PLAYLIST_TRACK.name)
            .filter { StableSyncIds.belongsToPlaylist(it.remoteEntityId, remotePlaylistId) }
            .forEach { tombstone ->
                val stableId = tombstone.localEntityId
                if (stableId == null) {
                    diagnostics += SyncDiagnostic(
                        code = "PLAYLIST_TOMBSTONE_WITHOUT_LOCAL_ID",
                        message = "Ignored membership tombstone without localEntityId",
                    )
                    return@forEach
                }
                val deleted = SyncRecord(
                    stableId = stableId,
                    contentHash = tombstone.payloadHash ?: "deleted",
                    isDeleted = true,
                )
                when (tombstone.source.uppercase()) {
                    SyncTombstoneSource.LOCAL -> {
                        localRecords.removeAll { it.stableId == stableId }
                        localRecords += deleted
                    }

                    SyncTombstoneSource.REMOTE -> {
                        remoteRecords.removeAll { it.stableId == stableId }
                        remoteRecords += deleted
                    }

                    else -> diagnostics += SyncDiagnostic(
                        code = "UNKNOWN_TOMBSTONE_SOURCE",
                        message = "Ignored playlist item tombstone with an unknown source",
                    )
                }
            }
    }

    private fun validateItemRemoteMutation(
        action: ProviderSyncAction,
        provider: MusicProvider,
        remoteIds: Set<String>,
        desiredRemoteTrackOrder: List<String>,
        localItemCount: Int,
    ): ProviderSyncAction {
        if (action.state != SyncActionState.PREVIEW || action.type !in itemRemoteActions) {
            return action
        }
        val capability = if (action.type == ReconciliationActionType.REORDER_REMOTE) {
            ProviderCapability.PLAYLIST_ORDER_WRITE
        } else {
            ProviderCapability.PLAYLIST_WRITE
        }
        if (!provider.supports(capability)) {
            return action.copy(state = SyncActionState.MANUAL_REVIEW)
        }
        if (action.type != ReconciliationActionType.REORDER_REMOTE && remoteIds.size != 1) {
            return action.copy(state = SyncActionState.SUPPRESSED_NO_MAPPING)
        }
        if (action.type == ReconciliationActionType.REORDER_REMOTE &&
            (desiredRemoteTrackOrder.size != localItemCount ||
                desiredRemoteTrackOrder.size != desiredRemoteTrackOrder.distinct().size)
        ) {
            return action.copy(state = SyncActionState.MANUAL_REVIEW)
        }
        return action
    }

    private suspend fun enqueuePlaylistItemMutation(
        action: ProviderSyncAction,
        provider: MusicProvider,
        playlist: ResolvedRemotePlaylist,
        remoteTrackId: String,
        type: RemoteMutationType,
        position: Int? = null,
    ): ProviderSyncAction {
        store.enqueueOperation(
            operationFactory.create(
                provider = provider.id,
                operationType = type,
                entityType = SyncEntityType.PLAYLIST_TRACK,
                localEntityId = StableSyncIds.playlistItemOperationKey(
                    playlist.localPlaylistId,
                    action.localEntityId.orEmpty(),
                ),
                remoteEntityId = playlist.remotePlaylist.remoteId,
                payload = SyncMutationPayload(
                    trackId = remoteTrackId,
                    position = position,
                ),
            )
        )
        return action.copy(
            remoteEntityId = remoteTrackId,
            state = SyncActionState.ENQUEUED_REMOTE,
        )
    }

    private fun normalizedTitle(value: String): String =
        TrackNormalizer.normalizePlainText(value)

    private companion object {
        val topLevelRemoteActions = setOf(
            ReconciliationActionType.ADD_TO_REMOTE,
            ReconciliationActionType.UPDATE_REMOTE,
            ReconciliationActionType.DELETE_REMOTE,
        )
        val itemRemoteActions = setOf(
            ReconciliationActionType.ADD_TO_REMOTE,
            ReconciliationActionType.UPDATE_REMOTE,
            ReconciliationActionType.DELETE_REMOTE,
            ReconciliationActionType.REORDER_REMOTE,
        )
    }
}
