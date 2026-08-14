package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.daos.ProviderSyncDao
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.ProviderPlaylistItem
import com.dd3boh.outertune.db.entities.ProviderSyncHealth
import com.dd3boh.outertune.db.entities.RemotePlaylistMapping
import com.dd3boh.outertune.db.entities.RemoteTrackMapping
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SyncOperation
import com.dd3boh.outertune.db.entities.SyncRun
import com.dd3boh.outertune.db.entities.SyncTombstone
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemoteTrack
import java.time.LocalDateTime

class RoomProviderSyncStore(
    private val dao: ProviderSyncDao,
) : ProviderSyncStore {
    override suspend fun libraryTracks(): List<LocalSyncTrack> =
        dao.providerSyncLibrarySongs().map { song ->
            LocalSyncTrack(
                localSongId = song.song.id,
                title = song.song.title,
                artists = song.artists.map(ArtistEntity::name),
                album = song.album?.title ?: song.song.albumName,
                durationSeconds = song.song.duration.takeIf { it >= 0 },
                artworkUrl = song.song.thumbnailUrl,
            )
        }

    override suspend fun trackCandidates(): List<LocalSyncTrack> =
        dao.providerSyncTrackCandidates().map { song ->
            LocalSyncTrack(
                localSongId = song.song.id,
                title = song.song.title,
                artists = song.artists.map(ArtistEntity::name),
                album = song.album?.title ?: song.song.albumName,
                durationSeconds = song.song.duration.takeIf { it >= 0 },
                artworkUrl = song.song.thumbnailUrl,
            )
        }

    override suspend fun playlists(): List<LocalSyncPlaylist> {
        val playlists = dao.providerSyncPlaylists()
        if (playlists.isEmpty()) return emptyList()
        val maps = dao.providerSyncPlaylistSongMaps(playlists.map(PlaylistEntity::id))
            .groupBy(PlaylistSongMap::playlistId)
        return playlists.map { playlist ->
            LocalSyncPlaylist(
                localPlaylistId = playlist.id,
                title = playlist.name,
                artworkUrl = playlist.thumbnailUrl,
                items = maps[playlist.id].orEmpty().map { map ->
                    LocalSyncPlaylistItem(
                        localSongId = map.songId,
                        position = map.position,
                    )
                },
            )
        }
    }

    override suspend fun trackMappingsByRemoteIds(
        provider: ProviderId,
        remoteTrackIds: List<String>,
    ): List<RemoteTrackMapping> = if (remoteTrackIds.isEmpty()) {
        emptyList()
    } else {
        dao.remoteTrackMappings(provider.name, remoteTrackIds.distinct())
    }

    override suspend fun trackMappingsByLocalIds(
        provider: ProviderId,
        localSongIds: List<String>,
    ): List<RemoteTrackMapping> = if (localSongIds.isEmpty()) {
        emptyList()
    } else {
        dao.remoteTrackMappingsForProviderAndLocalSongs(provider.name, localSongIds.distinct())
    }

    override suspend fun playlistMappingsByRemoteIds(
        provider: ProviderId,
        remotePlaylistIds: List<String>,
    ): List<RemotePlaylistMapping> = if (remotePlaylistIds.isEmpty()) {
        emptyList()
    } else {
        dao.remotePlaylistMappings(provider.name, remotePlaylistIds.distinct())
    }

    override suspend fun playlistMappingsByLocalIds(
        provider: ProviderId,
        localPlaylistIds: List<String>,
    ): List<RemotePlaylistMapping> = if (localPlaylistIds.isEmpty()) {
        emptyList()
    } else {
        dao.remotePlaylistMappingsForProviderAndLocalPlaylists(
            provider.name,
            localPlaylistIds.distinct(),
        )
    }

    override suspend fun saveTrackMapping(mapping: RemoteTrackMapping) {
        dao.upsertRemoteTrackMapping(mapping)
    }

    override suspend fun savePlaylistMapping(mapping: RemotePlaylistMapping) {
        dao.upsertRemotePlaylistMapping(mapping)
    }

    override suspend fun importRemoteTrack(
        localSongId: String,
        remoteTrack: RemoteTrack,
        mapping: RemoteTrackMapping,
        now: LocalDateTime,
    ) {
        val existing = dao.providerSyncSong(localSongId)?.song
        if (existing != null) {
            dao.linkProviderSyncTrack(mapping, now)
            return
        }
        val song = SongEntity(
            id = localSongId,
            title = remoteTrack.title,
            duration = remoteTrack.durationSeconds ?: -1,
            thumbnailUrl = remoteTrack.artworkUrl,
            inLibrary = now,
            isLocal = false,
            localPath = null,
            albumName = remoteTrack.album,
        )
        val artists = remoteTrack.artists.distinct().map { name ->
            ArtistEntity(
                id = StableSyncIds.artist(remoteTrack.provider, name),
                name = name,
                lastUpdateTime = now,
                isLocal = false,
            )
        }
        val artistMaps = artists.mapIndexed { position, artist ->
            SongArtistMap(
                songId = localSongId,
                artistId = artist.id,
                position = position,
            )
        }
        dao.importProviderSyncTrack(song, artists, artistMaps, mapping)
    }

    override suspend fun linkExistingTrack(mapping: RemoteTrackMapping, now: LocalDateTime) {
        dao.linkProviderSyncTrack(mapping, now)
    }

    override suspend fun overwriteLocalTrackMetadata(
        localSongId: String,
        remoteTrack: RemoteTrack,
        mapping: RemoteTrackMapping,
        now: LocalDateTime,
    ) {
        val existing = dao.providerSyncSong(localSongId)?.song ?: return
        val song = existing.copy(
            title = remoteTrack.title,
            duration = remoteTrack.durationSeconds ?: existing.duration,
            thumbnailUrl = remoteTrack.artworkUrl ?: existing.thumbnailUrl,
            inLibrary = existing.inLibrary ?: now,
            albumName = remoteTrack.album ?: existing.albumName,
        )
        val artists = remoteTrack.artists.distinct().map { name ->
            ArtistEntity(
                id = StableSyncIds.artist(remoteTrack.provider, name),
                name = name,
                lastUpdateTime = now,
                isLocal = false,
            )
        }
        val artistMaps = artists.mapIndexed { position, artist ->
            SongArtistMap(
                songId = localSongId,
                artistId = artist.id,
                position = position,
            )
        }
        dao.importProviderSyncTrack(song, artists, artistMaps, mapping)
    }

    override suspend fun removeTrackFromLocalLibrary(localSongId: String) {
        dao.removeProviderSyncSongFromLibrary(localSongId)
    }

    override suspend fun importRemotePlaylist(
        localPlaylistId: String,
        remotePlaylist: RemotePlaylist,
        mapping: RemotePlaylistMapping,
        now: LocalDateTime,
    ) {
        val existing = dao.providerSyncPlaylist(localPlaylistId)
        val entity = existing?.copy(
            name = remotePlaylist.title,
            thumbnailUrl = remotePlaylist.artworkUrl,
            remoteSongCount = remotePlaylist.trackCount,
            bookmarkedAt = existing.bookmarkedAt ?: now,
        ) ?: PlaylistEntity(
            id = localPlaylistId,
            name = remotePlaylist.title,
            browseId = null,
            isEditable = true,
            bookmarkedAt = now,
            thumbnailUrl = remotePlaylist.artworkUrl,
            remoteSongCount = remotePlaylist.trackCount,
            isLocal = false,
        )
        dao.importProviderSyncPlaylist(entity, mapping)
    }

    override suspend fun updateLocalPlaylist(
        localPlaylistId: String,
        remotePlaylist: RemotePlaylist,
    ) {
        val existing = dao.providerSyncPlaylist(localPlaylistId) ?: return
        dao.upsertProviderSyncPlaylist(
            existing.copy(
                name = remotePlaylist.title,
                thumbnailUrl = remotePlaylist.artworkUrl,
                remoteSongCount = remotePlaylist.trackCount,
            )
        )
    }

    override suspend fun replaceLocalPlaylistItems(
        localPlaylistId: String,
        orderedLocalSongIds: List<String>,
    ) {
        dao.replaceProviderSyncPlaylistSongMaps(
            localPlaylistId = localPlaylistId,
            maps = orderedLocalSongIds.mapIndexed { position, localSongId ->
                PlaylistSongMap(
                    playlistId = localPlaylistId,
                    songId = localSongId,
                    position = position,
                )
            },
        )
    }

    override suspend fun removeLocalPlaylistItem(
        localPlaylistId: String,
        localSongId: String,
    ) {
        dao.removeProviderSyncPlaylistSong(localPlaylistId, localSongId)
    }

    override suspend fun deleteLocalPlaylist(localPlaylistId: String) {
        dao.deleteProviderSyncPlaylist(localPlaylistId)
    }

    override suspend fun replaceProviderPlaylistItems(
        provider: ProviderId,
        remotePlaylistId: String,
        items: List<ProviderPlaylistItem>,
    ) {
        dao.replaceProviderPlaylistItems(provider.name, remotePlaylistId, items)
    }

    override suspend fun activeTombstones(
        provider: ProviderId,
        entityType: String,
    ): List<SyncTombstone> = dao.activeSyncTombstones(provider.name, entityType)

    override suspend fun upsertRun(run: SyncRun) {
        dao.upsertSyncRun(run)
    }

    override suspend fun upsertHealth(health: ProviderSyncHealth) {
        dao.upsertProviderSyncHealth(health)
    }

    override suspend fun currentHealth(provider: ProviderId): ProviderSyncHealth? =
        dao.providerSyncHealthSnapshot(provider.name)

    override suspend fun pendingOperationCount(provider: ProviderId): Int =
        dao.pendingSyncOperationCount(provider.name)

    override suspend fun enqueueOperation(operation: SyncOperation): SyncOperation =
        dao.enqueueSyncOperation(operation)

    override suspend fun claimOperations(
        provider: ProviderId,
        leaseOwner: String,
        now: LocalDateTime,
        leaseExpiresAt: LocalDateTime,
        limit: Int,
    ): List<SyncOperation> = dao.claimPendingSyncOperations(
        provider = provider.name,
        leaseOwner = leaseOwner,
        now = now,
        leaseExpiresAt = leaseExpiresAt,
        limit = limit,
    )

    override suspend fun markOperationSucceeded(
        id: String,
        leaseOwner: String,
        completedAt: LocalDateTime,
    ): Boolean = dao.markSyncOperationSucceeded(id, leaseOwner, completedAt) == 1

    override suspend fun markOperationRetryable(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        nextAttemptAt: LocalDateTime,
        error: String,
    ): Boolean = dao.markSyncOperationRetryableFailure(
        id = id,
        leaseOwner = leaseOwner,
        failedAt = failedAt,
        nextAttemptAt = nextAttemptAt,
        lastError = error,
    ) == 1

    override suspend fun markOperationPermanentFailure(
        id: String,
        leaseOwner: String,
        failedAt: LocalDateTime,
        error: String,
    ): Boolean = dao.markSyncOperationPermanentFailure(
        id = id,
        leaseOwner = leaseOwner,
        failedAt = failedAt,
        lastError = error,
    ) == 1
}
