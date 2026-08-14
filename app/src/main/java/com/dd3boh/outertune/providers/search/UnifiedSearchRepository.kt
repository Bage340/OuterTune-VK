/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.search

import com.dd3boh.outertune.providers.CapabilityAvailability
import com.dd3boh.outertune.providers.CapabilityUnavailableReasonCode
import com.dd3boh.outertune.providers.MusicProvider
import com.dd3boh.outertune.providers.ProviderCapability
import com.dd3boh.outertune.providers.ProviderResult
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.domain.TrackSearchRequest
import com.dd3boh.outertune.providers.matching.TrackMatch
import com.dd3boh.outertune.providers.matching.TrackMatcher

enum class VkSearchStatus {
    NOT_CONFIGURED,
    SIGN_IN_REQUIRED,
    CAPABILITY_UNAVAILABLE,
    SUCCESS,
    FAILURE,
}

enum class UnifiedSearchNotice {
    VK_SIGN_IN_REQUIRED,
    VK_SEARCH_UNAVAILABLE,
    VK_SEARCH_FAILED,
    VK_NO_HIGH_CONFIDENCE_MATCH,
    YOUTUBE_SEARCH_FAILED,
}

sealed interface SearchPayload<out T> {
    data object NotRequested : SearchPayload<Nothing>
    data class Success<T>(val value: T) : SearchPayload<T>
    data class Failure(val cause: Throwable) : SearchPayload<Nothing>
}

/** Result ordering is explicit: [vkTracks] are always presented before [youtube]. */
data class UnifiedSearchResult<T>(
    val vkTracks: List<RemoteTrack>,
    val youtube: SearchPayload<T>,
    val vkStatus: VkSearchStatus,
    val notice: UnifiedSearchNotice? = null,
    val canSearchYouTube: Boolean = false,
    val highConfidenceMatch: TrackMatch? = null,
    val unavailableReason: CapabilityUnavailableReasonCode? = null,
)

/**
 * Provider-aware search policy without any dependency on Compose or Innertube
 * result classes. Callers retain their existing YouTube payload type.
 */
