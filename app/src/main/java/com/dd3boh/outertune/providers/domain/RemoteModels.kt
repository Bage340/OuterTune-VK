/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.domain

import com.dd3boh.outertune.providers.ProviderError
import com.dd3boh.outertune.providers.ProviderId

data class ProviderTrackKey(
    val provider: ProviderId,
    val remoteId: String,
    val secondaryId: String? = null,
)

data class ProviderPlaylistKey(
    val provider: ProviderId,
    val remoteId: String,
)

data class RemoteTrack(
    val provider: ProviderId,
    val remoteId: String,
    val title: String,
    val artists: List<String>,
    val ownerId: String? = null,
    val album: String? = null,
    /** Duration uses seconds to match OuterTune v0.10.1's existing song model. */
    val durationSeconds: Int? = null,
    val artworkUrl: String? = null,
    val isInLibrary: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    val key: ProviderTrackKey
        get() = ProviderTrackKey(
            provider = provider,
            remoteId = remoteId,
            secondaryId = ownerId,
        )
}

data class RemotePlaylist(
    val provider: ProviderId,
    val remoteId: String,
    val title: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
    val revision: String? = null,
    val isEditable: Boolean = false,
) {
    val key: ProviderPlaylistKey
        get() = ProviderPlaylistKey(provider, remoteId)
}

data class RemotePlaylistTrack(
    val track: RemoteTrack,
    val position: Int,
)

data class PageRequest(
    val continuationToken: String? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

data class ProviderPage<T>(
    val items: List<T>,
    val continuationToken: String? = null,
) {
    val isComplete: Boolean
        get() = continuationToken == null
}

data class TrackSearchRequest(
    val query: String,
    val page: PageRequest = PageRequest(),
)

data class TrackSearchResult(
    val provider: ProviderId,
    val query: String,
    val tracks: List<RemoteTrack>,
    val continuationToken: String? = null,
)

data class ProviderAccount(
    val provider: ProviderId,
    val accountId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

enum class ProviderAuthStatus {
    SIGNED_OUT,
    SIGNING_IN,
    AUTHENTICATED,
    TOKEN_EXPIRED,
    ERROR,
}

data class ProviderAuthState(
    val status: ProviderAuthStatus,
    val account: ProviderAccount? = null,
    val error: ProviderError? = null,
) {
    val isAuthenticated: Boolean
        get() = status == ProviderAuthStatus.AUTHENTICATED && account != null
}

data class CreateRemotePlaylistRequest(
    val title: String,
    val description: String? = null,
)

data class UpdateRemotePlaylistRequest(
    val playlistId: String,
    val title: String? = null,
    val description: String? = null,
)

data class UploadLocalAudioRequest(
    /** Opaque document reference supplied by the platform integration (for example, a SAF URI). */
    val documentReference: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkDocumentReference: String? = null,
    val rightsConfirmed: Boolean,
)

data class RemoteStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAtEpochSeconds: Long? = null,
)
