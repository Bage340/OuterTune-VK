/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.testing

import com.dd3boh.outertune.providers.CapabilitySet
import com.dd3boh.outertune.providers.CapabilityUnavailableReasonCode
import com.dd3boh.outertune.providers.MusicProvider
import com.dd3boh.outertune.providers.ProviderCapability
import com.dd3boh.outertune.providers.ProviderError
import com.dd3boh.outertune.providers.ProviderErrorCode
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.ProviderResult
import com.dd3boh.outertune.providers.domain.CreateRemotePlaylistRequest
import com.dd3boh.outertune.providers.domain.PageRequest
import com.dd3boh.outertune.providers.domain.ProviderAccount
import com.dd3boh.outertune.providers.domain.ProviderAuthState
import com.dd3boh.outertune.providers.domain.ProviderAuthStatus
import com.dd3boh.outertune.providers.domain.ProviderPage
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemotePlaylistTrack
import com.dd3boh.outertune.providers.domain.RemoteStream
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.domain.TrackSearchRequest
import com.dd3boh.outertune.providers.domain.TrackSearchResult
import com.dd3boh.outertune.providers.domain.UpdateRemotePlaylistRequest
import com.dd3boh.outertune.providers.domain.UploadLocalAudioRequest
import com.dd3boh.outertune.providers.matching.TrackNormalizer
import com.dd3boh.outertune.providers.sync.IdempotencyKeyHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FakePlaylistSeed(
    val playlist: RemotePlaylist,
    val trackIds: List<String> = emptyList(),
)

