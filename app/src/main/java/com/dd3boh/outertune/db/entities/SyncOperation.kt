package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/** Persisted outbox entry for a single idempotent remote mutation. */
@Entity(
    tableName = "sync_operation",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["provider", "state", "nextAttemptAt"]),
        Index(value = ["leaseOwner", "leaseExpiresAt"]),
        Index(value = ["provider", "entityType", "localEntityId"]),
    ],
)
data class SyncOperation(
    @PrimaryKey val id: String,
    val provider: String,
    val operationType: String,
    val entityType: String,
    val localEntityId: String? = null,
    val remoteEntityId: String? = null,
    val payloadJson: String? = null,
    val payloadHash: String? = null,
    val idempotencyKey: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val attemptCount: Int = 0,
    val lastAttemptAt: LocalDateTime? = null,
    val nextAttemptAt: LocalDateTime? = null,
    val lastError: String? = null,
    val state: String = SyncOperationState.PENDING,
    val leaseOwner: String? = null,
    val leaseExpiresAt: LocalDateTime? = null,
    val completedAt: LocalDateTime? = null,
)
