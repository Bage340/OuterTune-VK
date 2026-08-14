package com.dd3boh.outertune.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitySetTest {
    @Test
    fun `allAvailable covers the complete provider contract`() {
        val capabilities = CapabilitySet.allAvailable()

        assertEquals(ProviderCapability.entries.toSet(), capabilities.asMap().keys)
        assertEquals(ProviderCapability.entries.toSet(), capabilities.availableCapabilities)
        ProviderCapability.entries.forEach { capability ->
            assertTrue(capabilities.supports(capability))
            assertEquals(
                CapabilityAvailability.Available,
                capabilities.availability(capability),
            )
        }
    }

    @Test
    fun `missing declarations become explicit NOT_DECLARED entries`() {
        val capabilities = CapabilitySet.from(
            mapOf(ProviderCapability.AUTH to CapabilityAvailability.Available)
        )

        assertTrue(capabilities.supports(ProviderCapability.AUTH))
        val search = capabilities.availability(ProviderCapability.SEARCH_TRACK)
        assertTrue(search is CapabilityAvailability.Unavailable)
        assertEquals(
            CapabilityUnavailableReasonCode.NOT_DECLARED,
            (search as CapabilityAvailability.Unavailable).reasonCode,
        )
    }

    @Test
    fun `capability check preserves a machine-readable unavailable reason`() {
        val capabilities = CapabilitySet.none(
            reasonCode = CapabilityUnavailableReasonCode.APP_NOT_ALLOWLISTED,
            detail = "Partner access required",
        )

        val result = capabilities.check(ProviderId.VK, ProviderCapability.PLAYLIST_WRITE)

        assertTrue(result is ProviderResult.Failure)
        val error = (result as ProviderResult.Failure).error
        assertEquals(ProviderErrorCode.CAPABILITY_UNAVAILABLE, error.code)
        assertEquals(CapabilityUnavailableReasonCode.APP_NOT_ALLOWLISTED, error.unavailableReasonCode)
        assertEquals("Partner access required", error.message)
    }

    @Test
    fun `withAvailable never implies undeclared capabilities`() {
        val capabilities = CapabilitySet.withAvailable(
            setOf(ProviderCapability.AUTH, ProviderCapability.SEARCH_TRACK)
        )

        assertTrue(capabilities.supports(ProviderCapability.AUTH))
        assertTrue(capabilities.supports(ProviderCapability.SEARCH_TRACK))
        assertFalse(capabilities.supports(ProviderCapability.STREAM))
        assertEquals(
            CapabilityUnavailableReasonCode.NOT_IMPLEMENTED,
            (capabilities.availability(ProviderCapability.STREAM) as
                CapabilityAvailability.Unavailable).reasonCode,
        )
    }
}
