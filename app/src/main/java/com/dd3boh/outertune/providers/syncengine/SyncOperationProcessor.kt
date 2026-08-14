package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.db.entities.PlaylistSyncMode
import com.dd3boh.outertune.db.entities.RemotePlaylistMapping
import com.dd3boh.outertune.db.entities.SyncOperation
import com.dd3boh.outertune.providers.MusicProvider
import com.dd3boh.outertune.providers.ProviderError
import com.dd3boh.outertune.providers.ProviderErrorCode
import com.dd3boh.outertune.providers.ProviderResult
import com.dd3boh.outertune.providers.domain.CreateRemotePlaylistRequest
import com.dd3boh.outertune.providers.domain.UpdateRemotePlaylistRequest
import com.dd3boh.outertune.providers.sync.RemoteMutationType
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.min

data class OutboxProcessReport(
    val claimed: Int,
    val succeeded: Int,
    val retryableFailures: Int,
    val permanentFailures: Int,
) {
    val hasRetryableFailure: Boolean
        get() = retryableFailures > 0
}

class ExponentialSyncBackoff(
    private val initialDelay: Duration = Duration.ofSeconds(30),
    private val maximumDelay: Duration = Duration.ofHours(6),
) {
    init {
        require(!initialDelay.isNegative && !initialDelay.isZero)
        require(maximumDelay >= initialDelay)
    }

    fun nextAttemptAt(failedAt: LocalDateTime, attemptCount: Int): LocalDateTime {
        val exponent = (attemptCount - 1).coerceIn(0, 30)
        val multiplier = 1L shl exponent
        val delayMillis = min(
            maximumDelay.toMillis(),
            multiplyCapped(initialDelay.toMillis(), multiplier, maximumDelay.toMillis()),
        )
        return failedAt.plusNanos(delayMillis * 1_000_000)
    }

    private fun multiplyCapped(value: Long, multiplier: Long, cap: Long): Long =
        if (value > cap / multiplier) cap else value * multiplier
}

