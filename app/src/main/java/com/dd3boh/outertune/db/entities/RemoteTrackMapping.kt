package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.LocalDateTime

@Entity(
    tableName = "remote_track_mapping",
    primaryKeys = ["provider", "remoteTrackId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["localSongId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["localSongId", "provider"]),
    ],
)
data class RemoteTrackMapping(
    val provider: String,
    val remoteTrackId: String,
    val localSongId: String,
    val ownerId: String? = null,
    val secondaryId: String? = null,
    /** Stable public URL only; expiring playback URLs and credentials must not be persisted. */
    val remoteUrl: String? = null,
    val metadataReference: String? = null,
    val syncState: String = RemoteMappingSyncState.LINKED,
    val lastSeenAt: LocalDateTime? = null,
    val lastSyncedAt: LocalDateTime? = null,
    val metadataHash: String? = null,
    val duration: Int? = null,
    val confidence: Double? = null,
)
