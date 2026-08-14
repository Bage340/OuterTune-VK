package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.LocalDateTime

@Entity(
    tableName = "remote_playlist_mapping",
    primaryKeys = ["provider", "remotePlaylistId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["localPlaylistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["localPlaylistId", "provider"]),
    ],
)
data class RemotePlaylistMapping(
    val provider: String,
    val remotePlaylistId: String,
    val localPlaylistId: String,
    val remoteRevision: String? = null,
    val etag: String? = null,
    val lastSeenAt: LocalDateTime? = null,
    val lastSyncedAt: LocalDateTime? = null,
    val syncMode: String = PlaylistSyncMode.ADD_ONLY,
)
