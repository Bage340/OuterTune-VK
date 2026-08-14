package com.dd3boh.outertune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.LocalDateTime

/** A provider playlist membership. [membershipId] is opaque and must never be parsed. */
@Entity(
    tableName = "provider_playlist_item",
    primaryKeys = ["provider", "remotePlaylistId", "membershipId"],
    foreignKeys = [
        ForeignKey(
            entity = RemotePlaylistMapping::class,
            parentColumns = ["provider", "remotePlaylistId"],
            childColumns = ["provider", "remotePlaylistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["localSongId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["provider", "remotePlaylistId", "position"]),
        Index(value = ["provider", "remoteTrackId"]),
        Index(value = ["localSongId"]),
    ],
)
data class ProviderPlaylistItem(
    val provider: String,
    val remotePlaylistId: String,
    val membershipId: String,
    val remoteTrackId: String,
    val localSongId: String? = null,
    val position: Int,
    val addedAt: LocalDateTime? = null,
    val lastSeenAt: LocalDateTime? = null,
    val metadataHash: String? = null,
)
