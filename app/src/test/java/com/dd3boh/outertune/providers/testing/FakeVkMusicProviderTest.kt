package com.dd3boh.outertune.providers.testing

import com.dd3boh.outertune.providers.CapabilityAvailability
import com.dd3boh.outertune.providers.CapabilitySet
import com.dd3boh.outertune.providers.CapabilityUnavailableReasonCode
import com.dd3boh.outertune.providers.ProviderCapability
import com.dd3boh.outertune.providers.ProviderErrorCode
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.ProviderResult
import com.dd3boh.outertune.providers.domain.CreateRemotePlaylistRequest
import com.dd3boh.outertune.providers.domain.PageRequest
import com.dd3boh.outertune.providers.domain.ProviderAuthStatus
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.domain.TrackSearchRequest
import com.dd3boh.outertune.providers.domain.UploadLocalAudioRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeVkMusicProviderTest {
    @Test
    fun `search and pagination are deterministic`() = runBlocking {
        val provider = providerWithThreeTracks()

        val first = provider.searchTracks(
            TrackSearchRequest("Signal", PageRequest(pageSize = 1))
        ).success()
        val second = provider.searchTracks(
            TrackSearchRequest(
                "Signal",
                PageRequest(continuationToken = first.continuationToken, pageSize = 1),
            )
        ).success()

        assertEquals(listOf("track-a"), first.tracks.map(RemoteTrack::remoteId))
        assertEquals("1", first.continuationToken)
        assertEquals(listOf("track-b"), second.tracks.map(RemoteTrack::remoteId))
        assertEquals(null, second.continuationToken)
    }

    @Test
    fun `library writes are idempotent`() = runBlocking {
        val provider = providerWithThreeTracks()

        assertTrue(provider.addTrackToLibrary("track-a") is ProviderResult.Success)
        assertTrue(provider.addTrackToLibrary("track-a") is ProviderResult.Success)
        assertEquals(setOf("track-a"), provider.snapshotLibraryTrackIds())

        assertTrue(provider.removeTrackFromLibrary("track-a") is ProviderResult.Success)
        assertTrue(provider.removeTrackFromLibrary("track-a") is ProviderResult.Success)
        assertTrue(provider.snapshotLibraryTrackIds().isEmpty())
    }

    @Test
    fun `playlist IDs and ordering are deterministic and duplicate safe`() = runBlocking {
        val provider = providerWithThreeTracks()
        val playlist = provider.createPlaylist(CreateRemotePlaylistRequest("Road trip")).success()

        assertEquals("fake-playlist-0001", playlist.remoteId)
        provider.addTrackToPlaylist(playlist.remoteId, "track-a").success()
        provider.addTrackToPlaylist(playlist.remoteId, "track-b").success()
        provider.addTrackToPlaylist(playlist.remoteId, "track-a").success()
        assertEquals(
            listOf("track-a", "track-b"),
            provider.snapshotPlaylistTrackIds(playlist.remoteId),
        )

        provider.reorderPlaylistTracks(
            playlist.remoteId,
            listOf("track-b", "track-a"),
        ).success()
        provider.reorderPlaylistTracks(
            playlist.remoteId,
            listOf("track-b", "track-a"),
        ).success()
        assertEquals(
            listOf("track-b", "track-a"),
            provider.snapshotPlaylistTrackIds(playlist.remoteId),
        )
    }

    @Test
    fun `capability denial is returned before executing an operation`() = runBlocking {
        val provider = providerWithThreeTracks()
        provider.setCapabilities(
            CapabilitySet.from(
                ProviderCapability.entries.associateWith { capability ->
                    if (capability == ProviderCapability.SEARCH_TRACK) {
                        CapabilityAvailability.Unavailable(
                            CapabilityUnavailableReasonCode.APP_NOT_ALLOWLISTED
                        )
                    } else {
                        CapabilityAvailability.Available
                    }
                }
            )
        )

        val result = provider.searchTracks(TrackSearchRequest("Signal"))

        assertTrue(result is ProviderResult.Failure)
        val error = (result as ProviderResult.Failure).error
        assertEquals(ProviderErrorCode.CAPABILITY_UNAVAILABLE, error.code)
        assertEquals(CapabilityUnavailableReasonCode.APP_NOT_ALLOWLISTED, error.unavailableReasonCode)
    }

    @Test
    fun `signed out operations fail until deterministic sign in`() = runBlocking {
        val provider = FakeVkMusicProvider(
            initialTracks = listOf(track("track-a", "Signal")),
            initiallyAuthenticated = false,
        )

        val signedOutResult = provider.searchTracks(TrackSearchRequest("Signal"))
        assertTrue(signedOutResult is ProviderResult.Failure)
        assertEquals(
            ProviderErrorCode.AUTH_REQUIRED,
            (signedOutResult as ProviderResult.Failure).error.code,
        )

        provider.signIn().success()
        assertEquals(ProviderAuthStatus.AUTHENTICATED, provider.authState.value.status)
        assertTrue(provider.searchTracks(TrackSearchRequest("Signal")) is ProviderResult.Success)
    }

    @Test
    fun `upload requires rights and retries return the same track`() = runBlocking {
        val provider = providerWithThreeTracks()
        val request = UploadLocalAudioRequest(
            documentReference = "content://test/song.flac",
            title = "My Song",
            artist = "My Artist",
            album = "My Album",
            rightsConfirmed = true,
        )

        val first = provider.uploadLocalAudio(request).success()
        val second = provider.uploadLocalAudio(request).success()

        assertEquals(first.remoteId, second.remoteId)
        assertTrue(first.remoteId in provider.snapshotLibraryTrackIds())

        val denied = provider.uploadLocalAudio(request.copy(rightsConfirmed = false))
        assertTrue(denied is ProviderResult.Failure)
        assertEquals(ProviderErrorCode.INVALID_REQUEST, (denied as ProviderResult.Failure).error.code)
    }

    @Test
    fun `invalid reorder cannot lose playlist contents`() = runBlocking {
        val provider = FakeVkMusicProvider(
            initialTracks = listOf(track("track-a", "A"), track("track-b", "B")),
            initialPlaylists = listOf(
                FakePlaylistSeed(
                    RemotePlaylist(
                        provider = ProviderId.VK,
                        remoteId = "playlist",
                        title = "Test",
                        isEditable = true,
                    ),
                    trackIds = listOf("track-a", "track-b"),
                )
            ),
        )

        val result = provider.reorderPlaylistTracks("playlist", listOf("track-a"))

        assertTrue(result is ProviderResult.Failure)
        assertEquals(listOf("track-a", "track-b"), provider.snapshotPlaylistTrackIds("playlist"))
    }

    private fun providerWithThreeTracks() = FakeVkMusicProvider(
        initialTracks = listOf(
            track("track-c", "Different"),
            track("track-b", "Signal Two"),
            track("track-a", "Signal One"),
        )
    )

    private fun track(id: String, title: String) = RemoteTrack(
        provider = ProviderId.VK,
        remoteId = id,
        title = title,
        artists = listOf("Artist"),
        durationSeconds = 180,
    )

    private fun <T> ProviderResult<T>.success(): T {
        assertTrue("Expected success, got $this", this is ProviderResult.Success)
        return (this as ProviderResult.Success<T>).value
    }
}
