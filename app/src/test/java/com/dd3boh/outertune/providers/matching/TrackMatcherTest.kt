package com.dd3boh.outertune.providers.matching

import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemoteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {
    private val matcher = TrackMatcher()

    @Test
    fun `normalization handles unicode case whitespace featuring and decorations`() {
        val normalized = TrackNormalizer.normalizeTitle(
            "  Ｓｉｇｎａｌ　(feat. Guest) [Official Audio]  "
        )

        assertEquals("signal", normalized.comparableText)
        assertTrue(normalized.variants.isEmpty())
    }

    @Test
    fun `normalized artist title and duration produce high confidence`() {
        val source = track(
            provider = ProviderId.YOUTUBE,
            id = "yt-1",
            title = "Ｓｉｇｎａｌ (feat. Guest) [Official Audio]",
            artist = "THE ARTIST",
            duration = 181,
            album = "The Album",
        )
        val candidate = track(
            provider = ProviderId.VK,
            id = "vk-1",
            title = "signal",
            artist = "the artist",
            duration = 179,
            album = "the album",
        )

        val result = matcher.evaluate(source, candidate)

        assertEquals(MatchConfidence.HIGH, result.confidence)
        assertTrue(result.canAutoLink)
        assertEquals(
            listOf(
                MatchSignal.NORMALIZED_ARTIST_TITLE,
                MatchSignal.DURATION_WITHIN_TOLERANCE,
                MatchSignal.NORMALIZED_ALBUM,
            ),
            result.signals,
        )
    }

    @Test
    fun `yo and e are used only as an explicit secondary heuristic`() {
        val source = track(title = "Песня", artist = "Алёна", duration = 200)
        val candidate = track(
            provider = ProviderId.VK,
            id = "vk-yo",
            title = "ПЕСНЯ",
            artist = "Алена",
            duration = 200,
        )

        val result = matcher.evaluate(source, candidate)

        assertEquals(MatchConfidence.HIGH, result.confidence)
        assertTrue(MatchSignal.YO_E_HEURISTIC in result.signals)
    }

    @Test
    fun `every protected variant is kept separate from the original`() {
        val source = track(title = "Signal", duration = 180, album = "Album")
        val variants = listOf(
            "Signal (Remix)",
            "Signal - Live",
            "Signal (Sped Up)",
            "Signal (Slowed Down)",
            "Signal (Instrumental)",
            "Signal (Remastered 2020)",
            "Signal (Cover)",
        )

        variants.forEachIndexed { index, title ->
            val result = matcher.evaluate(
                source,
                track(
                    provider = ProviderId.VK,
                    id = "variant-$index",
                    title = title,
                    duration = 180,
                    album = "Album",
                ),
            )
            assertEquals(title, MatchConfidence.LOW, result.confidence)
            assertTrue(title, MatchSignal.VARIANT_MISMATCH in result.signals)
            assertFalse(title, result.canAutoLink)
        }
    }

    @Test
    fun `matching versions of the same remaster can auto link`() {
        val source = track(title = "Signal - Remastered 2020", duration = 180)
        val candidate = track(
            provider = ProviderId.VK,
            id = "vk-remaster",
            title = "Signal (Remastered 2020)",
            duration = 181,
        )

        val result = matcher.evaluate(source, candidate)

        assertEquals(MatchConfidence.HIGH, result.confidence)
        assertTrue(MatchSignal.SAME_TRACK_VARIANT in result.signals)
    }

    @Test
    fun `existing mapping has priority over conflicting metadata`() {
        val source = track(title = "One", artist = "A")
        val candidate = track(
            provider = ProviderId.VK,
            id = "mapped",
            title = "Entirely different (Live)",
            artist = "B",
        )

        val result = matcher.rank(
            source = source,
            candidates = listOf(candidate),
            mappedCandidateKeys = setOf(candidate.key),
        ).best

        assertEquals(MatchConfidence.HIGH, result?.confidence)
        assertEquals(listOf(MatchSignal.EXISTING_PROVIDER_MAPPING), result?.signals)
    }

    @Test
    fun `exact provider ID is evaluated before metadata`() {
        val source = track(provider = ProviderId.VK, id = "same", title = "Old metadata")
        val candidate = track(provider = ProviderId.VK, id = "same", title = "New metadata")

        val result = matcher.evaluate(source, candidate)

        assertEquals(0.99, result.score, 0.0)
        assertEquals(listOf(MatchSignal.EXACT_PROVIDER_ID), result.signals)
    }

    @Test
    fun `artist and title without duration or album remain medium confidence`() {
        val result = matcher.evaluate(
            track(title = "Signal", artist = "Artist", duration = null),
            track(
                provider = ProviderId.VK,
                id = "vk-medium",
                title = "signal",
                artist = "artist",
                duration = null,
            ),
        )

        assertEquals(MatchConfidence.MEDIUM, result.confidence)
        assertNull(result.takeIf(TrackMatch::canAutoLink))
    }

    @Test
    fun `large duration conflict forces low confidence`() {
        val result = matcher.evaluate(
            track(title = "Signal", duration = 180, album = "Album"),
            track(
                provider = ProviderId.VK,
                id = "vk-long",
                title = "Signal",
                duration = 260,
                album = "Album",
            ),
        )

        assertEquals(MatchConfidence.LOW, result.confidence)
        assertTrue(MatchSignal.DURATION_CONFLICT in result.signals)
    }

    @Test
    fun `rank is deterministic for equal low confidence candidates`() {
        val source = track(title = "Source")
        val result = matcher.rank(
            source,
            listOf(
                track(provider = ProviderId.VK, id = "b", title = "Other"),
                track(provider = ProviderId.VK, id = "a", title = "Different"),
            ),
        )

        assertEquals(listOf("a", "b"), result.rankedMatches.map { it.candidate.remoteId })
        assertNull(result.automaticMatch)
    }

    private fun track(
        provider: ProviderId = ProviderId.YOUTUBE,
        id: String = "yt-source",
        title: String,
        artist: String = "Artist",
        duration: Int? = 180,
        album: String? = null,
    ) = RemoteTrack(
        provider = provider,
        remoteId = id,
        title = title,
        artists = listOf(artist),
        durationSeconds = duration,
        album = album,
    )
}
