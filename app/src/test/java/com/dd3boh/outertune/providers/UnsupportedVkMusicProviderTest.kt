package com.dd3boh.outertune.providers

import com.dd3boh.outertune.providers.domain.TrackSearchRequest
import com.dd3boh.outertune.providers.vk.UnsupportedVkMusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedVkMusicProviderTest {
    @Test
    fun `placeholder advertises every capability as unavailable`() {
        val provider = UnsupportedVkMusicProvider()

        assertEquals(ProviderId.VK, provider.id)
        ProviderCapability.entries.forEach { capability ->
            assertFalse(provider.supports(capability))
            assertTrue(provider.availability(capability) is CapabilityAvailability.Unavailable)
        }
        assertEquals(
            CapabilityUnavailableReasonCode.SDK_NOT_CONFIGURED,
            (provider.availability(ProviderCapability.AUTH) as
                CapabilityAvailability.Unavailable).reasonCode,
        )
        assertEquals(
            CapabilityUnavailableReasonCode.OFFICIAL_API_ACCESS_UNAVAILABLE,
            (provider.availability(ProviderCapability.SEARCH_TRACK) as
                CapabilityAvailability.Unavailable).reasonCode,
        )
    }

    @Test
    fun `placeholder never returns fake search data`() = runBlocking {
        val provider = UnsupportedVkMusicProvider()

        val result = provider.searchTracks(TrackSearchRequest("test"))

        assertTrue(result is ProviderResult.Failure)
        val error = (result as ProviderResult.Failure).error
        assertEquals(ProviderErrorCode.CAPABILITY_UNAVAILABLE, error.code)
        assertEquals(
            CapabilityUnavailableReasonCode.OFFICIAL_API_ACCESS_UNAVAILABLE,
            error.unavailableReasonCode,
        )
    }

    @Test
    fun `auth failure is distinct from music API failure`() = runBlocking {
        val provider = UnsupportedVkMusicProvider()

        val result = provider.signIn()

        assertTrue(result is ProviderResult.Failure)
        assertEquals(
            CapabilityUnavailableReasonCode.SDK_NOT_CONFIGURED,
            (result as ProviderResult.Failure).error.unavailableReasonCode,
        )
    }
}
