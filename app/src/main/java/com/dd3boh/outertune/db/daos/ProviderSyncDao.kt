package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.ProviderPlaylistItem
import com.dd3boh.outertune.db.entities.ProviderSyncHealth
import com.dd3boh.outertune.db.entities.RemotePlaylistMapping
import com.dd3boh.outertune.db.entities.RemoteTrackMapping
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SyncOperation
import com.dd3boh.outertune.db.entities.SyncRun
import com.dd3boh.outertune.db.entities.SyncTombstone
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

@Dao
interface ProviderSyncDao {

    // region Sync engine snapshots and atomic local mutations

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY id")
    suspend fun providerSyncLibrarySongs(): List<Song>

    @Transaction
    @Query("SELECT * FROM song ORDER BY id")
    suspend fun providerSyncTrackCandidates(): List<Song>

    @Transaction
    @Query("SELECT * FROM song WHERE id = :localSongId LIMIT 1")
    suspend fun providerSyncSong(localSongId: String): Song?

    @Query(
        """
        SELECT * FROM playlist
        WHERE (bookmarkedAt IS NOT NULL OR isLocal = 1)
          AND id NOT IN ('LP_LIKED', 'LP_DOWNLOADED')
        ORDER BY id
        """
    )
    suspend fun providerSyncPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :localPlaylistId LIMIT 1")
    suspend fun providerSyncPlaylist(localPlaylistId: String): PlaylistEntity?

    @Query(
        """
        SELECT * FROM playlist_song_map
        WHERE playlistId IN (:localPlaylistIds)
        ORDER BY playlistId, position, id
        """
    )
    suspend fun providerSyncPlaylistSongMaps(
        localPlaylistIds: List<String>,
    ): List<PlaylistSongMap>

    @Upsert
    suspend fun upsertProviderSyncSong(song: SongEntity)

    @Upsert
    suspend fun upsertProviderSyncArtists(artists: List<ArtistEntity>)

    @Upsert
    suspend fun upsertProviderSyncSongArtistMaps(maps: List<SongArtistMap>)

    @Query("DELETE FROM song_artist_map WHERE songId = :localSongId")
    suspend fun clearProviderSyncSongArtistMaps(localSongId: String): Int

    @Query(
        """
        UPDATE song
        SET inLibrary = COALESCE(inLibrary, :addedAt)
        WHERE id = :localSongId
        """
    )
    suspend fun markProviderSyncSongInLibrary(
        localSongId: String,
        addedAt: LocalDateTime,
    ): Int

    @Query(
        """
        UPDATE song
        SET inLibrary = NULL, liked = 0, likedDate = NULL
        WHERE id = :localSongId
        """
    )
    suspend fun removeProviderSyncSongFromLibrary(localSongId: String): Int

    @Transaction
    suspend fun importProviderSyncTrack(
        song: SongEntity,
        artists: List<ArtistEntity>,
        artistMaps: List<SongArtistMap>,
        mapping: RemoteTrackMapping,
    ) {
        require(song.id == mapping.localSongId)
        require(artistMaps.all { it.songId == song.id })
        upsertProviderSyncSong(song)
        clearProviderSyncSongArtistMaps(song.id)
        if (artists.isNotEmpty()) upsertProviderSyncArtists(artists)
        if (artistMaps.isNotEmpty()) upsertProviderSyncSongArtistMaps(artistMaps)
        upsertRemoteTrackMapping(mapping)
    }

    @Transaction
    suspend fun linkProviderSyncTrack(
        mapping: RemoteTrackMapping,
        addedAt: LocalDateTime,
    ) {
        markProviderSyncSongInLibrary(mapping.localSongId, addedAt)
        upsertRemoteTrackMapping(mapping)
    }

    @Upsert
    suspend fun upsertProviderSyncPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProviderSyncPlaylistSongMaps(maps: List<PlaylistSongMap>)

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :localPlaylistId")
    suspend fun clearProviderSyncPlaylistSongMaps(localPlaylistId: String): Int

