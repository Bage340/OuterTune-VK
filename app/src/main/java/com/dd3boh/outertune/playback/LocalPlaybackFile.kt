package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import java.io.File

private val localSongIdPattern = Regex("LS[A-Za-z]{8}")

internal fun isLocalPlayback(mediaId: String, databaseSong: MediaMetadata?, queueSong: MediaMetadata?): Boolean =
    databaseSong?.isLocal == true || queueSong?.isLocal == true || localSongIdPattern.matches(mediaId)

internal fun findLocalPlaybackFile(databaseSong: MediaMetadata?, queueSong: MediaMetadata?): File? {
    // Both local scanners store the audio file path as song artwork. Older scans
    // cleared localPath when disabling a song, but left this exact path intact.
    val candidates = listOf(
        databaseSong?.localPath,
        queueSong?.localPath,
        databaseSong?.takeIf { it.isLocal }?.thumbnailUrl,
        queueSong?.takeIf { it.isLocal }?.thumbnailUrl,
    )
    return candidates.filterNotNull().distinct().asSequence()
        .map(::File)
        .firstOrNull { it.isAbsolute && it.isFile && it.canRead() }
}