/** Deterministic, in-memory test double. It is intentionally test-source only. */
class FakeVkMusicProvider(
    initialTracks: List<RemoteTrack> = emptyList(),
    initialPlaylists: List<FakePlaylistSeed> = emptyList(),
    initialLibraryTrackIds: Set<String> = emptySet(),
    initialCapabilities: CapabilitySet = CapabilitySet.allAvailable(),
    initiallyAuthenticated: Boolean = true,
) : MusicProvider {
    override val id: ProviderId = ProviderId.VK

    private val lock = Any()
    private val account = ProviderAccount(
        provider = ProviderId.VK,
        accountId = "fake-vk-account",
        displayName = "Fake VK User",
    )
    private val tracks = linkedMapOf<String, RemoteTrack>()
    private val playlists = linkedMapOf<String, FakePlaylistState>()
    private val libraryTrackIds = linkedSetOf<String>()
    private var playlistSequence = 1
    @Volatile
    private var searchFailure: ProviderError? = null

    private val _authState = MutableStateFlow(
        if (initiallyAuthenticated) {
            ProviderAuthState(ProviderAuthStatus.AUTHENTICATED, account)
        } else {
            ProviderAuthState(ProviderAuthStatus.SIGNED_OUT)
        }
    )
    override val authState: StateFlow<ProviderAuthState> = _authState.asStateFlow()

    private val _capabilities = MutableStateFlow(initialCapabilities)
    override val capabilities: StateFlow<CapabilitySet> = _capabilities.asStateFlow()

    init {
        initialTracks.forEach { track ->
            tracks[track.remoteId] = track.copy(provider = ProviderId.VK)
        }
        initialPlaylists.forEach { seed ->
            val playlist = seed.playlist.copy(provider = ProviderId.VK)
            playlists[playlist.remoteId] = FakePlaylistState(
                playlist = playlist,
                trackIds = seed.trackIds.distinct().toMutableList(),
            )
        }
        libraryTrackIds += initialLibraryTrackIds.filter(tracks::containsKey)
    }

    fun setCapabilities(value: CapabilitySet) {
        _capabilities.value = value
    }

    fun failSearchWith(error: ProviderError?) {
        searchFailure = error
    }

    fun snapshotLibraryTrackIds(): Set<String> = synchronized(lock) {
        libraryTrackIds.toSet()
    }

    fun snapshotPlaylistTrackIds(playlistId: String): List<String>? = synchronized(lock) {
        playlists[playlistId]?.trackIds?.toList()
    }

    override suspend fun signIn(): ProviderResult<ProviderAccount> =
        withCapability(ProviderCapability.AUTH, requireAuthentication = false) {
            _authState.value = ProviderAuthState(ProviderAuthStatus.AUTHENTICATED, account)
            ProviderResult.Success(account)
        }

    override suspend fun signOut(): ProviderResult<Unit> =
        withCapability(ProviderCapability.AUTH, requireAuthentication = false) {
            _authState.value = ProviderAuthState(ProviderAuthStatus.SIGNED_OUT)
            ProviderResult.Success(Unit)
        }

    override suspend fun refreshAuthentication(): ProviderResult<ProviderAccount> =
        withCapability(ProviderCapability.AUTH) {
            ProviderResult.Success(account)
        }

    override suspend fun searchTracks(
        request: TrackSearchRequest,
    ): ProviderResult<TrackSearchResult> = withCapability(ProviderCapability.SEARCH_TRACK) {
        searchFailure?.let { error -> return@withCapability ProviderResult.Failure(error) }
        val query = TrackNormalizer.normalizePlainText(request.query)
        if (query.isEmpty()) return@withCapability invalid("Search query must not be blank")
        val queryTerms = query.split(' ').filter(String::isNotEmpty)
        val matching = synchronized(lock) {
            tracks.values
                .filter { track ->
                    val searchableText = sequenceOf(track.title, track.album.orEmpty())
                        .plus(track.artists.asSequence())
                        .map(TrackNormalizer::normalizePlainText)
                        .joinToString(separator = " ")
                    queryTerms.all { term -> term in searchableText }
                }
                .sortedBy(RemoteTrack::remoteId)
        }
        when (val page = page(matching, request.page)) {
            is ProviderResult.Failure -> page
            is ProviderResult.Success -> ProviderResult.Success(
                TrackSearchResult(
                    provider = ProviderId.VK,
                    query = request.query,
                    tracks = page.value.items,
                    continuationToken = page.value.continuationToken,
                )
            )
        }
    }

    override suspend fun getLibraryTracks(
        page: PageRequest,
    ): ProviderResult<ProviderPage<RemoteTrack>> = withCapability(ProviderCapability.LIBRARY_READ) {
        val library = synchronized(lock) {
            libraryTrackIds
                .mapNotNull(tracks::get)
                .map { track -> track.copy(isInLibrary = true) }
                .sortedBy(RemoteTrack::remoteId)
        }
        page(library, page)
    }

    override suspend fun addTrackToLibrary(trackId: String): ProviderResult<Unit> =
        withCapability(ProviderCapability.LIBRARY_WRITE) {
            synchronized(lock) {
                if (trackId !in tracks) return@synchronized notFound("Track", trackId)
                libraryTrackIds += trackId
                ProviderResult.Success(Unit)
            }
        }

    override suspend fun removeTrackFromLibrary(trackId: String): ProviderResult<Unit> =
        withCapability(ProviderCapability.LIBRARY_WRITE) {
            synchronized(lock) {
                if (trackId !in tracks) return@synchronized notFound("Track", trackId)
                libraryTrackIds -= trackId
                ProviderResult.Success(Unit)
            }
        }

    override suspend fun getPlaylists(
        page: PageRequest,
    ): ProviderResult<ProviderPage<RemotePlaylist>> = withCapability(ProviderCapability.PLAYLIST_READ) {
        val values = synchronized(lock) {
            playlists.values
                .map(FakePlaylistState::snapshot)
                .sortedBy(RemotePlaylist::remoteId)
        }
        page(values, page)
    }

    override suspend fun getPlaylist(playlistId: String): ProviderResult<RemotePlaylist> =
        withCapability(ProviderCapability.PLAYLIST_READ) {
            synchronized(lock) {
                playlists[playlistId]?.let { ProviderResult.Success(it.snapshot()) }
                    ?: notFound("Playlist", playlistId)
            }
        }

    override suspend fun getPlaylistTracks(
        playlistId: String,
        page: PageRequest,
    ): ProviderResult<ProviderPage<RemotePlaylistTrack>> =
        withCapability(ProviderCapability.PLAYLIST_READ) {
            val values = synchronized(lock) {
                val state = playlists[playlistId]
                    ?: return@synchronized null
                state.trackIds.mapIndexedNotNull { position, trackId ->
                    tracks[trackId]?.let { RemotePlaylistTrack(it, position) }
                }
            } ?: return@withCapability notFound("Playlist", playlistId)
            page(values, page)
        }

    override suspend fun createPlaylist(
        request: CreateRemotePlaylistRequest,
    ): ProviderResult<RemotePlaylist> = withCapability(ProviderCapability.PLAYLIST_WRITE) {
        if (request.title.isBlank()) return@withCapability invalid("Playlist title must not be blank")
        synchronized(lock) {
            var id: String
            do {
                id = "fake-playlist-${playlistSequence.toString().padStart(4, '0')}"
                playlistSequence += 1
            } while (id in playlists)
            val playlist = RemotePlaylist(
                provider = ProviderId.VK,
                remoteId = id,
                title = request.title,
                description = request.description,
                trackCount = 0,
                revision = "1",
                isEditable = true,
            )
            playlists[id] = FakePlaylistState(playlist, mutableListOf())
            ProviderResult.Success(playlist)
        }
    }

    override suspend fun updatePlaylist(
        request: UpdateRemotePlaylistRequest,
    ): ProviderResult<RemotePlaylist> = withCapability(ProviderCapability.PLAYLIST_WRITE) {
        val requestedTitle = request.title
        if (requestedTitle != null && requestedTitle.isBlank()) {
            return@withCapability invalid("Playlist title must not be blank")
        }
        synchronized(lock) {
            val state = playlists[request.playlistId]
                ?: return@synchronized notFound("Playlist", request.playlistId)
            val revision = (state.playlist.revision?.toIntOrNull() ?: 0) + 1
            state.playlist = state.playlist.copy(
                title = requestedTitle ?: state.playlist.title,
                description = request.description ?: state.playlist.description,
                revision = revision.toString(),
            )
            ProviderResult.Success(state.snapshot())
        }
    }

    override suspend fun deletePlaylist(playlistId: String): ProviderResult<Unit> =
        withCapability(ProviderCapability.PLAYLIST_WRITE) {
            synchronized(lock) {
                playlists.remove(playlistId)
                ProviderResult.Success(Unit)
            }
        }

    override suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        position: Int?,
    ): ProviderResult<Unit> = withCapability(ProviderCapability.PLAYLIST_WRITE) {
        synchronized(lock) {
            val state = playlists[playlistId]
                ?: return@synchronized notFound("Playlist", playlistId)
            if (trackId !in tracks) return@synchronized notFound("Track", trackId)
            if (trackId in state.trackIds && position == null) {
                return@synchronized ProviderResult.Success(Unit)
            }
            state.trackIds.removeAll { it == trackId }
            val insertionIndex = position?.coerceIn(0, state.trackIds.size) ?: state.trackIds.size
            state.trackIds.add(insertionIndex, trackId)
            state.bumpRevision()
            ProviderResult.Success(Unit)
        }
    }

    override suspend fun removeTrackFromPlaylist(
        playlistId: String,
        trackId: String,
    ): ProviderResult<Unit> = withCapability(ProviderCapability.PLAYLIST_WRITE) {
        synchronized(lock) {
            val state = playlists[playlistId]
                ?: return@synchronized notFound("Playlist", playlistId)
            if (state.trackIds.removeAll { it == trackId }) state.bumpRevision()
            ProviderResult.Success(Unit)
        }
    }

    override suspend fun reorderPlaylistTracks(
        playlistId: String,
        orderedTrackIds: List<String>,
    ): ProviderResult<Unit> = withCapability(ProviderCapability.PLAYLIST_ORDER_WRITE) {
        synchronized(lock) {
            val state = playlists[playlistId]
                ?: return@synchronized notFound("Playlist", playlistId)
            if (orderedTrackIds.size != orderedTrackIds.distinct().size ||
                orderedTrackIds.toSet() != state.trackIds.toSet()
            ) {
                return@synchronized invalid("Ordered IDs must contain every playlist track exactly once")
            }
            if (orderedTrackIds == state.trackIds) {
                return@synchronized ProviderResult.Success(Unit)
            }
            state.trackIds.clear()
            state.trackIds += orderedTrackIds
            state.bumpRevision()
            ProviderResult.Success(Unit)
        }
    }

    override suspend fun uploadLocalAudio(
        request: UploadLocalAudioRequest,
    ): ProviderResult<RemoteTrack> = withCapability(ProviderCapability.UPLOAD_LOCAL_AUDIO) {
        if (!request.rightsConfirmed) {
            return@withCapability invalid("Upload rights must be confirmed")
        }
        if (request.documentReference.isBlank() || request.title.isBlank() || request.artist.isBlank()) {
            return@withCapability invalid("Document reference, title, and artist are required")
        }
        val fingerprint = listOf(
            request.documentReference,
            request.title,
            request.artist,
            request.album.orEmpty(),
        ).joinToString(separator = "\u0000")
        val id = "fake-upload-${IdempotencyKeyHelper.hashPayload(fingerprint).take(16)}"
        synchronized(lock) {
            val track = tracks.getOrPut(id) {
                RemoteTrack(
                    provider = ProviderId.VK,
                    remoteId = id,
                    title = request.title,
                    artists = listOf(request.artist),
                    album = request.album,
                    isInLibrary = true,
                )
            }
            libraryTrackIds += id
            ProviderResult.Success(track.copy(isInLibrary = true))
        }
    }

    override suspend fun getStream(trackId: String): ProviderResult<RemoteStream> =
        withCapability(ProviderCapability.STREAM) {
            synchronized(lock) {
                if (trackId !in tracks) return@synchronized notFound("Track", trackId)
                ProviderResult.Success(
                    RemoteStream(url = "https://example.invalid/vk/stream/$trackId")
                )
            }
        }

    private inline fun <T> withCapability(
        capability: ProviderCapability,
        requireAuthentication: Boolean = true,
        block: () -> ProviderResult<T>,
    ): ProviderResult<T> {
        val capabilityCheck = checkCapability(capability)
        if (capabilityCheck is ProviderResult.Failure) return capabilityCheck
        if (requireAuthentication && !authState.value.isAuthenticated) {
            return ProviderResult.Failure(
                ProviderError(
                    provider = ProviderId.VK,
                    code = ProviderErrorCode.AUTH_REQUIRED,
                    message = "Fake VK account is signed out",
                    unavailableReasonCode = CapabilityUnavailableReasonCode.AUTH_REQUIRED,
                )
            )
        }
        return block()
    }

    private fun <T> page(values: List<T>, request: PageRequest): ProviderResult<ProviderPage<T>> {
        if (request.pageSize <= 0) return invalid("Page size must be positive")
        val continuationToken = request.continuationToken
        val parsedOffset = continuationToken?.toIntOrNull()
        val offset = parsedOffset ?: 0
        if (offset < 0 || offset > values.size ||
            (continuationToken != null && parsedOffset == null)
        ) {
            return invalid("Invalid continuation token")
        }
        val end = (offset + request.pageSize).coerceAtMost(values.size)
        return ProviderResult.Success(
            ProviderPage(
                items = values.subList(offset, end),
                continuationToken = end.takeIf { it < values.size }?.toString(),
            )
        )
    }

    private fun <T> invalid(message: String): ProviderResult<T> = ProviderResult.Failure(
        ProviderError(
            provider = ProviderId.VK,
            code = ProviderErrorCode.INVALID_REQUEST,
            message = message,
        )
    )

    private fun <T> notFound(entity: String, id: String): ProviderResult<T> = ProviderResult.Failure(
        ProviderError(
            provider = ProviderId.VK,
            code = ProviderErrorCode.NOT_FOUND,
            message = "$entity '$id' was not found",
        )
    )

    private data class FakePlaylistState(
        var playlist: RemotePlaylist,
        val trackIds: MutableList<String>,
    ) {
        fun snapshot(): RemotePlaylist = playlist.copy(trackCount = trackIds.size)

        fun bumpRevision() {
            playlist = playlist.copy(
                revision = ((playlist.revision?.toIntOrNull() ?: 0) + 1).toString(),
                trackCount = trackIds.size,
            )
        }
    }
}
