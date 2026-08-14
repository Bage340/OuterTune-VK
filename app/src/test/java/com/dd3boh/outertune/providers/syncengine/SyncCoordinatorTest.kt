package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.RemoteTrackMapping
import com.dd3boh.outertune.db.entities.SyncHealthState
import com.dd3boh.outertune.db.entities.SyncOperationState
import com.dd3boh.outertune.db.entities.SyncRunState
import com.dd3boh.outertune.db.entities.SyncTombstone
import com.dd3boh.outertune.providers.MusicProvider
import com.dd3boh.outertune.providers.ProviderError
import com.dd3boh.outertune.providers.ProviderErrorCode
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.ProviderResult
import com.dd3boh.outertune.providers.domain.PageRequest
import com.dd3boh.outertune.providers.domain.ProviderPage
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.sync.ReconciliationActionType
import com.dd3boh.outertune.providers.sync.SyncConflictPolicy
import com.dd3boh.outertune.providers.sync.SyncEntityType
import com.dd3boh.outertune.providers.testing.FakePlaylistSeed
import com.dd3boh.outertune.providers.testing.FakeVkMusicProvider
import com.dd3boh.outertune.providers.vk.UnsupportedVkMusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SyncCoordinatorTest {
    @Test
    fun repeatedContinuationTokenIsDetectedWithoutLeakingIt() = runBlocking {
        val load = PaginatedProviderLoader.load<String>(
            provider = ProviderId.VK,
            pageSize = 1,
            maxPages = 3,
        ) {
            ProviderResult.Success(
                ProviderPage(items = emptyList(), continuationToken = "SECRET_CURSOR")
            )
        }

        assertFalse(load.isComplete)
        assertEquals(2, load.pagesRead)
        assertEquals(ProviderErrorCode.MALFORMED_RESPONSE, load.error?.code)
        assertFalse(load.error?.message.orEmpty().contains("SECRET_CURSOR"))
    }

    @Test
    fun unsupportedProductionProviderFinishesUnavailableWithoutDomainWrites() = runBlocking {
        val store = InMemoryProviderSyncStore()
        val provider = UnsupportedVkMusicProvider(
            musicUnavailableDetail = "SECRET_SERVER_DETAIL",
        )
        val outcome = coordinator(store, provider).synchronize(
            ProviderSyncRequest(provider = ProviderId.VK)
        )

        assertTrue(outcome is ProviderSyncOutcome.Unavailable)
        assertTrue(store.tracksSnapshot().isEmpty())
        assertTrue(store.playlistsSnapshot().isEmpty())
        assertTrue(store.trackMappingsSnapshot().isEmpty())
        assertTrue(store.playlistMappingsSnapshot().isEmpty())
        assertTrue(store.operationsSnapshot().isEmpty())
        assertEquals(SyncRunState.UNAVAILABLE, store.runsSnapshot().single().state)
        assertEquals(SyncHealthState.UNAVAILABLE, store.healthSnapshot(ProviderId.VK)?.state)
        assertFalse(outcome.report.diagnostics.joinToString().contains("SECRET_SERVER_DETAIL"))
    }

    @Test
    fun previewThenPaginatedSyncReconcilesLibraryPlaylistAndOrderedMembership() = runBlocking {
        val tracks = listOf(
            remoteTrack("track-1", "First", "Artist A", 181),
            remoteTrack("track-2", "Second", "Artist B", 202),
            remoteTrack("track-3", "Third", "Artist C", 223),
        )
        val remotePlaylist = RemotePlaylist(
            provider = ProviderId.VK,
            remoteId = "playlist-1",
            title = "Road Test",
            revision = "7",
            isEditable = true,
        )
        val provider = FakeVkMusicProvider(
            initialTracks = tracks,
            initialLibraryTrackIds = tracks.map(RemoteTrack::remoteId).toSet(),
            initialPlaylists = listOf(
                FakePlaylistSeed(remotePlaylist, tracks.map(RemoteTrack::remoteId))
            ),
        )
        val store = InMemoryProviderSyncStore()
        val coordinator = coordinator(store, provider)
        val request = ProviderSyncRequest(provider = ProviderId.VK, pageSize = 1)

        val preview = coordinator.preview(request)
        assertTrue(preview.report.dryRun)
        assertTrue(preview.report.counts.addToLocal > 0)
        assertTrue(preview.report.pagesRead >= 7)
        assertTrue(store.libraryIds().isEmpty())
        assertTrue(store.playlistsSnapshot().isEmpty())
        assertTrue(store.trackMappingsSnapshot().isEmpty())
        assertTrue(store.playlistMappingsSnapshot().isEmpty())
        assertTrue(store.operationsSnapshot().isEmpty())

        val outcome = coordinator.synchronize(request)
        assertTrue(outcome is ProviderSyncOutcome.Completed)
        val expectedTrackIds = tracks.map { StableSyncIds.localTrack(ProviderId.VK, it.remoteId) }
        assertEquals(expectedTrackIds.toSet(), store.libraryIds())
        assertEquals(3, store.trackMappingsSnapshot().size)
        assertEquals(1, store.playlistMappingsSnapshot().size)

        val localPlaylistId = StableSyncIds.localPlaylist(ProviderId.VK, remotePlaylist.remoteId)
        val localPlaylist = store.playlistsSnapshot()[localPlaylistId]
        assertNotNull(localPlaylist)
        assertEquals(
            expectedTrackIds,
            localPlaylist!!.items.sortedBy { it.position }.map { it.localSongId },
        )
        assertEquals(listOf(0, 1, 2), store.providerItemsSnapshot().map { it.position }.sorted())
        assertTrue(store.operationsSnapshot().isEmpty())
    }

    @Test
    fun persistedOutboxRetriesWithBackoffAndStableIdempotencyKey() = runBlocking {
        val remote = remoteTrack("retry-track", "Retry Me", "Backoff", 200)
        val fake = FakeVkMusicProvider(initialTracks = listOf(remote))
        val provider = FailOnceLibraryWriteProvider(fake)
        val store = InMemoryProviderSyncStore()
        val clock = MutableClock(LocalDateTime.of(2026, 1, 1, 0, 0))
        val localId = "local-retry-track"
        store.seedTrack(
            LocalSyncTrack(
                localSongId = localId,
                title = remote.title,
                artists = remote.artists,
                durationSeconds = remote.durationSeconds,
            )
        )
        store.seedTrackMapping(
            RemoteTrackMapping(
                provider = ProviderId.VK.name,
                remoteTrackId = remote.remoteId,
                localSongId = localId,
            )
        )
        val coordinator = coordinator(store, provider, clock)
        val request = ProviderSyncRequest(
            provider = ProviderId.VK,
            syncPlaylists = false,
        )

        val first = coordinator.synchronize(request)
        assertTrue(first is ProviderSyncOutcome.Partial)
        val failedOperation = store.operationsSnapshot().single()
        assertEquals(SyncOperationState.RETRYABLE_FAILURE, failedOperation.state)
        assertEquals(1, failedOperation.attemptCount)
        assertEquals(clock.now().plusSeconds(30), failedOperation.nextAttemptAt)
        assertEquals("PROVIDER_NETWORK", failedOperation.lastError)
        assertFalse(failedOperation.lastError.orEmpty().contains("SECRET_RETRY_TOKEN"))
        assertTrue(fake.snapshotLibraryTrackIds().isEmpty())

        clock.advanceSeconds(31)
        val second = coordinator.synchronize(request.copy(trigger = SyncTrigger.RETRY))
        assertTrue(second is ProviderSyncOutcome.Completed)
        val completedOperation = store.operationsSnapshot().single()
        assertEquals(failedOperation.idempotencyKey, completedOperation.idempotencyKey)
        assertEquals(SyncOperationState.SUCCEEDED, completedOperation.state)
        assertEquals(2, completedOperation.attemptCount)
        assertEquals(setOf(remote.remoteId), fake.snapshotLibraryTrackIds())
    }

    @Test
    fun incompleteRemotePageSuppressesExplicitDeletionAndSanitizesDiagnostics() = runBlocking {
        val firstRemote = remoteTrack("track-1", "First", "Artist", 180)
        val secondRemote = remoteTrack("track-2", "Second", "Artist", 181)
        val fake = FakeVkMusicProvider(
            initialTracks = listOf(firstRemote, secondRemote),
            initialLibraryTrackIds = setOf(firstRemote.remoteId, secondRemote.remoteId),
        )
        val provider = FailAfterFirstLibraryPageProvider(fake)
        val store = InMemoryProviderSyncStore()
        val clock = MutableClock(LocalDateTime.of(2026, 2, 1, 0, 0))
        val localId = "local-first"
        store.seedTrack(
            LocalSyncTrack(
                localSongId = localId,
                title = firstRemote.title,
                artists = firstRemote.artists,
                durationSeconds = firstRemote.durationSeconds,
            )
        )
        store.seedTrackMapping(
            RemoteTrackMapping(
                provider = ProviderId.VK.name,
                remoteTrackId = firstRemote.remoteId,
                localSongId = localId,
            )
        )
        store.seedTombstone(
            SyncTombstone(
                provider = ProviderId.VK.name,
                entityType = SyncEntityType.TRACK.name,
                remoteEntityId = firstRemote.remoteId,
                localEntityId = localId,
                deletedAt = clock.now(),
                source = SyncTombstoneSource.LOCAL,
            )
        )

        val outcome = coordinator(store, provider, clock).synchronize(
            ProviderSyncRequest(
                provider = ProviderId.VK,
                conflictPolicy = SyncConflictPolicy.LOCAL_WINS,
                allowDeletions = true,
                syncPlaylists = false,
                pageSize = 1,
            )
        )

        assertTrue(outcome is ProviderSyncOutcome.Partial)
        val deletion = outcome.report.actions.single {
            it.type == ReconciliationActionType.DELETE_REMOTE
        }
        assertEquals(SyncActionState.SUPPRESSED_INCOMPLETE_SNAPSHOT, deletion.state)
        assertTrue(store.operationsSnapshot().isEmpty())
        assertTrue(firstRemote.remoteId in fake.snapshotLibraryTrackIds())
        assertFalse(outcome.report.diagnostics.joinToString().contains("SECRET_CONTINUATION_TOKEN"))
        assertFalse(store.runsSnapshot().last().lastError.orEmpty().contains("SECRET_CONTINUATION_TOKEN"))
    }

    @Test
    fun duplicateRemoteIdentityRequiresManualReviewWithoutImports() = runBlocking {
        val remote = remoteTrack("duplicate", "Same", "Artist", 190)
        val fake = FakeVkMusicProvider(
            initialTracks = listOf(remote),
            initialLibraryTrackIds = setOf(remote.remoteId),
        )
        val store = InMemoryProviderSyncStore()
        val outcome = coordinator(store, DuplicateLibraryIdentityProvider(fake)).synchronize(
            ProviderSyncRequest(provider = ProviderId.VK, syncPlaylists = false)
        )

        assertEquals(1, outcome.report.counts.manualReview)
        assertTrue(store.libraryIds().isEmpty())
        assertTrue(store.trackMappingsSnapshot().isEmpty())
        assertTrue(store.operationsSnapshot().isEmpty())
    }

    private fun coordinator(
        store: InMemoryProviderSyncStore,
        provider: MusicProvider,
        clock: ProviderSyncClock = MutableClock(LocalDateTime.of(2026, 1, 1, 0, 0)),
    ): SyncCoordinator {
        var runSequence = 0
        return SyncCoordinator(
            store = store,
            providers = StaticMusicProviderRegistry(listOf(provider)),
            clock = clock,
            runIdGenerator = SyncRunIdGenerator { id ->
                runSequence += 1
                "run-${id.name.lowercase()}-$runSequence"
            },
        )
    }

    private fun remoteTrack(
        id: String,
        title: String,
        artist: String,
        duration: Int,
    ): RemoteTrack = RemoteTrack(
        provider = ProviderId.VK,
        remoteId = id,
        title = title,
        artists = listOf(artist),
        durationSeconds = duration,
    )

    private class MutableClock(
        private var instant: LocalDateTime,
    ) : ProviderSyncClock {
        override fun now(): LocalDateTime = instant

        fun advanceSeconds(seconds: Long) {
            instant = instant.plusSeconds(seconds)
        }
    }

    private class FailOnceLibraryWriteProvider(
        private val delegate: MusicProvider,
    ) : MusicProvider by delegate {
        private var shouldFail = true

        override suspend fun addTrackToLibrary(trackId: String): ProviderResult<Unit> {
            if (!shouldFail) return delegate.addTrackToLibrary(trackId)
            shouldFail = false
            return ProviderResult.Failure(
                ProviderError(
                    provider = id,
                    code = ProviderErrorCode.NETWORK,
                    message = "SECRET_RETRY_TOKEN",
                    isRetryable = true,
                )
            )
        }
    }

    private class FailAfterFirstLibraryPageProvider(
        private val delegate: MusicProvider,
    ) : MusicProvider by delegate {
        override suspend fun getLibraryTracks(
            page: PageRequest,
        ): ProviderResult<ProviderPage<RemoteTrack>> {
            if (page.continuationToken == null) return delegate.getLibraryTracks(page)
            return ProviderResult.Failure(
                ProviderError(
                    provider = id,
                    code = ProviderErrorCode.NETWORK,
                    message = "SECRET_CONTINUATION_TOKEN=${page.continuationToken}",
                    isRetryable = true,
                )
            )
        }
    }

    private class DuplicateLibraryIdentityProvider(
        private val delegate: MusicProvider,
    ) : MusicProvider by delegate {
        override suspend fun getLibraryTracks(
            page: PageRequest,
        ): ProviderResult<ProviderPage<RemoteTrack>> = when (
            val result = delegate.getLibraryTracks(page)
        ) {
            is ProviderResult.Failure -> result
            is ProviderResult.Success -> ProviderResult.Success(
                result.value.copy(
                    items = result.value.items.flatMap { listOf(it, it) },
                    continuationToken = null,
                )
            )
        }
    }
}