    @Transaction
    suspend fun replaceProviderSyncPlaylistSongMaps(
        localPlaylistId: String,
        maps: List<PlaylistSongMap>,
    ) {
        require(maps.all { it.playlistId == localPlaylistId })
        clearProviderSyncPlaylistSongMaps(localPlaylistId)
        if (maps.isNotEmpty()) insertProviderSyncPlaylistSongMaps(maps)
    }

    @Transaction
    suspend fun importProviderSyncPlaylist(
        playlist: PlaylistEntity,
        mapping: RemotePlaylistMapping,
    ) {
        require(playlist.id == mapping.localPlaylistId)
        upsertProviderSyncPlaylist(playlist)
        upsertRemotePlaylistMapping(mapping)
    }

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :localPlaylistId AND songId = :localSongId")
    suspend fun removeProviderSyncPlaylistSong(
        localPlaylistId: String,
        localSongId: String,
    ): Int

    @Query("DELETE FROM playlist WHERE id = :localPlaylistId")
    suspend fun deleteProviderSyncPlaylist(localPlaylistId: String): Int

    @Query(
        """
        SELECT * FROM sync_tombstone
        WHERE provider = :provider
          AND entityType = :entityType
          AND acknowledgedAt IS NULL
        ORDER BY deletedAt, remoteEntityId
        """
    )
    suspend fun activeSyncTombstones(
        provider: String,
        entityType: String,
    ): List<SyncTombstone>

    // endregion

    // region Track mappings

    @Query(
        """
        SELECT * FROM remote_track_mapping
        WHERE provider = :provider AND remoteTrackId = :remoteTrackId
        LIMIT 1
        """
    )
    suspend fun remoteTrackMapping(provider: String, remoteTrackId: String): RemoteTrackMapping?

    @Query(
        """
        SELECT * FROM remote_track_mapping
        WHERE provider = :provider AND remoteTrackId IN (:remoteTrackIds)
        """
    )
    suspend fun remoteTrackMappings(
        provider: String,
        remoteTrackIds: List<String>,
    ): List<RemoteTrackMapping>

    @Query(
        """
        SELECT * FROM remote_track_mapping
        WHERE localSongId IN (:localSongIds)
        ORDER BY localSongId, provider
        """
    )
    suspend fun remoteTrackMappingsForLocalSongs(localSongIds: List<String>): List<RemoteTrackMapping>

    @Query(
        """
        SELECT * FROM remote_track_mapping
        WHERE provider = :provider AND localSongId IN (:localSongIds)
        ORDER BY localSongId
        """
    )
    suspend fun remoteTrackMappingsForProviderAndLocalSongs(
        provider: String,
        localSongIds: List<String>,
    ): List<RemoteTrackMapping>

    @Query(
        """
        SELECT * FROM remote_track_mapping
        WHERE localSongId = :localSongId
        ORDER BY provider
        """
    )
    fun observeRemoteTrackMappings(localSongId: String): Flow<List<RemoteTrackMapping>>

    @Upsert
    suspend fun upsertRemoteTrackMapping(mapping: RemoteTrackMapping)

    @Upsert
    suspend fun upsertRemoteTrackMappings(mappings: List<RemoteTrackMapping>)

    @Query(
        """
        DELETE FROM remote_track_mapping
        WHERE provider = :provider AND remoteTrackId = :remoteTrackId
        """
    )
    suspend fun deleteRemoteTrackMapping(provider: String, remoteTrackId: String): Int

    // endregion

    // region Playlist mappings and provider memberships

    @Query(
        """
        SELECT * FROM remote_playlist_mapping
        WHERE provider = :provider AND remotePlaylistId = :remotePlaylistId
        LIMIT 1
        """
    )
    suspend fun remotePlaylistMapping(
        provider: String,
        remotePlaylistId: String,
    ): RemotePlaylistMapping?

    @Query(
        """
        SELECT * FROM remote_playlist_mapping
        WHERE provider = :provider AND remotePlaylistId IN (:remotePlaylistIds)
        """
    )
    suspend fun remotePlaylistMappings(
        provider: String,
        remotePlaylistIds: List<String>,
    ): List<RemotePlaylistMapping>

