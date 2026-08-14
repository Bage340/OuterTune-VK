package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.ProviderPlaylistItem
import com.dd3boh.outertune.db.entities.ProviderSyncHealth
import com.dd3boh.outertune.db.entities.RemotePlaylistMapping
import com.dd3boh.outertune.db.entities.RemoteTrackMapping
import com.dd3boh.outertune.db.entities.SyncOperation
import com.dd3boh.outertune.db.entities.SyncOperationState
import com.dd3boh.outertune.db.entities.SyncRun
import com.dd3boh.outertune.db.entities.SyncTombstone
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemoteTrack
import java.time.LocalDateTime

class InMemoryProviderSyncStore : ProviderSyncStore {
    private val lock = Any()
    private val tracks = linkedMapOf<String, LocalSyncTrack>()
    private val libraryIds = linkedSetOf<String>()
    private val localPlaylists = linkedMapOf<String, LocalSyncPlaylist>()
    private val trackMappings = linkedMapOf<Pair<String, String>, RemoteTrackMapping>()
    private val playlistMappings = linkedMapOf<Pair<String, String>, RemotePlaylistMapping>()
    private val playlistItems = linkedMapOf<Pair<String, String>, List<ProviderPlaylistItem>>()
    private val tombstones = mutableListOf<SyncTombstone>()
    private val operations = linkedMapOf<String, SyncOperation>()
    private val operationIdByKey = linkedMapOf<String, String>()
    private val runs = linkedMapOf<String, SyncRun>()
    private val health = linkedMapOf<String, ProviderSyncHealth>()

    fun seedTrack(track: LocalSyncTrack, inLibrary: Boolean = true) = synchronized(lock) {
        tracks[track.localSongId] = track
        if (inLibrary) libraryIds += track.localSongId
    }

    fun seedPlaylist(playlist: LocalSyncPlaylist) = synchronized(lock) {
        localPlaylists[playlist.localPlaylistId] = playlist
    }

    fun seedTrackMapping(mapping: RemoteTrackMapping) = synchronized(lock) {
        require(mapping.localSongId in tracks)
        trackMappings[mapping.provider to mapping.remoteTrackId] = mapping
    }

    fun seedPlaylistMapping(mapping: RemotePlaylistMapping) = synchronized(lock) {
        require(mapping.localPlaylistId in localPlaylists)
        playlistMappings[mapping.provider to mapping.remotePlaylistId] = mapping
    }

    fun seedTombstone(tombstone: SyncTombstone) = synchronized(lock) {
        tombstones.removeAll {
            it.provider == tombstone.provider &&
                it.entityType == tombstone.entityType &&
                it.remoteEntityId == tombstone.remoteEntityId
        }
        tombstones += tombstone
    }

    fun libraryIds(): Set<String> = synchronized(lock) { libraryIds.toSet() }
    fun tracksSnapshot(): Map<String, LocalSyncTrack> = synchronized(lock) { tracks.toMap() }
    fun playlistsSnapshot(): Map<String, LocalSyncPlaylist> = synchronized(lock) {
        localPlaylists.toMap()
    }

    fun operationsSnapshot(): List<SyncOperation> = synchronized(lock) {
        operations.values.toList()
    }

    fun trackMappingsSnapshot(): List<RemoteTrackMapping> = synchronized(lock) {
        trackMappings.values.toList()
    }

    fun playlistMappingsSnapshot(): List<RemotePlaylistMapping> = synchronized(lock) {
        playlistMappings.values.toList()
    }

    fun providerItemsSnapshot(): List<ProviderPlaylistItem> = synchronized(lock) {
        playlistItems.values.flatten()
    }

    fun runsSnapshot(): List<SyncRun> = synchronized(lock) { runs.values.toList() }
    fun healthSnapshot(provider: ProviderId): ProviderSyncHealth? = synchronized(lock) {
        health[provider.name]
    }

    override suspend fun libraryTracks(): List<LocalSyncTrack> = synchronized(lock) {
        libraryIds.mapNotNull(tracks::get)
    }

    override suspend fun trackCandidates(): List<LocalSyncTrack> = synchronized(lock) {
        tracks.values.toList()
    }

    override suspend fun playlists(): List<LocalSyncPlaylist> = synchronized(lock) {
        localPlaylists.values.toList()
    }

    override suspend fun trackMappingsByRemoteIds(
        provider: ProviderId,
        remoteTrackIds: List<String>,
    ): List<RemoteTrackMapping> = synchronized(lock) {
        remoteTrackIds.distinct().mapNotNull { trackMappings[provider.name to it] }
    }

    override suspend fun trackMappingsByLocalIds(
        provider: ProviderId,
        localSongIds: List<String>,
    ): List<RemoteTrackMapping> = synchronized(lock) {
        val ids = localSongIds.toSet()
        trackMappings.values.filter { it.provider == provider.name && it.localSongId in ids }
    }

    override suspend fun playlistMappingsByRemoteIds(
        provider: ProviderId,
        remotePlaylistIds: List<String>,
    ): List<RemotePlaylistMapping> = synchronized(lock) {
        remotePlaylistIds.distinct().mapNotNull { playlistMappings[provider.name to it] }
    }

