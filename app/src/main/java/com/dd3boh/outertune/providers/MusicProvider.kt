/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers

import com.dd3boh.outertune.providers.domain.CreateRemotePlaylistRequest
import com.dd3boh.outertune.providers.domain.PageRequest
import com.dd3boh.outertune.providers.domain.ProviderAccount
import com.dd3boh.outertune.providers.domain.ProviderAuthState
import com.dd3boh.outertune.providers.domain.ProviderPage
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemotePlaylistTrack
import com.dd3boh.outertune.providers.domain.RemoteStream
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.domain.TrackSearchRequest
import com.dd3boh.outertune.providers.domain.TrackSearchResult
import com.dd3boh.outertune.providers.domain.UpdateRemotePlaylistRequest
import com.dd3boh.outertune.providers.domain.UploadLocalAudioRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Capability-gated boundary for remote music services.
 *
 * Expected provider and network failures are returned as [ProviderResult] rather
 * than thrown. Cancellation remains normal coroutine cancellation and must not
 * be swallowed by implementations.
 */
interface MusicProvider {
    val id: ProviderId
    val authState: StateFlow<ProviderAuthState>
    val capabilities: StateFlow<CapabilitySet>

    fun availability(capability: ProviderCapability): CapabilityAvailability =
        capabilities.value.availability(capability)

    fun supports(capability: ProviderCapability): Boolean =
        capabilities.value.supports(capability)

    fun checkCapability(capability: ProviderCapability): ProviderResult<Unit> =
        capabilities.value.check(id, capability)

    suspend fun signIn(): ProviderResult<ProviderAccount>
    suspend fun signOut(): ProviderResult<Unit>
    suspend fun refreshAuthentication(): ProviderResult<ProviderAccount>

    suspend fun searchTracks(request: TrackSearchRequest): ProviderResult<TrackSearchResult>

    suspend fun getLibraryTracks(page: PageRequest = PageRequest()): ProviderResult<ProviderPage<RemoteTrack>>
    suspend fun addTrackToLibrary(trackId: String): ProviderResult<Unit>
    suspend fun removeTrackFromLibrary(trackId: String): ProviderResult<Unit>

    suspend fun getPlaylists(page: PageRequest = PageRequest()): ProviderResult<ProviderPage<RemotePlaylist>>
    suspend fun getPlaylist(playlistId: String): ProviderResult<RemotePlaylist>
    suspend fun getPlaylistTracks(
        playlistId: String,
        page: PageRequest = PageRequest(),
    ): ProviderResult<ProviderPage<RemotePlaylistTrack>>

    suspend fun createPlaylist(request: CreateRemotePlaylistRequest): ProviderResult<RemotePlaylist>
    suspend fun updatePlaylist(request: UpdateRemotePlaylistRequest): ProviderResult<RemotePlaylist>
    suspend fun deletePlaylist(playlistId: String): ProviderResult<Unit>
    suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        position: Int? = null,
    ): ProviderResult<Unit>
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): ProviderResult<Unit>
    suspend fun reorderPlaylistTracks(
        playlistId: String,
        orderedTrackIds: List<String>,
    ): ProviderResult<Unit>

    suspend fun uploadLocalAudio(request: UploadLocalAudioRequest): ProviderResult<RemoteTrack>
    suspend fun getStream(trackId: String): ProviderResult<RemoteStream>
}
