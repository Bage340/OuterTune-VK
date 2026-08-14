package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.RemoteMappingSyncState
import com.dd3boh.outertune.db.entities.RemoteTrackMapping
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.matching.TrackMatcher
import java.time.LocalDateTime

data class ResolvedRemoteTrack(
    val remoteTrack: RemoteTrack,
    val localSongId: String,
    val localTrackExists: Boolean,
    val mapping: RemoteTrackMapping,
    val mappingAlreadyPersisted: Boolean,
)

class TrackIdentityResolver(
    private val store: ProviderSyncStore,
    private val matcher: TrackMatcher = TrackMatcher(),
) {
    suspend fun resolve(
        provider: ProviderId,
        remoteTracks: List<RemoteTrack>,
        localCandidates: List<LocalSyncTrack>,
        now: LocalDateTime,
        persistSafeMappings: Boolean,
    ): List<ResolvedRemoteTrack> {
        val persistedMappings = store.trackMappingsByRemoteIds(
            provider = provider,
            remoteTrackIds = remoteTracks.map(RemoteTrack::remoteId),
        ).associateBy(RemoteTrackMapping::remoteTrackId)
        val candidatesById = localCandidates.associateBy(LocalSyncTrack::localSongId)
        val matcherCandidates = localCandidates.map { it.asMatcherTrack() }

        return remoteTracks.map { remoteTrack ->
            val existing = persistedMappings[remoteTrack.remoteId]
            if (existing != null) {
                val refreshed = existing.copy(
                    ownerId = remoteTrack.ownerId,
                    secondaryId = remoteTrack.key.secondaryId,
                    metadataReference = "${provider.name}:${remoteTrack.remoteId}",
                    syncState = RemoteMappingSyncState.LINKED,
                    lastSeenAt = now,
                    metadataHash = SyncFingerprint.remoteTrack(remoteTrack),
                    duration = remoteTrack.durationSeconds,
                )
                if (persistSafeMappings) store.saveTrackMapping(refreshed)
                ResolvedRemoteTrack(
                    remoteTrack = remoteTrack,
                    localSongId = refreshed.localSongId,
                    localTrackExists = refreshed.localSongId in candidatesById,
                    mapping = refreshed,
                    mappingAlreadyPersisted = true,
                )
            } else {
                val match = matcher.rank(remoteTrack, matcherCandidates).automaticMatch
                val matchedLocalId = match?.candidate?.remoteId
                val localSongId = matchedLocalId
                    ?: StableSyncIds.localTrack(provider, remoteTrack.remoteId)
                val mapping = RemoteTrackMapping(
                    provider = provider.name,
                    remoteTrackId = remoteTrack.remoteId,
                    localSongId = localSongId,
                    ownerId = remoteTrack.ownerId,
                    secondaryId = remoteTrack.key.secondaryId,
                    metadataReference = "${provider.name}:${remoteTrack.remoteId}",
                    syncState = RemoteMappingSyncState.LINKED,
                    lastSeenAt = now,
                    metadataHash = SyncFingerprint.remoteTrack(remoteTrack),
                    duration = remoteTrack.durationSeconds,
                    confidence = match?.score ?: 1.0,
                )
                val localExists = matchedLocalId != null
                if (persistSafeMappings && localExists) {
                    store.saveTrackMapping(mapping)
                }
                ResolvedRemoteTrack(
                    remoteTrack = remoteTrack,
                    localSongId = localSongId,
                    localTrackExists = localExists,
                    mapping = mapping,
                    mappingAlreadyPersisted = false,
                )
            }
        }
    }

    private fun LocalSyncTrack.asMatcherTrack(): RemoteTrack = RemoteTrack(
        provider = ProviderId.LOCAL,
        remoteId = localSongId,
        title = title,
        artists = artists,
        album = album,
        durationSeconds = durationSeconds,
        artworkUrl = artworkUrl,
        isInLibrary = true,
    )
}
