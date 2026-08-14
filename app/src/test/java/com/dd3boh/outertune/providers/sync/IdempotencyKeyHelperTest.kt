package com.dd3boh.outertune.providers.sync

import com.dd3boh.outertune.providers.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotencyKeyHelperTest {
    @Test
    fun `same operation always produces the same key`() {
        val input = IdempotencyKeyInput(
            provider = ProviderId.VK,
            operationType = RemoteMutationType.ADD_TRACK_TO_PLAYLIST,
            entityType = SyncEntityType.PLAYLIST_TRACK,
            localEntityId = "local-playlist",
            remoteEntityId = "remote-track",
            payloadHash = IdempotencyKeyHelper.hashPayload("position=4"),
        )

        val first = IdempotencyKeyHelper.create(input)
        val second = IdempotencyKeyHelper.create(input.copy())

        assertEquals(first, second)
        assertTrue(first.matches(Regex("""v1:[0-9a-f]{64}""")))
    }

    @Test
    fun `field boundaries cannot collide`() {
        val first = IdempotencyKeyHelper.create(
            IdempotencyKeyInput(
                provider = ProviderId.VK,
                operationType = RemoteMutationType.UPDATE_PLAYLIST,
                entityType = SyncEntityType.PLAYLIST,
                localEntityId = "ab",
                remoteEntityId = "c",
            )
        )
        val second = IdempotencyKeyHelper.create(
            IdempotencyKeyInput(
                provider = ProviderId.VK,
                operationType = RemoteMutationType.UPDATE_PLAYLIST,
                entityType = SyncEntityType.PLAYLIST,
                localEntityId = "a",
                remoteEntityId = "bc",
            )
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `provider operation and payload are part of the key`() {
        val base = IdempotencyKeyInput(
            provider = ProviderId.VK,
            operationType = RemoteMutationType.ADD_TO_LIBRARY,
            entityType = SyncEntityType.TRACK,
            remoteEntityId = "track-1",
            payloadHash = "payload-a",
        )

        assertNotEquals(
            IdempotencyKeyHelper.create(base),
            IdempotencyKeyHelper.create(base.copy(provider = ProviderId.YOUTUBE)),
        )
        assertNotEquals(
            IdempotencyKeyHelper.create(base),
            IdempotencyKeyHelper.create(base.copy(operationType = RemoteMutationType.REMOVE_FROM_LIBRARY)),
        )
        assertNotEquals(
            IdempotencyKeyHelper.create(base),
            IdempotencyKeyHelper.create(base.copy(payloadHash = "payload-b")),
        )
    }

    @Test
    fun `null and empty IDs are distinct`() {
        val base = IdempotencyKeyInput(
            provider = ProviderId.VK,
            operationType = RemoteMutationType.CREATE_PLAYLIST,
            entityType = SyncEntityType.PLAYLIST,
        )

        assertNotEquals(
            IdempotencyKeyHelper.create(base),
            IdempotencyKeyHelper.create(base.copy(localEntityId = "")),
        )
    }
}