    @Query(
        """
        SELECT * FROM remote_playlist_mapping
        WHERE localPlaylistId IN (:localPlaylistIds)
        ORDER BY localPlaylistId, provider
        """
    )
    suspend fun remotePlaylistMappingsForLocalPlaylists(
        localPlaylistIds: List<String>,
    ): List<RemotePlaylistMapping>

    @Query(
        """
        SELECT * FROM remote_playlist_mapping
        WHERE provider = :provider AND localPlaylistId IN (:localPlaylistIds)
        ORDER BY localPlaylistId
        """
    )
    suspend fun remotePlaylistMappingsForProviderAndLocalPlaylists(
        provider: String,
        localPlaylistIds: List<String>,
    ): List<RemotePlaylistMapping>

    @Upsert
    suspend fun upsertRemotePlaylistMapping(mapping: RemotePlaylistMapping)

    @Upsert
    suspend fun upsertRemotePlaylistMappings(mappings: List<RemotePlaylistMapping>)

    @Query(
        """
        DELETE FROM remote_playlist_mapping
        WHERE provider = :provider AND remotePlaylistId = :remotePlaylistId
        """
    )
    suspend fun deleteRemotePlaylistMapping(provider: String, remotePlaylistId: String): Int

    @Query(
        """
        SELECT * FROM provider_playlist_item
        WHERE provider = :provider AND remotePlaylistId = :remotePlaylistId
        ORDER BY position, membershipId
        """
    )
    suspend fun providerPlaylistItems(
        provider: String,
        remotePlaylistId: String,
    ): List<ProviderPlaylistItem>

    @Query(
        """
        SELECT * FROM provider_playlist_item
        WHERE provider = :provider AND remoteTrackId IN (:remoteTrackIds)
        ORDER BY remotePlaylistId, position
        """
    )
    suspend fun providerPlaylistItemsForTracks(
        provider: String,
        remoteTrackIds: List<String>,
    ): List<ProviderPlaylistItem>

    @Upsert
    suspend fun upsertProviderPlaylistItems(items: List<ProviderPlaylistItem>)

    @Query(
        """
        DELETE FROM provider_playlist_item
        WHERE provider = :provider AND remotePlaylistId = :remotePlaylistId
        """
    )
    suspend fun clearProviderPlaylistItems(provider: String, remotePlaylistId: String): Int

    @Query(
        """
        DELETE FROM provider_playlist_item
        WHERE provider = :provider
          AND remotePlaylistId = :remotePlaylistId
          AND membershipId IN (:membershipIds)
        """
    )
    suspend fun deleteProviderPlaylistItems(
        provider: String,
        remotePlaylistId: String,
        membershipIds: List<String>,
    ): Int

    @Transaction
    suspend fun replaceProviderPlaylistItems(
        provider: String,
        remotePlaylistId: String,
        items: List<ProviderPlaylistItem>,
    ) {
        require(items.all { it.provider == provider && it.remotePlaylistId == remotePlaylistId }) {
            "Every playlist item must belong to the replaced provider playlist"
        }
        clearProviderPlaylistItems(provider, remotePlaylistId)
        if (items.isNotEmpty()) upsertProviderPlaylistItems(items)
    }

    // endregion

    // region Persisted operation outbox

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncOperation(operation: SyncOperation): Long

    @Query("SELECT * FROM sync_operation WHERE id = :id LIMIT 1")
    suspend fun syncOperation(id: String): SyncOperation?