    override suspend fun playlistMappingsByLocalIds(
        provider: ProviderId,
        localPlaylistIds: List<String>,
    ): List<RemotePlaylistMapping> = synchronized(lock) {
        val ids = localPlaylistIds.toSet()
        playlistMappings.values.filter {
            it.provider == provider.name && it.localPlaylistId in ids
        }
    }

    override suspend fun saveTrackMapping(mapping: RemoteTrackMapping) = synchronized(lock) {
        require(mapping.localSongId in tracks)
        trackMappings[mapping.provider to mapping.remoteTrackId] = mapping
    }

    override suspend fun savePlaylistMapping(mapping: RemotePlaylistMapping) = synchronized(lock) {
        require(mapping.localPlaylistId in localPlaylists)
        playlistMappings[mapping.provider to mapping.remotePlaylistId] = mapping
    }

    override suspend fun importRemoteTrack(
        localSongId: String,
        remoteTrack: RemoteTrack,
        mapping: RemoteTrackMapping,
        now: LocalDateTime,
    ) = synchronized(lock) {
        tracks.putIfAbsent(
            localSongId,
            LocalSyncTrack(
                localSongId = localSongId,
                title = remoteTrack.title,
                artists = remoteTrack.artists,
                album = remoteTrack.album,
                durationSeconds = remoteTrack.durationSeconds,
                artworkUrl = remoteTrack.artworkUrl,
            )
        )
        libraryIds += localSongId
        trackMappings[mapping.provider to mapping.remoteTrackId] = mapping
    }

    override suspend fun linkExistingTrack(
        mapping: RemoteTrackMapping,
        now: LocalDateTime,
    ) = synchronized(lock) {
        require(mapping.localSongId in tracks)
        libraryIds += mapping.localSongId
        trackMappings[mapping.provider to mapping.remoteTrackId] = mapping
    }

    override suspend fun overwriteLocalTrackMetadata(
        localSongId: String,
        remoteTrack: RemoteTrack,
        mapping: RemoteTrackMapping,
        now: LocalDateTime,
    ) = synchronized(lock) {
        require(localSongId in tracks)
        tracks[localSongId] = LocalSyncTrack(
            localSongId = localSongId,
            title = remoteTrack.title,
            artists = remoteTrack.artists,
            album = remoteTrack.album,
            durationSeconds = remoteTrack.durationSeconds,
            artworkUrl = remoteTrack.artworkUrl,
        )
        libraryIds += localSongId
        trackMappings[mapping.provider to mapping.remoteTrackId] = mapping
    }

    override suspend fun removeTrackFromLocalLibrary(localSongId: String) = synchronized(lock) {
        libraryIds -= localSongId
    }

    override suspend fun importRemotePlaylist(
        localPlaylistId: String,
        remotePlaylist: RemotePlaylist,
        mapping: RemotePlaylistMapping,
        now: LocalDateTime,
    ) = synchronized(lock) {
        val previous = localPlaylists[localPlaylistId]
        localPlaylists[localPlaylistId] = LocalSyncPlaylist(
            localPlaylistId = localPlaylistId,
            title = remotePlaylist.title,
            artworkUrl = remotePlaylist.artworkUrl,
            items = previous?.items.orEmpty(),
        )
        playlistMappings[mapping.provider to mapping.remotePlaylistId] = mapping
    }

    override suspend fun updateLocalPlaylist(
        localPlaylistId: String,
        remotePlaylist: RemotePlaylist,
    ) = synchronized(lock) {
        val previous = localPlaylists[localPlaylistId] ?: return@synchronized
        localPlaylists[localPlaylistId] = previous.copy(
            title = remotePlaylist.title,
            artworkUrl = remotePlaylist.artworkUrl,
        )
    }

    override suspend fun replaceLocalPlaylistItems(
        localPlaylistId: String,
        orderedLocalSongIds: List<String>,
    ) = synchronized(lock) {
        val previous = requireNotNull(localPlaylists[localPlaylistId])
        require(orderedLocalSongIds.all { it in tracks })
        localPlaylists[localPlaylistId] = previous.copy(
            items = orderedLocalSongIds.mapIndexed { position, id ->
                LocalSyncPlaylistItem(id, position)
            }
        )
    }

    override suspend fun removeLocalPlaylistItem(
        localPlaylistId: String,
        localSongId: String,
    ) = synchronized(lock) {
        val previous = localPlaylists[localPlaylistId] ?: return@synchronized
        localPlaylists[localPlaylistId] = previous.copy(
            items = previous.items
                .filterNot { it.localSongId == localSongId }
                .mapIndexed { position, item -> item.copy(position = position) }
        )
    }

    override suspend fun deleteLocalPlaylist(localPlaylistId: String) = synchronized(lock) {
        val removedRemoteIds = playlistMappings.values
            .filter { it.localPlaylistId == localPlaylistId }
            .map { it.provider to it.remotePlaylistId }
            .toSet()
        localPlaylists.remove(localPlaylistId)
        playlistMappings.entries.removeAll { it.value.localPlaylistId == localPlaylistId }
        playlistItems.keys.removeAll(removedRemoteIds)
        Unit
    }

