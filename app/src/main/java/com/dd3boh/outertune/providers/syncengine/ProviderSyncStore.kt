package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.ProviderPlaylistItem
import com.dd3boh.outertune.db.entities.ProviderSyncHealth
import com.dd3boh.outertune.db.entities.RemotePlaylistMapping
import com.dd3boh.outertune.db.entities.RemoteTrackMapping
import com.dd3boh.outertune.db.entities.SyncOperation
import com.dd3boh.outertune.db.entities.SyncRun
import com.dd3boh.outertune.db.entities.SyncTombstone
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemoteTrack
import java.time.LocalDateTime

data class LocalSyncTrack(
    val localSongId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationSeconds: Int? = null,
    val artworkUrl: String? = null,
)

data class LocalSyncPlaylistItem(
    val localSongId: String,
    val position: Int,
)

data class LocalSyncPlaylist(
    val localPlaylistId: String,
    val title: String,
    val artworkUrl: String? = null,
    val items: List<LocalSyncPlaylistItem> = emptyList(),
)

/**
 * The coordinator only sees this suspend boundary. The Room implementation delegates to DAO
 * transactions; tests use a deterministic in-memory implementation.
 */
interface ProviderSyncStore {
    suspend fun libraryTracks(): List<LocalSyncTrack>
    suspend fun trackCandidates(): List<LocalSyncTrack>
    suspend fun playlists(): List<LocalSyncPlaylist>

    suspend fun trackMappingsByRemoteIds(
        provider: ProviderId,
        remoteTrackIds: List<String>,
    ): List<RemoteTrackMapping>

    suspend fun trackMappingsByLocalIds(
        provider: ProviderId,
        localSongIds: List<String>,
    ): List<RemoteTrackMapping>

    suspend fun playlistMappingsByRemoteIds(
        provider: ProviderId,
        remotePlaylistIds: List<String>,
    ): List<RemotePlaylistMapping>

    suspend fun playlistMappingsByLocalIds(
        provider: ProviderId,
        localPlaylistIds: List<String>,
    ): List<RemotePlaylistMapping>

    suspend fun saveTrackMapping(mapping: RemoteTrackMapping)
    suspend fun savePlaylistMapping(mapping: RemotePlaylistMapping)

    suspend fun importRemoteTrack(
        localSongId: String,
        remoteTrack: RemoteTrack,
        mapping: RemoteTrackMapping,
        now: LocalDateTime,
    )

    suspend fun linkExistingTrack(mapping: RemoteTrackMapping, now: LocalDateTime)
    suspend fun overwriteLocalTrackMetadata(
        localSongId: String,
        remoteTrack: RemoteTrack,
        mapping: RemoteTrackMapping,
        now: LocalDateTime,
    )
    suspend fun removeTrackFromLocalLibrary(localSongId: String)

    suspend fun importRemotePlaylist(
        localPlaylistId: String,
        remotePlaylist: RemotePlaylist,
        mapping: RemotePlaylistMapping,
        now: LocalDateTime,
    )

    suspend fun updateLocalPlaylist(
        localPlaylistId: String,
        remotePlaylist: RemotePlaylist,
    )

    suspend fun replaceLocalPlaylistItems(
        localPlaylistId: String,
        orderedLocalSongIds: List<String>,
    )

    suspend fun removeLocalPlaylistItem(localPlaylistId: String, localSongId: String)
    suspend fun deleteLocalPlaylist(localPlaylistId: String)

    suspend fun replaceProviderPlaylistItems(
        provider: ProviderId,
        remotePlaylistId: String,
        items: List<ProviderPlaylistItem>,
    )

    suspend fun activeTombstones(
        provider: ProviderId,
        entityType: String,
    ): List<SyncTombstone>

    suspend fun upsertRun(run: SyncRun)
    suspend fun upsertHealth(health: ProviderSyncHealth)
    suspend fun currentHealth(provider: ProviderId): ProviderSyncHealth?
    suspend fun pendingOperationCount(provider: ProviderId): Int

    suspend fun enqueueOperation(operation: SyncOperation): SyncOperation
    suspend fun claimOperations(
        provider: ProviderId,
        leaseOwner: String,
        now: LocalDateTime,
        leaseExpiresAt: LocalDateTime,
        limit: Int,
    ): List<SyncOperation>

    suspend fun markOperationSucceeded(
        id: String,
        leaseOwner: String,
        completedAt: LocalDateTime,
    ): Boolean

    suspend fun markOperationRetryable(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        nextAttemptAt: LocalDateTime,
        error: String,
    ): Boolean

    suspend fun markOperationPermanentFailure(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        error: String,
    ): Boolean
}
