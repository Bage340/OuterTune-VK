/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.search

import com.dd3boh.outertune.providers.domain.RemoteTrack

enum class UnifiedSearchPhase {
    IDLE,
    LOADING,
    CONTENT,
    ERROR,
}

data class UnifiedSearchState(
    val phase: UnifiedSearchPhase = UnifiedSearchPhase.IDLE,
    val vkTracks: List<RemoteTrack> = emptyList(),
    val notice: UnifiedSearchNotice? = null,
    val showSearchYouTubeToo: Boolean = false,
    val youtubeLoaded: Boolean = false,
    val canRetry: Boolean = false,
) {
    val isLoading: Boolean
        get() = phase == UnifiedSearchPhase.LOADING

    fun loading(): UnifiedSearchState = copy(
        phase = UnifiedSearchPhase.LOADING,
        showSearchYouTubeToo = false,
        canRetry = false,
    )

    companion object {
        fun <T> from(result: UnifiedSearchResult<T>): UnifiedSearchState {
            val youtubeFailed = result.youtube is SearchPayload.Failure
            val hasContent = result.vkTracks.isNotEmpty() || result.youtube is SearchPayload.Success
            return UnifiedSearchState(
                phase = when {
                    youtubeFailed && !hasContent -> UnifiedSearchPhase.ERROR
                    else -> UnifiedSearchPhase.CONTENT
                },
                vkTracks = result.vkTracks,
                notice = if (youtubeFailed) {
                    UnifiedSearchNotice.YOUTUBE_SEARCH_FAILED
                } else {
                    result.notice
                },
                showSearchYouTubeToo = result.canSearchYouTube,
                youtubeLoaded = result.youtube is SearchPayload.Success,
                canRetry = youtubeFailed || result.vkStatus == VkSearchStatus.FAILURE,
            )
        }
    }
}
