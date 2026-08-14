package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.SyncOperation
import com.dd3boh.outertune.db.entities.SyncOperationState
import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.sync.IdempotencyKeyHelper
import com.dd3boh.outertune.providers.sync.IdempotencyKeyInput
import com.dd3boh.outertune.providers.sync.RemoteMutationType
import com.dd3boh.outertune.providers.sync.SyncEntityType

class SyncOperationFactory(
    private val clock: ProviderSyncClock = ProviderSyncClock.SYSTEM,
) {
    fun create(
        provider: ProviderId,
        operationType: RemoteMutationType,
        entityType: SyncEntityType,
        localEntityId: String? = null,
        remoteEntityId: String? = null,
        payload: SyncMutationPayload = SyncMutationPayload(),
    ): SyncOperation {
        val encodedPayload = SyncMutationPayloadCodec.encode(payload)
        val payloadHash = IdempotencyKeyHelper.hashPayload(encodedPayload)
        val idempotencyKey = IdempotencyKeyHelper.create(
            IdempotencyKeyInput(
                provider = provider,
                operationType = operationType,
                entityType = entityType,
                localEntityId = localEntityId,
                remoteEntityId = remoteEntityId,
                payloadHash = payloadHash,
            )
        )
        val now = clock.now()
        return SyncOperation(
            id = "op:${idempotencyKey.substringAfter(':')}",
            provider = provider.name,
            operationType = operationType.name,
            entityType = entityType.name,
            localEntityId = localEntityId,
            remoteEntityId = remoteEntityId,
            payloadJson = encodedPayload,
            payloadHash = payloadHash,
            idempotencyKey = idempotencyKey,
            createdAt = now,
            updatedAt = now,
            state = SyncOperationState.PENDING,
        )
    }
}