class UnifiedSearchRepository(
    private val vkProvider: MusicProvider?,
    private val trackMatcher: TrackMatcher = TrackMatcher(),
) {
    /**
     * Broad search is VK-first. A successful VK request leaves YouTube behind
     * an explicit user action; unavailable or failed VK automatically falls back.
     */
    suspend fun <T> searchBroad(
        query: String,
        searchYouTube: suspend () -> Result<T>,
    ): UnifiedSearchResult<T> = when (val gate = vkGate()) {
        VkGate.Ready -> when (
            val vkResult = vkProvider!!.searchTracks(TrackSearchRequest(query))
        ) {
            is ProviderResult.Success -> UnifiedSearchResult(
                vkTracks = vkResult.value.tracks,
                youtube = SearchPayload.NotRequested,
                vkStatus = VkSearchStatus.SUCCESS,
                canSearchYouTube = true,
            )

            is ProviderResult.Failure -> youtubeFallback(
                searchYouTube = searchYouTube,
                vkStatus = VkSearchStatus.FAILURE,
                notice = UnifiedSearchNotice.VK_SEARCH_FAILED,
                unavailableReason = vkResult.error.unavailableReasonCode,
            )
        }

        is VkGate.Unavailable -> youtubeFallback(
            searchYouTube = searchYouTube,
            vkStatus = gate.status,
            notice = gate.notice,
            unavailableReason = gate.reason,
        )
    }

    /** Loads the optional YouTube section without repeating a successful VK request. */
    suspend fun <T> searchYouTubeToo(
        vkTracks: List<RemoteTrack>,
        searchYouTube: suspend () -> Result<T>,
    ): UnifiedSearchResult<T> = UnifiedSearchResult(
        vkTracks = vkTracks,
        youtube = searchYouTube.toPayload(),
        vkStatus = VkSearchStatus.SUCCESS,
        canSearchYouTube = false,
    )

    /**
     * A concrete-track lookup only reaches YouTube when VK cannot supply a
     * HIGH-confidence equivalent.
     */
    suspend fun <T> searchSpecificTrack(
        source: RemoteTrack,
        searchYouTube: suspend () -> Result<T>,
    ): UnifiedSearchResult<T> = when (val gate = vkGate()) {
        VkGate.Ready -> {
            val query = buildSpecificTrackQuery(source)
            when (val vkResult = vkProvider!!.searchTracks(TrackSearchRequest(query))) {
                is ProviderResult.Failure -> youtubeFallback(
                    searchYouTube = searchYouTube,
                    vkStatus = VkSearchStatus.FAILURE,
                    notice = UnifiedSearchNotice.VK_SEARCH_FAILED,
                    unavailableReason = vkResult.error.unavailableReasonCode,
                )

                is ProviderResult.Success -> {
                    val matches = trackMatcher.rank(source, vkResult.value.tracks)
                    val highConfidenceMatch = matches.automaticMatch
                    if (highConfidenceMatch != null) {
                        UnifiedSearchResult(
                            vkTracks = listOf(highConfidenceMatch.candidate),
                            youtube = SearchPayload.NotRequested,
                            vkStatus = VkSearchStatus.SUCCESS,
                            highConfidenceMatch = highConfidenceMatch,
                        )
                    } else {
                        youtubeFallback(
                            searchYouTube = searchYouTube,
                            vkStatus = VkSearchStatus.SUCCESS,
                            notice = UnifiedSearchNotice.VK_NO_HIGH_CONFIDENCE_MATCH,
                            vkTracks = matches.rankedMatches.map(TrackMatch::candidate),
                        )
                    }
                }
            }
        }

        is VkGate.Unavailable -> youtubeFallback(
            searchYouTube = searchYouTube,
            vkStatus = gate.status,
            notice = gate.notice,
            unavailableReason = gate.reason,
        )
    }

    private fun vkGate(): VkGate {
        val provider = vkProvider ?: return VkGate.Unavailable(
            status = VkSearchStatus.NOT_CONFIGURED,
            notice = UnifiedSearchNotice.VK_SEARCH_UNAVAILABLE,
            reason = CapabilityUnavailableReasonCode.NOT_DECLARED,
        )
        val searchAvailability = provider.availability(ProviderCapability.SEARCH_TRACK)
        if (searchAvailability is CapabilityAvailability.Unavailable) {
            return VkGate.Unavailable(
                status = VkSearchStatus.CAPABILITY_UNAVAILABLE,
                notice = UnifiedSearchNotice.VK_SEARCH_UNAVAILABLE,
                reason = searchAvailability.reasonCode,
            )
        }
        if (!provider.authState.value.isAuthenticated) {
            return VkGate.Unavailable(
                status = VkSearchStatus.SIGN_IN_REQUIRED,
                notice = UnifiedSearchNotice.VK_SIGN_IN_REQUIRED,
                reason = CapabilityUnavailableReasonCode.AUTH_REQUIRED,
            )
        }
        return VkGate.Ready
    }

    private suspend fun <T> youtubeFallback(
        searchYouTube: suspend () -> Result<T>,
        vkStatus: VkSearchStatus,
        notice: UnifiedSearchNotice,
        vkTracks: List<RemoteTrack> = emptyList(),
        unavailableReason: CapabilityUnavailableReasonCode? = null,
    ) = UnifiedSearchResult(
        vkTracks = vkTracks,
        youtube = searchYouTube.toPayload(),
        vkStatus = vkStatus,
        notice = notice,
        canSearchYouTube = false,
        unavailableReason = unavailableReason,
    )

    private suspend fun <T> (suspend () -> Result<T>).toPayload(): SearchPayload<T> =
        invoke().fold(
            onSuccess = { value -> SearchPayload.Success(value) },
            onFailure = { cause -> SearchPayload.Failure(cause) },
        )

    private fun buildSpecificTrackQuery(track: RemoteTrack): String = buildList {
        addAll(track.artists)
        add(track.title)
        track.album?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(separator = " ")

    private sealed interface VkGate {
        data object Ready : VkGate

        data class Unavailable(
            val status: VkSearchStatus,
            val notice: UnifiedSearchNotice,
            val reason: CapabilityUnavailableReasonCode,
        ) : VkGate
    }
}