    @Query("SELECT * FROM sync_operation WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun syncOperationByIdempotencyKey(idempotencyKey: String): SyncOperation?

    @Transaction
    suspend fun enqueueSyncOperation(operation: SyncOperation): SyncOperation {
        insertSyncOperation(operation)
        return requireNotNull(syncOperationByIdempotencyKey(operation.idempotencyKey))
    }

    @Query(
        """
        SELECT id FROM sync_operation
        WHERE provider = :provider
          AND (
            state IN ('PENDING', 'RETRYABLE_FAILURE')
            OR (state = 'RUNNING' AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt <= :now)
          )
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
          AND (leaseExpiresAt IS NULL OR leaseExpiresAt <= :now)
        ORDER BY createdAt, id
        LIMIT :limit
        """
    )
    suspend fun leaseableSyncOperationIds(
        provider: String,
        now: LocalDateTime,
        limit: Int,
    ): List<String>

    @Query(
        """
        UPDATE sync_operation
        SET state = 'RUNNING',
            attemptCount = attemptCount + 1,
            lastAttemptAt = :now,
            updatedAt = :now,
            leaseOwner = :leaseOwner,
            leaseExpiresAt = :leaseExpiresAt
        WHERE id IN (:ids)
          AND provider = :provider
          AND (
            state IN ('PENDING', 'RETRYABLE_FAILURE')
            OR (state = 'RUNNING' AND leaseExpiresAt IS NOT NULL AND leaseExpiresAt <= :now)
          )
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
          AND (leaseExpiresAt IS NULL OR leaseExpiresAt <= :now)
        """
    )
    suspend fun leaseSyncOperations(
        provider: String,
        ids: List<String>,
        leaseOwner: String,
        now: LocalDateTime,
        leaseExpiresAt: LocalDateTime,
    ): Int

    @Query(
        """
        SELECT * FROM sync_operation
        WHERE id IN (:ids) AND state = 'RUNNING' AND leaseOwner = :leaseOwner
        ORDER BY createdAt, id
        """
    )
    suspend fun leasedSyncOperations(ids: List<String>, leaseOwner: String): List<SyncOperation>

    @Transaction
    suspend fun claimPendingSyncOperations(
        provider: String,
        leaseOwner: String,
        now: LocalDateTime,
        leaseExpiresAt: LocalDateTime,
        limit: Int,
    ): List<SyncOperation> {
        if (limit <= 0) return emptyList()
        val ids = leaseableSyncOperationIds(provider, now, limit)
        if (ids.isEmpty()) return emptyList()
        leaseSyncOperations(provider, ids, leaseOwner, now, leaseExpiresAt)
        return leasedSyncOperations(ids, leaseOwner)
    }

    @Query(
        """
        UPDATE sync_operation
        SET state = 'SUCCEEDED',
            updatedAt = :completedAt,
            completedAt = :completedAt,
            lastError = NULL,
            nextAttemptAt = NULL,
            leaseOwner = NULL,
            leaseExpiresAt = NULL
        WHERE id = :id AND state = 'RUNNING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun markSyncOperationSucceeded(
        id: String,
        leaseOwner: String,
        completedAt: LocalDateTime,
    ): Int

    @Query(
        """
        UPDATE sync_operation
        SET state = 'RETRYABLE_FAILURE',
            updatedAt = :failedAt,
            nextAttemptAt = :nextAttemptAt,
            lastError = :lastError,
            leaseOwner = NULL,
            leaseExpiresAt = NULL
        WHERE id = :id AND state = 'RUNNING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun markSyncOperationRetryableFailure(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        nextAttemptAt: LocalDateTime,
        lastError: String,
    ): Int

    @Query(
        """
        UPDATE sync_operation
        SET state = 'PERMANENT_FAILURE',
            updatedAt = :failedAt,
            completedAt = :failedAt,
            nextAttemptAt = NULL,
            lastError = :lastError,
            leaseOwner = NULL,
            leaseExpiresAt = NULL
        WHERE id = :id AND state = 'RUNNING' AND leaseOwner = :leaseOwner
        """
    )
    suspend fun markSyncOperationPermanentFailure(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        lastError: String,
    ): Int

    @Query(
        """
        SELECT * FROM sync_operation
        WHERE provider = :provider AND state IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILURE')
        ORDER BY createdAt, id
        """
    )
    fun observePendingSyncOperations(provider: String): Flow<List<SyncOperation>>

    @Query(
        """
        SELECT COUNT(*) FROM sync_operation
        WHERE provider = :provider AND state IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILURE')
        """
    )
    fun observePendingSyncOperationCount(provider: String): Flow<Int>

    // endregion

    // region Tombstones

    @Upsert
    suspend fun upsertSyncTombstone(tombstone: SyncTombstone)

    @Upsert
    suspend fun upsertSyncTombstones(tombstones: List<SyncTombstone>)

    @Query(
        """
        SELECT * FROM sync_tombstone
        WHERE provider = :provider
          AND entityType = :entityType
          AND remoteEntityId IN (:remoteEntityIds)
        """
    )
    suspend fun syncTombstones(
        provider: String,
        entityType: String,
        remoteEntityIds: List<String>,
    ): List<SyncTombstone>

    @Query(
        """
        SELECT * FROM sync_tombstone
        WHERE provider = :provider
          AND entityType = :entityType
          AND acknowledgedAt IS NULL
        ORDER BY deletedAt
        LIMIT :limit
        """
    )
    suspend fun unacknowledgedSyncTombstones(
        provider: String,
        entityType: String,
        limit: Int,
    ): List<SyncTombstone>

    @Query(
        """
        UPDATE sync_tombstone
        SET acknowledgedAt = :acknowledgedAt
        WHERE provider = :provider
          AND entityType = :entityType
          AND remoteEntityId IN (:remoteEntityIds)
          AND acknowledgedAt IS NULL
        """
    )
    suspend fun acknowledgeSyncTombstones(
        provider: String,
        entityType: String,
        remoteEntityIds: List<String>,
        acknowledgedAt: LocalDateTime,
    ): Int

    @Query("DELETE FROM sync_tombstone WHERE acknowledgedAt IS NOT NULL AND acknowledgedAt < :before")
    suspend fun pruneAcknowledgedSyncTombstones(before: LocalDateTime): Int

    // endregion

    // region Runs and current provider health

    @Upsert
    suspend fun upsertSyncRun(run: SyncRun)

    @Query(
        """
        SELECT * FROM sync_run
        WHERE provider = :provider
        ORDER BY startedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentSyncRuns(provider: String, limit: Int = 20): Flow<List<SyncRun>>

    @Query(
        """
        UPDATE sync_run
        SET cursor = :cursor,
            pageCount = :pageCount,
            scannedCount = :scannedCount,
            insertedCount = :insertedCount,
            updatedCount = :updatedCount,
            deletedCount = :deletedCount,
            conflictCount = :conflictCount,
            errorCount = :errorCount,
            lastError = :lastError
        WHERE id = :id AND state = 'RUNNING'
        """
    )
    suspend fun updateSyncRunProgress(
        id: String,
        cursor: String?,
        pageCount: Int,
        scannedCount: Int,
        insertedCount: Int,
        updatedCount: Int,
        deletedCount: Int,
        conflictCount: Int,
        errorCount: Int,
        lastError: String?,
    ): Int

    @Query(
        """
        UPDATE sync_run
        SET state = :state,
            health = :health,
            finishedAt = :finishedAt,
            cursor = :cursor,
            pageCount = :pageCount,
            scannedCount = :scannedCount,
            insertedCount = :insertedCount,
            updatedCount = :updatedCount,
            deletedCount = :deletedCount,
            conflictCount = :conflictCount,
            errorCount = :errorCount,
            lastError = :lastError
        WHERE id = :id AND state = 'RUNNING'
        """
    )
    suspend fun finishSyncRun(
        id: String,
        state: String,
        health: String,
        finishedAt: LocalDateTime,
        cursor: String?,
        pageCount: Int,
        scannedCount: Int,
        insertedCount: Int,
        updatedCount: Int,
        deletedCount: Int,
        conflictCount: Int,
        errorCount: Int,
        lastError: String?,
    ): Int

    @Upsert
    suspend fun upsertProviderSyncHealth(health: ProviderSyncHealth)

    @Query("SELECT * FROM provider_sync_health WHERE provider = :provider LIMIT 1")
    fun observeProviderSyncHealth(provider: String): Flow<ProviderSyncHealth?>

    @Query("SELECT * FROM provider_sync_health WHERE provider = :provider LIMIT 1")
    suspend fun providerSyncHealthSnapshot(provider: String): ProviderSyncHealth?

    @Query(
        """
        SELECT COUNT(*) FROM sync_operation
        WHERE provider = :provider AND state IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILURE')
        """
    )
    suspend fun pendingSyncOperationCount(provider: String): Int

    // endregion
}
