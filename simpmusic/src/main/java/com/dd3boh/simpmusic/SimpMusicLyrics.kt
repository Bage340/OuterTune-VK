package com.dd3boh.simpmusic

import com.dd3boh.simpmusic.models.LyricsData
import com.dd3boh.simpmusic.models.LyricsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.math.abs

/**
 * SimpMusic lyrics API (https://api-lyrics.simpmusic.org). Lyrics are looked up by YouTube videoId,
 * so localized titles never break matching. The response carries a standard LRC line-synced string
 * (`syncedLyrics`), a plain string (`plainLyric`) and a word-synced string (`richSyncLyrics`); the
 * synced string is preferred and returned raw for parsing in the shared lyrics parse entry point.
 */
object SimpMusicLyrics {
    private val client by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            defaultRequest {
                url("https://api-lyrics.simpmusic.org")
            }

            expectSuccess = false
        }
    }

    private suspend fun queryLyrics(videoId: String): List<LyricsData> {
        val response = client.get("/v1/$videoId")
        // 404 means the API holds no lyrics for this videoId (a definitive absence), not a transport
        // failure; return empty so the caller reports NotFound rather than a Failed result.
        if (response.status == HttpStatusCode.NotFound) {
            return emptyList()
        }
        if (response.status != HttpStatusCode.OK) {
            throw IOException("SimpMusic lyrics request failed: HTTP ${response.status.value}")
        }
        val body = response.body<LyricsResponse>()
        return if (body.isSuccess) body.data else emptyList()
    }

    /**
     * Look up lyrics by videoId. Returns success with the raw synced (or plain) text when a match is
     * found, success with null when the request succeeded but carried no lyrics (a definitive absence),
     * and a failure when the request itself failed (non-2xx or transport error).
     */
    suspend fun getLyrics(
        videoId: String,
        duration: Int,
    ): Result<String?> = runCatching {
        queryLyrics(videoId).selectBestRaw(duration)
    }

    suspend fun getAllLyrics(
        videoId: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        val tracks = queryLyrics(videoId)
        val sorted = if (duration > 0) {
            tracks.sortedBy { abs((it.duration ?: 0) - duration) }
        } else {
            tracks
        }
        var plain = 0
        sorted.forEach { track ->
            track.syncedLyrics?.let(callback)
            if (track.plainLyrics != null && plain == 0) {
                plain++
                callback(track.plainLyrics)
            }
        }
    }

    /**
     * Pick the best track for [duration] and return its raw lyrics, preferring synced (line-timed LRC)
     * over plain text. Returns null when no track carries either.
     */
    internal fun List<LyricsData>.selectBestRaw(duration: Int): String? {
        val best = bestMatchingFor(duration)
        return best?.syncedLyrics ?: best?.plainLyrics
    }

    private fun List<LyricsData>.bestMatchingFor(duration: Int): LyricsData? {
        if (isEmpty()) return null
        if (duration <= 0 || size == 1) return first()
        return minByOrNull { abs((it.duration ?: 0) - duration) }
    }
}
