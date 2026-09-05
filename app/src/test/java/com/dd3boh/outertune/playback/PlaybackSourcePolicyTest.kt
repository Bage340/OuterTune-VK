package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSourcePolicyTest {
    @Test
    fun physicalCustomDownloadWinsOverEveryOtherSource() {
        assertEquals(
            PlaybackSourceKind.CUSTOM_DOWNLOAD,
            selectPlaybackSourceKind(
                customDownloadFound = true,
                databaseFileFound = true,
                downloadCacheHit = true,
                playerCacheHit = true,
            )
        )
    }

    @Test
    fun hydratedDatabaseFileWinsOverStaleQueueAndCaches() {
        assertEquals(
            PlaybackSourceKind.DATABASE_FILE,
            selectPlaybackSourceKind(
                customDownloadFound = false,
                databaseFileFound = true,
                downloadCacheHit = true,
                playerCacheHit = true,
            )
        )
    }

    @Test
    fun completedDownloadCacheAvoidsRemoteResolution() {
        assertEquals(
            PlaybackSourceKind.DOWNLOAD_CACHE,
            selectPlaybackSourceKind(
                customDownloadFound = false,
                databaseFileFound = false,
                downloadCacheHit = true,
                playerCacheHit = false,
            )
        )
    }

    @Test
    fun partialDownloadCacheDoesNotAvoidRemoteResolution() {
        assertEquals(
            PlaybackSourceKind.REMOTE,
            selectPlaybackSourceKind(
                customDownloadFound = false,
                databaseFileFound = false,
                downloadCacheHit = false,
                playerCacheHit = false,
            )
        )
    }

    @Test
    fun remoteIsChosenOnlyWhenNoLocalOrCachedSourceExists() {
        assertEquals(
            PlaybackSourceKind.REMOTE,
            selectPlaybackSourceKind(
                customDownloadFound = false,
                databaseFileFound = false,
                downloadCacheHit = false,
                playerCacheHit = false,
            )
        )
    }
}
