/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.providers.sync

import com.dd3boh.outertune.providers.ProviderId
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class RemoteMutationType {
    ADD_TO_LIBRARY,
    REMOVE_FROM_LIBRARY,
    CREATE_PLAYLIST,
    UPDATE_PLAYLIST,
    DELETE_PLAYLIST,
    ADD_TRACK_TO_PLAYLIST,
    REMOVE_TRACK_FROM_PLAYLIST,
    REORDER_PLAYLIST,
    UPLOAD_AUDIO,
}

enum class SyncEntityType {
    TRACK,
    PLAYLIST,
    PLAYLIST_TRACK,
}

data class IdempotencyKeyInput(
    val provider: ProviderId,
    val operationType: RemoteMutationType,
    val entityType: SyncEntityType,
    val localEntityId: String? = null,
    val remoteEntityId: String? = null,
    val payloadHash: String? = null,
)

/** Stable, delimiter-safe SHA-256 keys for persisted remote mutation retries. */
object IdempotencyKeyHelper {
    private const val VERSION = "outertune-provider-operation-v1"

    fun create(input: IdempotencyKeyInput): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            VERSION,
            input.provider.name,
            input.operationType.name,
            input.entityType.name,
            input.localEntityId,
            input.remoteEntityId,
            input.payloadHash,
        ).forEach { value -> digest.updateLengthPrefixed(value) }
        return "v1:${digest.digest().toHex()}"
    }

    fun hashPayload(payload: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(payload.toByteArray(StandardCharsets.UTF_8))
        .toHex()

    private fun MessageDigest.updateLengthPrefixed(value: String?) {
        if (value == null) {
            update(NULL_MARKER)
            return
        }
        update(VALUE_MARKER)
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(LocaleHolder.ROOT, byte.toInt() and 0xff)
    }

    private object LocaleHolder {
        val ROOT: java.util.Locale = java.util.Locale.ROOT
    }

    private val NULL_MARKER = byteArrayOf(0)
    private val VALUE_MARKER = byteArrayOf(1)
}
