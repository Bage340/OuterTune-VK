package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlCacheTest {
    @Test
    fun getReturnsTheAtomicEntryBeforeSafeExpiry() {
        var now = 1_000L
        val cache = StreamUrlCache { now }
        val inserted = cache.put(
            mediaId = "song",
            url = "https://example.com/stream",
            requestHeaders = mapOf("User-Agent" to "client"),
            clientName = "VISIONOS",
            expiresInSeconds = 120,
        )
        now += 59_999L

        assertEquals(inserted, cache["song"])
    }

    @Test
    fun getExpiresEntrySixtySecondsBeforeServerExpiry() {
        var now = 1_000L
        val cache = StreamUrlCache { now }
        cache.put("song", "https://example.com/stream", emptyMap(), "VISIONOS", 120)
        now += 60_000L

        assertNull(cache["song"])
        assertNull(cache.invalidate("song"))
    }

    @Test
    fun shortLivedEntryIsNeverReused() {
        val cache = StreamUrlCache { 1_000L }

        cache.put("song", "https://example.com/stream", emptyMap(), "VISIONOS", 30)

        assertNull(cache["song"])
    }

    @Test
    fun invalidateReturnsAndRemovesWholeEntry() {
        val cache = StreamUrlCache { 1_000L }
        val inserted = cache.put(
            mediaId = "song",
            url = "https://example.com/stream",
            requestHeaders = mapOf("User-Agent" to "client"),
            clientName = "VISIONOS",
            expiresInSeconds = 120,
        )

        assertEquals(inserted, cache.invalidate("song"))
        assertNull(cache["song"])
    }

    @Test
    fun invalidateUrlRemovesTheRejectedRequest() {
        val cache = StreamUrlCache { 1_000L }
        val inserted = cache.put(
            mediaId = "song",
            url = "https://example.com/rejected",
            requestHeaders = emptyMap(),
            clientName = "IOS",
            expiresInSeconds = 120,
        )

        assertEquals(inserted, cache.invalidateUrl("https://example.com/rejected"))
        assertNull(cache["song"])
    }

    @Test
    fun putDefensivelyCopiesHeaders() {
        val cache = StreamUrlCache { 1_000L }
        val headers = mutableMapOf("User-Agent" to "client")
        cache.put("song", "https://example.com/stream", headers, "VISIONOS", 120)
        headers["User-Agent"] = "changed"

        assertEquals("client", cache["song"]?.requestHeaders?.get("User-Agent"))
    }

    @Test
    fun resolvedHeadersRetainCallerHeadersAndPreferIssuingClient() {
        val stream = CachedStreamUrl(
            mediaId = "song",
            url = "https://example.com/stream",
            expiresAtMillis = 61_000L,
            clientName = "VISIONOS",
            requestHeaders = mapOf("User-Agent" to "stream", "Origin" to "stream-origin"),
        )

        val headers = resolvedRequestHeaders(
            existingHeaders = mapOf("Range" to "bytes=0-100", "User-Agent" to "existing"),
            stream = stream,
        )

        assertEquals("bytes=0-100", headers["Range"])
        assertEquals("stream", headers["User-Agent"])
        assertEquals("stream-origin", headers["Origin"])
    }
}
