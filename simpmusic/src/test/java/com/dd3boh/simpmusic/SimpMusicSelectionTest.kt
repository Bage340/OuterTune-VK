package com.dd3boh.simpmusic

import com.dd3boh.simpmusic.SimpMusicLyrics.selectBestRaw
import com.dd3boh.simpmusic.models.LyricsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SimpMusicLyrics.selectBestRaw] picks a track by duration and prefers synced over plain lyrics.
 */
class SimpMusicSelectionTest {

    @Test
    fun prefersSyncedOverPlain() {
        val tracks = listOf(
            LyricsData(duration = 200, syncedLyrics = "[00:01.00]synced", plainLyrics = "plain"),
        )
        assertEquals("[00:01.00]synced", tracks.selectBestRaw(200))
    }

    @Test
    fun fallsBackToPlainWhenNoSynced() {
        val tracks = listOf(
            LyricsData(duration = 200, syncedLyrics = null, plainLyrics = "plain"),
        )
        assertEquals("plain", tracks.selectBestRaw(200))
    }

    @Test
    fun picksNearestDurationThenPrefersSynced() {
        val tracks = listOf(
            LyricsData(duration = 100, syncedLyrics = "far-synced", plainLyrics = "far-plain"),
            LyricsData(duration = 205, syncedLyrics = "near-synced", plainLyrics = "near-plain"),
        )
        assertEquals("near-synced", tracks.selectBestRaw(200))
    }

    @Test
    fun nullWhenBestHasNeither() {
        val tracks = listOf(
            LyricsData(duration = 200, syncedLyrics = null, plainLyrics = null),
        )
        assertNull(tracks.selectBestRaw(200))
    }

    @Test
    fun emptyReturnsNull() {
        assertNull(emptyList<LyricsData>().selectBestRaw(200))
    }
}
