package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(
    tableName = "sync_run",
    indices = [
        Index(value = ["provider", "startedAt"]),
        Index(value = ["state", "startedAt"]),
    ],
)
data class SyncRun(
    @PrimaryKey val id: String,
    val provider: String,
    val trigger: String,
    val state: String = SyncRunState.RUNNING,
    val health: String = SyncHealthState.HEALTHY,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime? = null,
    val cursor: String? = null,
    val pageCount: Int = 0,
    val scannedCount: Int = 0,
    val insertedCount: Int = 0,
    val updatedCount: Int = 0,
    val deletedCount: Int = 0,
    val conflictCount: Int = 0,
    val errorCount: Int = 0,
    val lastError: String? = null,
)

@Entity(
    tableName = "provider_sync_health",
    indices = [
        Index(value = ["state", "updatedAt"]),
    ],
)
data class ProviderSyncHealth(
    @PrimaryKey val provider: String,
    val state: String,
    val lastRunId: String? = null,
    val lastStartedAt: LocalDateTime? = null,
    val lastCompletedAt: LocalDateTime? = null,
    val lastSuccessfulAt: LocalDateTime? = null,
    val consecutiveFailures: Int = 0,
    val pendingOperationCount: Int = 0,
    val lastError: String? = null,
    val updatedAt: LocalDateTime,
)