    override suspend fun replaceProviderPlaylistItems(
        provider: ProviderId,
        remotePlaylistId: String,
        items: List<ProviderPlaylistItem>,
    ) = synchronized(lock) {
        require(items.all {
            it.provider == provider.name && it.remotePlaylistId == remotePlaylistId
        })
        playlistItems[provider.name to remotePlaylistId] = items.toList()
    }

    override suspend fun activeTombstones(
        provider: ProviderId,
        entityType: String,
    ): List<SyncTombstone> = synchronized(lock) {
        tombstones.filter {
            it.provider == provider.name &&
                it.entityType == entityType &&
                it.acknowledgedAt == null
        }
    }

    override suspend fun upsertRun(run: SyncRun) = synchronized(lock) {
        runs[run.id] = run
    }

    override suspend fun upsertHealth(value: ProviderSyncHealth) = synchronized(lock) {
        health[value.provider] = value
    }

    override suspend fun currentHealth(provider: ProviderId): ProviderSyncHealth? =
        synchronized(lock) { health[provider.name] }

    override suspend fun pendingOperationCount(provider: ProviderId): Int = synchronized(lock) {
        operations.values.count {
            it.provider == provider.name && it.state in pendingStates
        }
    }

    override suspend fun enqueueOperation(operation: SyncOperation): SyncOperation = synchronized(lock) {
        val existingId = operationIdByKey[operation.idempotencyKey]
        if (existingId != null) return@synchronized operations.getValue(existingId)
        operations[operation.id] = operation
        operationIdByKey[operation.idempotencyKey] = operation.id
        operation
    }

    override suspend fun claimOperations(
        provider: ProviderId,
        leaseOwner: String,
        now: LocalDateTime,
        leaseExpiresAt: LocalDateTime,
        limit: Int,
    ): List<SyncOperation> = synchronized(lock) {
        val ids = operations.values
            .asSequence()
            .filter { it.provider == provider.name }
            .filter { operation ->
                operation.state in setOf(
                    SyncOperationState.PENDING,
                    SyncOperationState.RETRYABLE_FAILURE,
                ) || (
                    operation.state == SyncOperationState.RUNNING &&
                        operation.leaseExpiresAt?.let { !it.isAfter(now) } == true
                    )
            }
            .filter { it.nextAttemptAt?.let { next -> !next.isAfter(now) } != false }
            .filter { it.leaseExpiresAt?.let { lease -> !lease.isAfter(now) } != false }
            .sortedWith(compareBy<SyncOperation> { it.createdAt }.thenBy { it.id })
            .take(limit)
            .map(SyncOperation::id)
            .toList()
        ids.map { id ->
            operations.getValue(id).copy(
                state = SyncOperationState.RUNNING,
                attemptCount = operations.getValue(id).attemptCount + 1,
                lastAttemptAt = now,
                updatedAt = now,
                leaseOwner = leaseOwner,
                leaseExpiresAt = leaseExpiresAt,
            ).also { operations[id] = it }
        }
    }

    override suspend fun markOperationSucceeded(
        id: String,
        leaseOwner: String,
        completedAt: LocalDateTime,
    ): Boolean = transition(id, leaseOwner) { operation ->
        operation.copy(
            state = SyncOperationState.SUCCEEDED,
            updatedAt = completedAt,
            completedAt = completedAt,
            nextAttemptAt = null,
            lastError = null,
            leaseOwner = null,
            leaseExpiresAt = null,
        )
    }

    override suspend fun markOperationRetryable(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        nextAttemptAt: LocalDateTime,
        error: String,
    ): Boolean = transition(id, leaseOwner) { operation ->
        operation.copy(
            state = SyncOperationState.RETRYABLE_FAILURE,
            updatedAt = failedAt,
            nextAttemptAt = nextAttemptAt,
            lastError = error,
            leaseOwner = null,
            leaseExpiresAt = null,
        )
    }

    override suspend fun markOperationPermanentFailure(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        error: String,
    ): Boolean = transition(id, leaseOwner) { operation ->
        operation.copy(
            state = SyncOperationState.PERMANENT_FAILURE,
            updatedAt = failedAt,
            completedAt = failedAt,
            nextAttemptAt = null,
            lastError = error,
            leaseOwner = null,
            leaseExpiresAt = null,
        )
    }

    private fun transition(
        id: String,
        leaseOwner: String,
        transform: (SyncOperation) -> SyncOperation,
    ): Boolean = synchronized(lock) {
        val operation = operations[id] ?: return@synchronized false
        if (operation.state != SyncOperationState.RUNNING || operation.leaseOwner != leaseOwner) {
            return@synchronized false
        }
        operations[id] = transform(operation)
        true
    }

    private companion object {
        val pendingStates = setOf(
            SyncOperationState.PENDING,
            SyncOperationState.RUNNING,
            SyncOperationState.RETRYABLE_FAILURE,
        )
    }
}