class SyncOperationProcessor(
    private val store: ProviderSyncStore,
    private val clock: ProviderSyncClock = ProviderSyncClock.SYSTEM,
    private val backoff: ExponentialSyncBackoff = ExponentialSyncBackoff(),
    private val leaseDuration: Duration = Duration.ofMinutes(5),
    private val batchSize: Int = 25,
    private val leaseOwnerFactory: () -> String = { "sync-worker-${UUID.randomUUID()}" },
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        require(batchSize > 0)
    }

    suspend fun process(
        provider: MusicProvider,
        maxOperations: Int,
    ): OutboxProcessReport {
        if (maxOperations <= 0) return OutboxProcessReport(0, 0, 0, 0)
        val leaseOwner = leaseOwnerFactory()
        var remaining = maxOperations
        var claimed = 0
        var succeeded = 0
        var retryableFailures = 0
        var permanentFailures = 0

        while (remaining > 0) {
            val now = clock.now()
            val operations = store.claimOperations(
                provider = provider.id,
                leaseOwner = leaseOwner,
                now = now,
                leaseExpiresAt = now.plus(leaseDuration),
                limit = min(batchSize, remaining),
            )
            if (operations.isEmpty()) break
            claimed += operations.size
            remaining -= operations.size

            for (operation in operations) {
                when (val execution = execute(provider, operation)) {
                    OperationExecution.Success -> {
                        if (store.markOperationSucceeded(operation.id, leaseOwner, clock.now())) {
                            succeeded += 1
                        }
                    }

                    is OperationExecution.Failure -> {
                        val failedAt = clock.now()
                        if (execution.retryable) {
                            val nextAttemptAt = backoff.nextAttemptAt(
                                failedAt = failedAt,
                                attemptCount = operation.attemptCount,
                            )
                            if (store.markOperationRetryable(
                                    id = operation.id,
                                    leaseOwner = leaseOwner,
                                    failedAt = failedAt,
                                    nextAttemptAt = nextAttemptAt,
                                    error = execution.message,
                                )
                            ) {
                                retryableFailures += 1
                            }
                        } else if (store.markOperationPermanentFailure(
                                id = operation.id,
                                leaseOwner = leaseOwner,
                                failedAt = failedAt,
                                error = execution.message,
                            )
                        ) {
                            permanentFailures += 1
                        }
                    }
                }
            }
        }

        return OutboxProcessReport(
            claimed = claimed,
            succeeded = succeeded,
            retryableFailures = retryableFailures,
            permanentFailures = permanentFailures,
        )
    }

    private suspend fun execute(
        provider: MusicProvider,
        operation: SyncOperation,
    ): OperationExecution {
        if (operation.provider != provider.id.name) {
            return OperationExecution.Failure(
                message = "OPERATION_PROVIDER_MISMATCH",
                retryable = false,
            )
        }
        val type = runCatching { RemoteMutationType.valueOf(operation.operationType) }
            .getOrElse {
                return OperationExecution.Failure(
                    message = "UNKNOWN_OPERATION_TYPE",
                    retryable = false,
                )
            }
        val payload = runCatching { SyncMutationPayloadCodec.decode(operation.payloadJson) }
            .getOrElse {
                return OperationExecution.Failure(
                    message = "INVALID_PERSISTED_PAYLOAD",
                    retryable = false,
                )
            }

        return try {
            when (type) {
                RemoteMutationType.ADD_TO_LIBRARY -> provider
                    .addTrackToLibrary(requireRemoteId(operation))
                    .toExecution()

                RemoteMutationType.REMOVE_FROM_LIBRARY -> provider
                    .removeTrackFromLibrary(requireRemoteId(operation))
                    .toExecution()

                RemoteMutationType.CREATE_PLAYLIST -> {
                    val localPlaylistId = requireNotNull(operation.localEntityId) {
                        "CREATE_PLAYLIST requires localEntityId"
                    }
                    // If the process died after mapping persistence but before the outbox state
                    // transition, replay completes without creating a duplicate playlist.
                    val existingMapping = store.playlistMappingsByLocalIds(
                        provider.id,
                        listOf(localPlaylistId),
                    ).singleOrNull()
                    if (existingMapping != null) {
                        OperationExecution.Success
                    } else {
                        when (
                            val result = provider.createPlaylist(
                                CreateRemotePlaylistRequest(
                                    title = requireNotNull(payload.title) {
                                        "CREATE_PLAYLIST requires title"
                                    },
                                    description = payload.description,
                                )
                            )
                        ) {
                            is ProviderResult.Failure -> result.error.toExecution()
                            is ProviderResult.Success -> {
                                store.savePlaylistMapping(
                                    RemotePlaylistMapping(
                                        provider = provider.id.name,
                                        remotePlaylistId = result.value.remoteId,
                                        localPlaylistId = localPlaylistId,
                                        remoteRevision = result.value.revision,
                                        lastSeenAt = clock.now(),
                                        lastSyncedAt = clock.now(),
                                        syncMode = PlaylistSyncMode.ADD_ONLY,
                                    )
                                )
                                OperationExecution.Success
                            }
                        }
                    }
                }

                RemoteMutationType.UPDATE_PLAYLIST -> when (
                    val result = provider.updatePlaylist(
                        UpdateRemotePlaylistRequest(
                            playlistId = requireRemoteId(operation),
                            title = payload.title,
                            description = payload.description,
                        )
                    )
                ) {
                    is ProviderResult.Failure -> result.error.toExecution()
                    is ProviderResult.Success -> {
                        val localPlaylistId = operation.localEntityId
                        if (localPlaylistId != null) {
                            store.savePlaylistMapping(
                                RemotePlaylistMapping(
                                    provider = provider.id.name,
                                    remotePlaylistId = result.value.remoteId,
                                    localPlaylistId = localPlaylistId,
                                    remoteRevision = result.value.revision,
                                    lastSeenAt = clock.now(),
                                    lastSyncedAt = clock.now(),
                                    syncMode = PlaylistSyncMode.ADD_ONLY,
                                )
                            )
                        }
                        OperationExecution.Success
                    }
                }

                RemoteMutationType.DELETE_PLAYLIST -> provider
                    .deletePlaylist(requireRemoteId(operation))
                    .toExecution()

                RemoteMutationType.ADD_TRACK_TO_PLAYLIST -> provider
                    .addTrackToPlaylist(
                        playlistId = requireRemoteId(operation),
                        trackId = requireNotNull(payload.trackId) {
                            "ADD_TRACK_TO_PLAYLIST requires trackId"
                        },
                        position = payload.position,
                    )
                    .toExecution()

                RemoteMutationType.REMOVE_TRACK_FROM_PLAYLIST -> provider
                    .removeTrackFromPlaylist(
                        playlistId = requireRemoteId(operation),
                        trackId = requireNotNull(payload.trackId) {
                            "REMOVE_TRACK_FROM_PLAYLIST requires trackId"
                        },
                    )
                    .toExecution()

                RemoteMutationType.REORDER_PLAYLIST -> provider
                    .reorderPlaylistTracks(
                        playlistId = requireRemoteId(operation),
                        orderedTrackIds = payload.orderedTrackIds,
                    )
                    .toExecution()

                RemoteMutationType.UPLOAD_AUDIO -> OperationExecution.Failure(
                    message = "UPLOAD_AUDIO_NOT_REPLAYABLE",
                    retryable = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            OperationExecution.Failure("INVALID_OPERATION_ARGUMENT", retryable = false)
        } catch (error: Exception) {
            OperationExecution.Failure(
                message = "OPERATION_EXCEPTION_${error.safeClassName()}",
                retryable = true,
            )
        }
    }

    private fun requireRemoteId(operation: SyncOperation): String =
        requireNotNull(operation.remoteEntityId) {
            "${operation.operationType} requires remoteEntityId"
        }

    private fun ProviderResult<*>.toExecution(): OperationExecution = when (this) {
        is ProviderResult.Failure -> error.toExecution()
        is ProviderResult.Success -> OperationExecution.Success
    }

    private fun ProviderError.toExecution(): OperationExecution.Failure =
        OperationExecution.Failure(
            message = "PROVIDER_${code.name}",
            retryable = isRetryable || code in retryableErrorCodes,
        )

    private sealed interface OperationExecution {
        data object Success : OperationExecution

        data class Failure(
            val message: String,
            val retryable: Boolean,
        ) : OperationExecution
    }

    private companion object {
        val retryableErrorCodes = setOf(
            ProviderErrorCode.NETWORK,
            ProviderErrorCode.RATE_LIMITED,
            ProviderErrorCode.TOKEN_EXPIRED,
            ProviderErrorCode.UNKNOWN,
        )
    }

    private fun Throwable.safeClassName(): String =
        this::class.java.simpleName.takeIf(String::isNotBlank) ?: "Exception"
}
