package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.Index
import java.time.LocalDateTime

/** Previous-known deletion marker. It intentionally has no FK so it survives local deletion. */
@Entity(
    tableName = "sync_tombstone",
    primaryKeys = ["provider", "entityType", "remoteEntityId"],
    indices = [
        Index(value = ["provider", "entityType", "deletedAt"]),
        Index(value = ["localEntityId"]),
    ],
)
data class SyncTombstone(
    val provider: String,
    val entityType: String,
    val remoteEntityId: String,
    val localEntityId: String? = null,
    val deletedAt: LocalDateTime,
    val source: String,
    val payloadHash: String? = null,
    val acknowledgedAt: LocalDateTime? = null,
)
