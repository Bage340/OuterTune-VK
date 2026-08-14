package com.dd3boh.outertune.providers.syncengine

import com.dd3boh.outertune.providers.ProviderId
import com.dd3boh.outertune.providers.domain.RemotePlaylist
import com.dd3boh.outertune.providers.domain.RemoteTrack
import com.dd3boh.outertune.providers.matching.TrackNormalizer
import com.dd3boh.outertune.providers.sync.IdempotencyKeyHelper
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID as JavaUuid

object StableSyncIds {
    fun localTrack(provider: ProviderId, remoteTrackId: String): String =
        "PRT" + digest(provider.name, remoteTrackId).take(24)

    fun localPlaylist(provider: ProviderId, remotePlaylistId: String): String =
        "PRP" + digest(provider.name, remotePlaylistId).take(24)

    fun artist(provider: ProviderId, artistName: String): String =
        "PRA" + digest(provider.name, TrackNormalizer.normalizeArtist(artistName)).take(24)

    fun fallbackMembership(
        provider: ProviderId,
        remotePlaylistId: String,
        remoteTrackId: String,
        position: Int,
    ): String = "generated:" + digest(
        provider.name,
        remotePlaylistId,
        remoteTrackId,
        position.toString(),
    )

    fun playlistMembershipTombstoneKey(
        remotePlaylistId: String,
        membershipId: String,
    ): String = "${remotePlaylistId.length}:$remotePlaylistId$membershipId"

    fun playlistItemOperationKey(
        localPlaylistId: String,
        localSongId: String,
    ): String = "${localPlaylistId.length}:$localPlaylistId$localSongId"

    fun belongsToPlaylist(tombstoneRemoteId: String, remotePlaylistId: String): Boolean =
        tombstoneRemoteId.startsWith("${remotePlaylistId.length}:$remotePlaylistId")

    private fun digest(vararg values: String): String =
        IdempotencyKeyHelper.hashPayload(values.joinToString(separator = "\u0000"))
}

object SyncFingerprint {
    fun remoteTrack(track: RemoteTrack): String = IdempotencyKeyHelper.hashPayload(
        listOf(
            TrackNormalizer.normalizeTitle(track.title).comparableText,
            track.artists.joinToString("\u0001", transform = TrackNormalizer::normalizeArtist),
            track.album?.let(TrackNormalizer::normalizePlainText).orEmpty(),
            track.durationSeconds?.toString().orEmpty(),
        ).joinToString("\u0000")
    )

    fun localTrack(track: LocalSyncTrack): String = IdempotencyKeyHelper.hashPayload(
        listOf(
            TrackNormalizer.normalizeTitle(track.title).comparableText,
            track.artists.joinToString("\u0001", transform = TrackNormalizer::normalizeArtist),
            track.album?.let(TrackNormalizer::normalizePlainText).orEmpty(),
            track.durationSeconds?.toString().orEmpty(),
        ).joinToString("\u0000")
    )

    fun remotePlaylist(playlist: RemotePlaylist): String = IdempotencyKeyHelper.hashPayload(
        TrackNormalizer.normalizePlainText(playlist.title)
    )

    fun localPlaylist(playlist: LocalSyncPlaylist): String = IdempotencyKeyHelper.hashPayload(
        TrackNormalizer.normalizePlainText(playlist.title)
    )
}

fun interface ProviderSyncClock {
    fun now(): LocalDateTime

    companion object {
        val SYSTEM = ProviderSyncClock {
            LocalDateTime.now(ZoneOffset.UTC)
        }
    }
}

fun interface SyncRunIdGenerator {
    fun create(provider: ProviderId): String

    companion object {
        val UUID = SyncRunIdGenerator { provider ->
            "sync-${provider.name.lowercase()}-${JavaUuid.randomUUID()}"
        }
    }
}
