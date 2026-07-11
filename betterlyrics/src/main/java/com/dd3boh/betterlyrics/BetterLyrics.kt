package com.dd3boh.betterlyrics

import com.dd3boh.betterlyrics.models.TTMLResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Better Lyrics API (https://github.com/better-lyrics/api). The `/getLyrics` endpoint returns
 * Apple Music style TTML with syllable/word timing wrapped in a JSON `ttml` field. The raw TTML
 * is returned unchanged so word timing survives for karaoke rendering; parsing happens in the
 * shared lyrics parse entry point.
 */
object BetterLyrics {
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
                url("https://lyrics-api.boidu.dev")
            }

            expectSuccess = false
        }
    }

    /**
     * Look up word-synced TTML. Returns success with the raw TTML when a match is found, success with
     * null when the request succeeded but carried no TTML (a definitive absence), and a failure when the
     * request itself failed. Non-2xx responses (including the 401 returned for uncached songs) are
     * failures, not absences, so a missing API key is never mistaken for a song without lyrics.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String?> = runCatching {
        val response = client.get("/getLyrics") {
            parameter("s", title)
            parameter("a", artist)
            if (duration > 0) parameter("d", duration)
            if (!album.isNullOrBlank()) parameter("al", album)
        }
        if (response.status != HttpStatusCode.OK) {
            throw IOException("BetterLyrics request failed: HTTP ${response.status.value}")
        }
        response.body<TTMLResponse>().ttml?.trim()?.takeIf { it.isNotEmpty() }
    }
}
