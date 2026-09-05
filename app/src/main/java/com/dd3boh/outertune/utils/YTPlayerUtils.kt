/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.YTPlayerUtils.MAIN_CLIENT
import com.dd3boh.outertune.utils.YTPlayerUtils.STREAM_CLIENTS
import com.dd3boh.outertune.utils.YTPlayerUtils.validateStatus
import com.dd3boh.outertune.utils.cipher.SignatureCipherManager
import com.dd3boh.outertune.utils.potoken.PoTokenGenerator
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.ORIGIN_YOUTUBE_MUSIC
import com.zionhuang.innertube.models.YouTubeClient.Companion.REFERER_YOUTUBE_MUSIC
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.response.PlayerResponse
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

    private val URL_DIAGNOSTIC_PATTERN = Regex("""(?i)\bhttps?://\S+""")
    private val SECRET_DIAGNOSTIC_PATTERN =
        Regex("""(?i)\b(cookie|authorization|(?:po)?token|sig(?:nature)?)\s*[:=]\s*[^\s,|]+""")

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * How long a video keeps skipping WEB_REMIX after one of its streams was rejected. Long enough
     * to get past the rejection, short enough that a transient CDN failure does not pin the video to
     * a lower-quality client for the rest of the session.
     */
    private const val WEB_REMIX_FAILURE_TTL_MS = 5 * 60 * 1000L

    /** videoId -> when its WEB_REMIX stream was last rejected mid-playback. */
    private val webRemixFailures = ConcurrentHashMap<String, Long>()

    /**
     * Records that [videoId]'s WEB_REMIX stream url was refused while playing. Such a url passes
     * [validateStatus] and only fails later, so the resolver cannot tell it apart on its own; the
     * player has to report it back for the next resolution to move on to another client.
     */
    fun markWebRemixFailed(videoId: String) {
        webRemixFailures[videoId] = System.currentTimeMillis()
    }

    private fun hasRecentWebRemixFailure(videoId: String): Boolean {
        val failedAt = webRemixFailures[videoId] ?: return false
        if ((System.currentTimeMillis() - failedAt) !in 0 until WEB_REMIX_FAILURE_TTL_MS) {
            webRemixFailures.remove(videoId, failedAt)
            return false
        }
        return true
    }

    /**
     * Client used for metadata and the initial stream response. Other clients are not used for the
     * metadata because it can differ between them (e.g. different loudnessDb normalization targets).
     *
     * This has to be a client that carries the signed-in session. Leading with an anonymous one
     * hands YouTube an unauthenticated request for every single song, which is what gets answered
     * with "sign in to confirm you're not a bot".
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    /**
     * Clients tried for the stream, in order. Separate from [MAIN_CLIENT], which only has to be good
     * for metadata: the VR builds hand out the longest-lived urls for music, so they go first even
     * though the metadata keeps coming from the signed-in client. When this list reaches
     * [MAIN_CLIENT] its already-fetched response is reused instead of asking again.
     */
    private val STREAM_CLIENTS: Array<YouTubeClient> = arrayOf(
        // First on purpose: its urls play a track through, while the others get cut off partway.
        VISIONOS,
        ANDROID_VR_1_65_10,
        ANDROID_VR_1_43_32,
        WEB_REMIX,
        TVHTML5,
        IOS,
        // ANDROID stays out: its player request answers 400. Measured 2026-08-19.
    )


    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        /** Client that produced [streamUrl], so the player can report it back if the url is refused. */
        val streamClient: String,
        /**
         * Headers the media request has to carry. googlevideo issues a url on behalf of a specific
         * client and expects the fetch to look like it came from that client; a request with the
         * http library's own defaults is served briefly and then refused.
         */
        val streamHeaders: Map<String, String>,
    )

    internal data class StreamClientDiagnostic(
        val clientName: String,
        val status: String?,
        val reason: String?,
        val hasAudioFormat: Boolean,
        val hasStreamUrl: Boolean,
        val validationHttpCode: Int?,
        val validationError: String? = null,
    )

    private data class StreamValidationResult(
        val httpCode: Int? = null,
        val errorType: String? = null,
    ) {
        val isSuccessful: Boolean
            get() = httpCode in 200..299
    }

    private data class ResolvedStream(
        val playerResponse: PlayerResponse,
        val format: PlayerResponse.StreamingData.Format,
        val url: String,
        val expiresInSeconds: Int,
        val clientName: String,
        val headers: Map<String, String>,
    )

    internal fun diagnosticsSummary(diagnostics: List<StreamClientDiagnostic>): String =
        diagnostics.joinToString(" | ") { diagnostic ->
            val validation = diagnostic.validationHttpCode?.let { "HTTP $it" }
                ?: diagnostic.validationError
                ?: "-"
            "${diagnostic.clientName}:status=${sanitizeDiagnostic(diagnostic.status)}," +
                "reason=${sanitizeDiagnostic(diagnostic.reason)}," +
                "audio=${diagnostic.hasAudioFormat},url=${diagnostic.hasStreamUrl}," +
                "validate=$validation"
        }

    private fun sanitizeDiagnostic(value: String?): String = value
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.replace(URL_DIAGNOSTIC_PATTERN, "[redacted-url]")
        ?.replace(SECRET_DIAGNOSTIC_PATTERN) { match ->
            "${match.groupValues[1]}=[redacted]"
        }
        ?.take(120)
        ?.ifBlank { "-" }
        ?: "-"

    /** Identifies a media request as coming from the client the stream url was issued for. */
    private fun YouTubeClient.streamHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        when (clientName) {
            "WEB_REMIX" -> {
                put("Referer", REFERER_YOUTUBE_MUSIC)
                put("Origin", ORIGIN_YOUTUBE_MUSIC)
            }

            "WEB_CREATOR" -> {
                put("Referer", "https://studio.youtube.com/")
                put("Origin", "https://studio.youtube.com")
            }

            else -> {
                put("Referer", "https://www.youtube.com/")
                put("Origin", "https://www.youtube.com")
            }
        }
    }

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        clientStartIndex: Int = 0,
    ): Result<PlaybackData> = runCatching {
        Log.d(TAG, "Playback info requested: $videoId")

        // Required for some clients to get working streams, but not forced for MAIN_CLIENT: its
        // response is needed even when its streams won't work, so this is allowed to be null.
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)

        val isLoggedIn = YouTube.cookie != null && !YouTube.dataSyncId.isNullOrBlank()
        // The streaming (GVS) token is minted against this and the stream url is then fetched by a
        // session identifying itself with visitorData, so this has to be visitorData for both
        // signed-in and signed-out sessions. Binding it to dataSyncId while requests carry
        // visitorData is honoured for roughly the first minute of a track and refused after that.
        val sessionId = YouTube.visitorData

        Log.d(TAG, "[$videoId] signatureTimestamp: $signatureTimestamp, isLoggedIn: $isLoggedIn, " +
                "dataSyncId present: ${!YouTube.dataSyncId.isNullOrBlank()} (len=${YouTube.dataSyncId?.length ?: 0}), " +
                "visitorData present: ${!YouTube.visitorData.isNullOrBlank()}")

        val (webPlayerPot, webStreamingPot) = getWebClientPoTokenOrNull(videoId, sessionId)?.let {
            Pair(it.playerRequestPoToken, it.streamingDataPoToken)
        } ?: Pair(null, null).also {
            Log.w(TAG, "[$videoId] No po token")
        }

        val mainPlayerResult =
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, webPlayerPot)
        mainPlayerResult.exceptionOrNull()?.let {
            Log.w(TAG, "[$videoId] [${MAIN_CLIENT.clientName}] metadata request failed: ${it.javaClass.simpleName}")
        }
        val mainPlayerResponse = mainPlayerResult.getOrNull()

        val diagnostics = mutableListOf<StreamClientDiagnostic>()
        var resolvedStream: ResolvedStream? = null
        val normalizedStartIndex = Math.floorMod(clientStartIndex, STREAM_CLIENTS.size)
        val orderedClients = STREAM_CLIENTS.indices.map { offset ->
            STREAM_CLIENTS[(normalizedStartIndex + offset) % STREAM_CLIENTS.size]
        }

        for ((clientIndex, client) in orderedClients.withIndex()) {
            Log.d(TAG, "Trying stream client ${clientIndex + 1}/${orderedClients.size}: ${client.clientName}")

            if (client.loginRequired && !isLoggedIn) {
                diagnostics += StreamClientDiagnostic(
                    clientName = client.clientName,
                    status = "SKIPPED",
                    reason = "login context unavailable",
                    hasAudioFormat = false,
                    hasStreamUrl = false,
                    validationHttpCode = null,
                )
                continue
            }
            if (client.clientName == "WEB_REMIX" && hasRecentWebRemixFailure(videoId)) {
                Log.d(TAG, "[$videoId] skipping WEB_REMIX after a rejected stream")
                diagnostics += StreamClientDiagnostic(
                    clientName = client.clientName,
                    status = "SKIPPED",
                    reason = "recent stream rejection",
                    hasAudioFormat = false,
                    hasStreamUrl = false,
                    validationHttpCode = null,
                )
                continue
            }

            val playerResult = if (client == MAIN_CLIENT) {
                mainPlayerResult
            } else {
                YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot)
            }
            val streamPlayerResponse = playerResult.getOrNull()
            if (streamPlayerResponse == null) {
                val errorType = playerResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown error"
                Log.w(TAG, "[$videoId] [${client.clientName}] player request failed: $errorType")
                diagnostics += StreamClientDiagnostic(
                    clientName = client.clientName,
                    status = "REQUEST_ERROR",
                    reason = errorType,
                    hasAudioFormat = false,
                    hasStreamUrl = false,
                    validationHttpCode = null,
                )
                continue
            }

            Log.d(TAG, "[$videoId] stream client: ${client.clientName}, " +
                    "playabilityStatus: ${streamPlayerResponse.playabilityStatus.let {
                        it.status + (it.reason?.let { " - $it" } ?: "")
                    }}")

            val playabilityStatus = streamPlayerResponse.playabilityStatus
            if (playabilityStatus.status != "OK") {
                diagnostics += StreamClientDiagnostic(
                    clientName = client.clientName,
                    status = playabilityStatus.status,
                    reason = playabilityStatus.reason,
                    hasAudioFormat = false,
                    hasStreamUrl = false,
                    validationHttpCode = null,
                )
                continue
            }

            val formats = findFormats(streamPlayerResponse, audioQuality, connectivityManager)
            val expiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
            if (formats.isEmpty() || expiresInSeconds == null) {
                diagnostics += StreamClientDiagnostic(
                    clientName = client.clientName,
                    status = playabilityStatus.status,
                    reason = if (formats.isEmpty()) "no audio format" else "missing expiresInSeconds",
                    hasAudioFormat = formats.isNotEmpty(),
                    hasStreamUrl = false,
                    validationHttpCode = null,
                )
                continue
            }

            val streamHeaders = client.streamHeaders()
            var builtAnyUrl = false
            var lastValidation: StreamValidationResult? = null
            for (format in formats) {
                var streamUrl = findUrlOrNull(format, videoId) ?: continue
                builtAnyUrl = true
                if (client.useWebPoTokens && webStreamingPot != null) {
                    streamUrl += "&pot=$webStreamingPot"
                }
                val validation = validateStatus(streamUrl, streamHeaders)
                lastValidation = validation
                if (validation.isSuccessful) {
                    resolvedStream = ResolvedStream(
                        playerResponse = streamPlayerResponse,
                        format = format,
                        url = streamUrl,
                        expiresInSeconds = expiresInSeconds,
                        clientName = client.clientName,
                        headers = streamHeaders,
                    )
                    break
                }
            }

            diagnostics += StreamClientDiagnostic(
                clientName = client.clientName,
                status = playabilityStatus.status,
                reason = when {
                    !builtAnyUrl -> "stream URL unavailable"
                    resolvedStream == null -> "stream validation failed"
                    else -> playabilityStatus.reason
                },
                hasAudioFormat = true,
                hasStreamUrl = builtAnyUrl,
                validationHttpCode = lastValidation?.httpCode,
                validationError = lastValidation?.errorType,
            )
            if (resolvedStream != null) break
        }

        val selected = resolvedStream ?: run {
            val summary = diagnosticsSummary(diagnostics)
            Log.e(TAG, "[$videoId] no validated stream: $summary")
            throw PlaybackException(
                "No validated stream\n$summary",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }
        val metadataResponse = mainPlayerResponse ?: selected.playerResponse
        Log.i(TAG, "[$videoId] resolved ${selected.clientName} itag=${selected.format.itag}")

        PlaybackData(
            audioConfig = metadataResponse.playerConfig?.audioConfig,
            videoDetails = metadataResponse.videoDetails,
            playbackTracking = metadataResponse.playbackTracking,
            format = selected.format,
            streamUrl = selected.url,
            streamExpiresInSeconds = selected.expiresInSeconds,
            streamClient = selected.clientName,
            streamHeaders = selected.headers,
        )
    }

    /**
     * Retries transient player-resolution failures a few times. YouTube can briefly answer
     * "Video unavailable" for a valid track; downloads should not become permanently failed
     * because of one such response.
     */
    suspend fun playerResponseForPlaybackWithRetry(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        attempts: Int = 3,
        rejectedClient: String? = null,
    ): Result<PlaybackData> {
        require(attempts > 0) { "attempts must be positive" }
        val startIndex = STREAM_CLIENTS.indexOfFirst { it.clientName == rejectedClient } + 1
        var result: Result<PlaybackData>? = null
        for (attempt in 1..attempts) {
            if (attempt > 1) {
                kotlinx.coroutines.delay(250L * (attempt - 1))
            }
            result = playerResponseForPlayback(
                videoId = videoId,
                playlistId = playlistId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
                clientStartIndex = startIndex + attempt - 1,
            )
            if (result.isSuccess) return result
            Log.w(
                TAG,
                "[$videoId] stream resolution failed: ${result.exceptionOrNull()?.message}; retry $attempt/$attempts"
            )
        }
        return checkNotNull(result)
    }

    /**
     * Fetches a WEB_REMIX player response for non-streaming data, including
     * video metadata and playback tracking.
     *
     * Streaming URLs from this response are not guaranteed to be playable.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        // WEB_REMIX provides the playback tracking URL required for history registration.
        // Include the web player integrity fields because omitting the player PoToken may
        // cause the request to return UNPLAYABLE.
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        val webPlayerPot = getWebClientPoTokenOrNull(videoId, YouTube.visitorData)?.playerRequestPoToken
        return YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp, webPlayerPot)
    }

    private fun findFormats(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): List<PlayerResponse.StreamingData.Format> =
        playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio }
            ?.sortedByDescending {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }
            .orEmpty()

    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String, headers: Map<String, String>): StreamValidationResult {
        try {
            // googlevideo often rejects HEAD with 403 even when the stream plays; validate with a
            // tiny ranged GET instead, which is how the player actually fetches the media.
            val requestBuilder = okhttp3.Request.Builder()
                .header("Range", "bytes=0-0")
                .url(url)
            headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "stream validation failed: HTTP ${response.code}")
                }
                return StreamValidationResult(httpCode = response.code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream validation failed: ${e.javaClass.simpleName}")
            return StreamValidationResult(errorType = e.javaClass.simpleName)
        }
    }

    // Reports exceptions; returns null on failure.
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure {
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Resolves the playable stream URL for the given audio [format].
     *
     * @param videoId the id of the video [format] belongs to
     * @return the stream URL, or null if it could not be resolved; any error is reported, not thrown
     */
    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        val npResult = NewPipeUtils.getStreamUrl(format, videoId)
        npResult.getOrNull()?.let { return it }

        // fallback for cipher formats: deobfuscate in a WebView (see SignatureCipherManager)
        val signatureCipher = format.signatureCipher
        if (signatureCipher != null) {
            val url = SignatureCipherManager.deobfuscateStreamUrl(signatureCipher, videoId)
            if (url != null) return url
        }

        npResult.exceptionOrNull()?.let {
            Log.e(TAG, "[$videoId] getStreamUrl failed (itag=${format.itag}, hasUrl=${format.url != null}, hasCipher=${format.signatureCipher != null})", it)
            reportException(it)
        }
        return null
    }

    // Reports exceptions; returns null on failure.
    private fun getWebClientPoTokenOrNull(videoId: String, visitorData: String?): PoTokenResult? {
        if (visitorData == null) {
            Log.d(TAG, "[$videoId] visitorData is null")
            return null
        }
        try {
            return poTokenGenerator.getWebClientPoToken(videoId, visitorData)
        } catch (e: Exception) {
            reportException(e)
        }
        return null
    }
}
