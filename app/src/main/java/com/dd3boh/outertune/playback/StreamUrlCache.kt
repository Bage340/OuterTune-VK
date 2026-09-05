/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec

internal data class CachedStreamUrl(
    val mediaId: String,
    val url: String,
    val expiresAtMillis: Long,
    val clientName: String,
    val requestHeaders: Map<String, String>,
)

/** Keeps every property of one resolved stream together under a single lock. */
internal class StreamUrlCache(
    private val expirySafetyMarginMillis: Long = DEFAULT_EXPIRY_SAFETY_MARGIN_MILLIS,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val entries = HashMap<String, CachedStreamUrl>()

    init {
        require(expirySafetyMarginMillis >= 0) { "expirySafetyMarginMillis must not be negative" }
    }

    operator fun get(mediaId: String): CachedStreamUrl? = synchronized(lock) {
        val stream = entries[mediaId] ?: return@synchronized null
        if (stream.expiresAtMillis <= currentTimeMillis()) {
            entries.remove(mediaId)
            null
        } else {
            stream
        }
    }

    fun put(
        mediaId: String,
        url: String,
        requestHeaders: Map<String, String>,
        clientName: String,
        expiresInSeconds: Int,
    ): CachedStreamUrl = synchronized(lock) {
        val usableLifetimeMillis =
            (expiresInSeconds.coerceAtLeast(0).toLong() * 1_000L - expirySafetyMarginMillis)
                .coerceAtLeast(0L)
        CachedStreamUrl(
            mediaId = mediaId,
            url = url,
            expiresAtMillis = currentTimeMillis() + usableLifetimeMillis,
            clientName = clientName,
            requestHeaders = requestHeaders.toMap(),
        ).also { entries[mediaId] = it }
    }

    /** Returns the removed entry so a failed request can be attributed to its issuing client. */
    fun invalidate(mediaId: String): CachedStreamUrl? = synchronized(lock) {
        entries.remove(mediaId)
    }

    /** Invalidates an actual request URL after the upstream server rejects it. */
    fun invalidateUrl(url: String): CachedStreamUrl? = synchronized(lock) {
        val entry = entries.entries.firstOrNull { it.value.url == url } ?: return@synchronized null
        entries.remove(entry.key)
    }

    private companion object {
        const val DEFAULT_EXPIRY_SAFETY_MARGIN_MILLIS = 60_000L
    }
}

internal fun resolvedRequestHeaders(
    existingHeaders: Map<String, String>,
    stream: CachedStreamUrl,
): Map<String, String> = existingHeaders + stream.requestHeaders

internal fun DataSpec.withResolvedStream(stream: CachedStreamUrl): DataSpec =
    withUri(stream.url.toUri())
        .withRequestHeaders(resolvedRequestHeaders(httpRequestHeaders, stream))
