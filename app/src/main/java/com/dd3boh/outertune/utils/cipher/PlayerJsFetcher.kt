/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils.cipher

import android.util.Log
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object PlayerJsFetcher {
    private const val TAG = "PlayerJsFetcher"
    private val client = OkHttpClient.Builder().proxy(YouTube.proxy).build()
    private val mutex = Mutex()
    @Volatile private var cached: Pair<String, String>? = null
    private val hashRegex = Regex("""/s/player/([0-9a-fA-F]{8})/""")

    internal data class PlayerJs(val hash: String, val code: String, val fromCache: Boolean)

    internal suspend fun getPlayerJs(videoId: String, forceRefresh: Boolean = false): PlayerJs? = mutex.withLock {
        if (!forceRefresh) cached?.let { return PlayerJs(it.first, it.second, true) }
        val result = runCatching { fetch(videoId) }.getOrElse {
            Log.e(TAG, "Failed to fetch player.js", it); null
        }
        if (result != null) cached = result
        result?.let { PlayerJs(it.first, it.second, false) }
    }

    fun invalidate() { cached = null }

    private suspend fun fetch(videoId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val hash = fetchPlayerHash(videoId) ?: run {
            Log.e(TAG, "Could not resolve player hash for $videoId"); return@withContext null
        }
        val url = "https://www.youtube.com/s/player/$hash/player_ias.vflset/en_US/base.js"
        val request = Request.Builder().url(url).header("User-Agent", YouTubeClient.USER_AGENT_WEB).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "player.js fetch failed: HTTP ${response.code}"); return@withContext null
            }
            val code = response.body?.string() ?: return@withContext null
            hash to code
        }
    }

    private fun fetchPlayerHash(videoId: String): String? {
        val request = Request.Builder().url("https://www.youtube.com/embed/$videoId")
            .header("User-Agent", YouTubeClient.USER_AGENT_WEB).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return hashRegex.find(body)?.groupValues?.get(1)
        }
    }
}
