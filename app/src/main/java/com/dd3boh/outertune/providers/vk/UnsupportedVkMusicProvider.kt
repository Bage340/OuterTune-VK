/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.vk

import com.dd3boh.outertune.providers.CapabilityAvailability
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Honest production placeholder used when no official VK Music API access is
 * available. It never returns sample music and must not be presented as a fake
 * or a working VK Music integration.
 */
class UnsupportedVkMusicProvider(
    musicReasonCode: CapabilityUnavailableReasonCode =
        CapabilityUnavailableReasonCode.OFFICIAL_API_ACCESS_UNAVAILABLE,
    musicUnavailableDetail: String =
        "Official VK Music API access is unavailable for this application",
) : MusicProvider {
    override val id: ProviderId = ProviderId.VK

    private val _authState = MutableStateFlow(
        ProviderAuthState(status = ProviderAuthStatus.SIGNED_OUT)
    )
    override val authState: StateFlow<ProviderAuthState> = _authState.asStateFlow()

    private val _capabilities = MutableStateFlow(
        CapabilitySet.from(
            ProviderCapability.entries.associateWith { capability ->
                if (capability == ProviderCapability.AUTH) {
                    CapabilityAvailability.Unavailable(
                        reasonCode = CapabilityUnavailableReasonCode.SDK_NOT_CONFIGURED,
                        detail = "VK ID SDK is not configured",
                    )
                } else {
                    CapabilityAvailability.Unavailable(
                        reasonCode = musicReasonCode,
                        detail = musicUnavailableDetail,
                    )
                }
            }
        )
    )
    override val capabilities: StateFlow<CapabilitySet> = _capabilities.asStateFlow()

    override suspend fun signIn(): ProviderResult<ProviderAccount> =
        unavailable(ProviderCapability.AUTH)

    override suspend fun signOut(): ProviderResult<Unit> =
        unavailable(ProviderCapability.AUTH)

    override suspend fun refreshAuthentication(): ProviderResult<ProviderAccount> =
        unavailable(ProviderCapability.AUTH)

    override suspend fun searchTracks(request: TrackSearchRequest): ProviderResult<TrackSearchResult> =
        unavailable(ProviderCapability.SEARCH_TRACK)

    override suspend fun getLibraryTracks(page: PageRequest): ProviderResult<ProviderPage<RemoteTrack>> =
        unavailable(ProviderCapability.LIBRARY_READ)

    override suspend fun addTrackToLibrary(trackId: String): ProviderResult<Unit> =
        unavailable(ProviderCapability.LIBRARY_WRITE)

    override suspend fun removeTrackFromLibrary(trackId: String): ProviderResult<Unit> =
        unavailable(ProviderCapability.LIBRARY_WRITE)

    override suspend fun getPlaylists(page: PageRequest): ProviderResult<ProviderPage<RemotePlaylist>> =
        unavailable(ProviderCapability.PLAYLIST_READ)

    override suspend fun getPlaylist(playlistId: String): ProviderResult<RemotePlaylist> =
        unavailable(ProviderCapability.PLAYLIST_READ)

    override suspend fun getPlaylistTracks(
        playlistId: String,
        page: PageRequest,
    ): ProviderResult<ProviderPage<RemotePlaylistTrack>> =
        unavailable(ProviderCapability.PLAYLIST_READ)

    override suspend fun createPlaylist(
        request: CreateRemotePlaylistRequest,
    ): ProviderResult<RemotePlaylist> = unavailable(ProviderCapability.PLAYLIST_WRITE)

    override suspend fun updatePlaylist(
        request: UpdateRemotePlaylistRequest,
    ): ProviderResult<RemotePlaylist> = unavailable(ProviderCapability.PLAYLIST_WRITE)

    override suspend fun deletePlaylist(playlistId: String): ProviderResult<Unit> =
        unavailable(ProviderCapability.PLAYLIST_WRITE)

    override suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        position: Int?,
    ): ProviderResult<Unit> = unavailable(ProviderCapability.PLAYLIST_WRITE)

    override suspend fun removeTrackFromPlaylist(
        playlistId: String,
        trackId: String,
    ): ProviderResult<Unit> = unavailable(ProviderCapability.PLAYLIST_WRITE)

    override suspend fun reorderPlaylistTracks(
        playlistId: String,
        orderedTrackIds: List<String>,
    ): ProviderResult<Unit> = unavailable(ProviderCapability.PLAYLIST_ORDER_WRITE)

    override suspend fun uploadLocalAudio(
        request: UploadLocalAudioRequest,
    ): ProviderResult<RemoteTrack> = unavailable(ProviderCapability.UPLOAD_LOCAL_AUDIO)

    override suspend fun getStream(trackId: String): ProviderResult<RemoteStream> =
        unavailable(ProviderCapability.STREAM)

    private fun <T> unavailable(capability: ProviderCapability): ProviderResult<T> {
        val availability = capabilities.value.availability(capability)
        val unavailable = availability as? CapabilityAvailability.Unavailable
        return ProviderResult.Failure(
            ProviderError(
                provider = id,
                code = if (unavailable?.reasonCode == CapabilityUnavailableReasonCode.AUTH_REQUIRED) {
                    ProviderErrorCode.AUTH_REQUIRED
                } else {
                    ProviderErrorCode.CAPABILITY_UNAVAILABLE
                },
                message = unavailable?.detail ?: "${capability.name} is unavailable for VK",
                unavailableReasonCode = unavailable?.reasonCode
                    ?: CapabilityUnavailableReasonCode.NOT_DECLARED,
            )
        )
    }
}
