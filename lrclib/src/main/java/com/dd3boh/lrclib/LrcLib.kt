package com.dd3boh.lrclib

import com.dd3boh.lrclib.models.Track
import com.dd3boh.lrclib.models.bestMatchingFor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * Source: https://github.com/Malopieds/InnerTune
 */
object LrcLib {
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
                url("https://lrclib.net")
            }

            expectSuccess = true
        }
    }

    private suspend fun queryLyrics(
        artist: String,
        title: String,
        album: String? = null,
    ) = client
        .get("/api/search") {
            parameter("track_name", title)
            parameter("artist_name", artist)
            if (album != null) parameter("album_name", album)
        }.body<List<Track>>()

    /**
     * Look up synced lyrics. Returns success with the raw LRC text when a match is found, success with
     * null when the search succeeded but carried no synced match (a definitive absence), and a failure
     * when the request itself failed. Non-2xx responses throw because [expectSuccess] is set.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String?> = runCatching {
        val syncedTracks = queryLyrics(artist, title, album).filter { it.syncedLyrics != null }
        syncedTracks.bestMatchingFor(duration)?.syncedLyrics?.let(LrcLib::Lyrics)?.text
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        val tracks = queryLyrics(artist, title, album)
        var count = 0
        var plain = 0
        tracks.forEach {
            if (count <= 4) {
                if (it.syncedLyrics != null && duration == -1) {
                    count++
                    it.syncedLyrics.let(callback)
                } else {
                    if (it.syncedLyrics != null && abs(it.duration - duration) <= 2) {
                        count++
                        it.syncedLyrics.let(callback)
                    }
                    if (it.plainLyrics != null && abs(it.duration - duration) <= 2 && plain == 0) {
                        count++
                        plain++
                        it.plainLyrics.let(callback)
                    }
                }
            }
        }
    }

    suspend fun lyrics(
        artist: String,
        title: String,
    ) = runCatching {
        queryLyrics(artist = artist, title = title, album = null)
    }

    @JvmInline
    value class Lyrics(
        val text: String,
    ) {
        val sentences
            get() =
                runCatching {
                    buildMap {
                        put(0L, "")
                        text.trim().lines().filter { it.length >= 10 }.forEach {
                            put(
                                it[8].digitToInt() * 10L +
                                        it[7].digitToInt() * 100 +
                                        it[5].digitToInt() * 1000 +
                                        it[4].digitToInt() * 10000 +
                                        it[2].digitToInt() * 60 * 1000 +
                                        it[1].digitToInt() * 600 * 1000,
                                it.substring(10),
                            )
                        }
                    }
                }.getOrNull()
    }
}
