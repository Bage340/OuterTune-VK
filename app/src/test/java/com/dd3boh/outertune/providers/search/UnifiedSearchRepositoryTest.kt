package com.dd3boh.outertune.providers.search

import com.dd3boh.outertune.providers.CapabilityAvailability
import com.dd3boh.outertune.providers.CapabilitySet
import com.dd3boh.outertune.providers.CapabilityUnavailableReasonCode
import com.dd3boh.outertune.providers.ProviderCapability
import com.dd3boh.outertune.providers.ProviderError
import com.dd3boh.outertune.providers.ProviderErrorCode
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.testing.FakeVkMusicProvider
import com.dd3boh.outertune.providers.vk.UnsupportedVkMusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSearchRepositoryTest {
    @Test
    fun `broad search calls VK first and defers YouTube after VK success`() = runBlocking {
        val provider = FakeVkMusicProvider(initialTracks = listOf(vkTrack()))
        val repository = UnifiedSearchRepository(provider)
        var youtubeCalls = 0

        val result = repository.searchBroad("Signal") {
            youtubeCalls += 1
            Result.success("youtube")
        }

        assertEquals(0, youtubeCalls)
        assertEquals(VkSearchStatus.SUCCESS, result.vkStatus)
        assertEquals(listOf("vk-signal"), result.vkTracks.map(RemoteTrack::remoteId))
        assertTrue(result.youtube is SearchPayload.NotRequested)
        assertTrue(result.canSearchYouTube)
        assertNull(result.notice)
    }

    @Test
    fun `Search YouTube too preserves VK section and loads YouTube once`() = runBlocking {
        val repository = UnifiedSearchRepository(FakeVkMusicProvider())
        val existingVkTracks = listOf(vkTrack())
        var youtubeCalls = 0

        val result = repository.searchYouTubeToo(existingVkTracks) {
            youtubeCalls += 1
            Result.success(listOf("yt-1"))
        }

        assertEquals(1, youtubeCalls)
        assertEquals(existingVkTracks, result.vkTracks)
        assertEquals(listOf("yt-1"), (result.youtube as SearchPayload.Success).value)
        assertFalse(result.canSearchYouTube)
    }

    @Test
    fun `signed out VK automatically falls back to YouTube with inline notice state`() = runBlocking {
        val provider = FakeVkMusicProvider(initiallyAuthenticated = false)
        val repository = UnifiedSearchRepository(provider)

        val result = repository.searchBroad("Signal") { Result.success("youtube") }

        assertEquals(VkSearchStatus.SIGN_IN_REQUIRED, result.vkStatus)
        assertEquals(UnifiedSearchNotice.VK_SIGN_IN_REQUIRED, result.notice)
        assertEquals(CapabilityUnavailableReasonCode.AUTH_REQUIRED, result.unavailableReason)
        assertEquals("youtube", (result.youtube as SearchPayload.Success).value)
    }

    @Test
    fun `unavailable capability takes precedence over a misleading sign in prompt`() = runBlocking {
        val provider = FakeVkMusicProvider(
            initialCapabilities = CapabilitySet.from(
                ProviderCapability.entries.associateWith { capability ->
                    if (capability == ProviderCapability.SEARCH_TRACK) {
                        CapabilityAvailability.Unavailable(
                            CapabilityUnavailableReasonCode.APP_NOT_ALLOWLISTED
                        )
                    } else {
                        CapabilityAvailability.Available
                    }
                }
            ),
            initiallyAuthenticated = false,
        )
        val repository = UnifiedSearchRepository(provider)

        val result = repository.searchBroad("Signal") { Result.success("youtube") }

        assertEquals(VkSearchStatus.CAPABILITY_UNAVAILABLE, result.vkStatus)
        assertEquals(UnifiedSearchNotice.VK_SEARCH_UNAVAILABLE, result.notice)
        assertEquals(CapabilityUnavailableReasonCode.APP_NOT_ALLOWLISTED, result.unavailableReason)
        assertEquals("youtube", (result.youtube as SearchPayload.Success).value)
    }

    @Test
    fun `VK provider error falls back and produces retryable presentation state`() = runBlocking {
        val provider = FakeVkMusicProvider()
        provider.failSearchWith(
            ProviderError(
                provider = ProviderId.VK,
                code = ProviderErrorCode.NETWORK,
                message = "network",
                isRetryable = true,
            )
        )
        val repository = UnifiedSearchRepository(provider)

        val result = repository.searchBroad("Signal") { Result.success("youtube") }
        val state = UnifiedSearchState.from(result)

        assertEquals(UnifiedSearchNotice.VK_SEARCH_FAILED, result.notice)
        assertEquals(VkSearchStatus.FAILURE, result.vkStatus)
        assertEquals(UnifiedSearchPhase.CONTENT, state.phase)
        assertTrue(state.youtubeLoaded)
        assertTrue(state.canRetry)
    }

    @Test
    fun `specific track does not call YouTube when VK match is HIGH`() = runBlocking {
        val provider = FakeVkMusicProvider(initialTracks = listOf(vkTrack()))
        val repository = UnifiedSearchRepository(provider)
        var youtubeCalls = 0

        val result = repository.searchSpecificTrack(sourceTrack()) {
            youtubeCalls += 1
            Result.success("youtube")
        }

        assertEquals(0, youtubeCalls)
        assertTrue(result.youtube is SearchPayload.NotRequested)
        assertEquals("vk-signal", result.highConfidenceMatch?.candidate?.remoteId)
        assertEquals(listOf("vk-signal"), result.vkTracks.map(RemoteTrack::remoteId))
    }

    @Test
    fun `specific track falls back only when best VK match is below HIGH`() = runBlocking {
        val candidate = vkTrack(duration = null, album = null)
        val provider = FakeVkMusicProvider(initialTracks = listOf(candidate))
        val repository = UnifiedSearchRepository(provider)
        var youtubeCalls = 0

        val result = repository.searchSpecificTrack(
            sourceTrack(duration = null, album = null)
        ) {
            youtubeCalls += 1
            Result.success("youtube")
        }

        assertEquals(1, youtubeCalls)
        assertNull(result.highConfidenceMatch)
        assertEquals(UnifiedSearchNotice.VK_NO_HIGH_CONFIDENCE_MATCH, result.notice)
        assertEquals("youtube", (result.youtube as SearchPayload.Success).value)
    }

    @Test
    fun `unsupported production provider cannot surface fake VK rows`() = runBlocking {
        val repository = UnifiedSearchRepository(UnsupportedVkMusicProvider())

        val result = repository.searchBroad("Signal") { Result.success("youtube") }

        assertTrue(result.vkTracks.isEmpty())
        assertEquals(VkSearchStatus.CAPABILITY_UNAVAILABLE, result.vkStatus)
        assertEquals(UnifiedSearchNotice.VK_SEARCH_UNAVAILABLE, result.notice)
        assertEquals("youtube", (result.youtube as SearchPayload.Success).value)
    }

    @Test
    fun `failed YouTube fallback produces inline error and retry state`() = runBlocking {
        val repository = UnifiedSearchRepository(null)

        val result = repository.searchBroad<String>("Signal") {
            Result.failure(IllegalStateException("youtube down"))
        }
        val state = UnifiedSearchState.from(result)

        assertEquals(UnifiedSearchPhase.ERROR, state.phase)
        assertFalse(state.youtubeLoaded)
        assertTrue(state.canRetry)
        assertEquals(UnifiedSearchNotice.YOUTUBE_SEARCH_FAILED, state.notice)
    }

    @Test
    fun `failed explicit YouTube search preserves VK content and exposes retry state`() = runBlocking {
        val repository = UnifiedSearchRepository(FakeVkMusicProvider())
        val existingVkTracks = listOf(vkTrack())

        val result = repository.searchYouTubeToo<String>(existingVkTracks) {
            Result.failure(IllegalStateException("youtube down"))
        }
        val state = UnifiedSearchState.from(result)

        assertEquals(UnifiedSearchPhase.CONTENT, state.phase)
        assertEquals(existingVkTracks, state.vkTracks)
        assertEquals(UnifiedSearchNotice.YOUTUBE_SEARCH_FAILED, state.notice)
        assertTrue(state.canRetry)
    }

    @Test
    fun `loading state preserves already visible VK rows without reporting stale retry`() {
        val existing = UnifiedSearchState(
            phase = UnifiedSearchPhase.CONTENT,
            vkTracks = listOf(vkTrack()),
            showSearchYouTubeToo = true,
            canRetry = true,
        )

        val loading = existing.loading()

        assertEquals(UnifiedSearchPhase.LOADING, loading.phase)
        assertEquals(existing.vkTracks, loading.vkTracks)
        assertFalse(loading.showSearchYouTubeToo)
        assertFalse(loading.canRetry)
    }

    private fun sourceTrack(
        duration: Int? = 180,
        album: String? = "Album",
    ) = RemoteTrack(
        provider = ProviderId.YOUTUBE,
        remoteId = "yt-signal",
        title = "Signal",
        artists = listOf("Artist"),
        durationSeconds = duration,
        album = album,
    )

    private fun vkTrack(
        duration: Int? = 181,
        album: String? = "Album",
    ) = RemoteTrack(
        provider = ProviderId.VK,
        remoteId = "vk-signal",
        title = "Signal",
        artists = listOf("Artist"),
        durationSeconds = duration,
        album = album,
    )
}
