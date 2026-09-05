package com.dd3boh.outertune.playback

internal enum class PlaybackSourceKind {
    CUSTOM_DOWNLOAD,
    DATABASE_FILE,
    DOWNLOAD_CACHE,
    PLAYER_CACHE,
    REMOTE,
}

internal fun selectPlaybackSourceKind(
    customDownloadFound: Boolean,
    databaseFileFound: Boolean,
    downloadCacheHit: Boolean,
    playerCacheHit: Boolean,
): PlaybackSourceKind = when {
    customDownloadFound -> PlaybackSourceKind.CUSTOM_DOWNLOAD
    databaseFileFound -> PlaybackSourceKind.DATABASE_FILE
    downloadCacheHit -> PlaybackSourceKind.DOWNLOAD_CACHE
    playerCacheHit -> PlaybackSourceKind.PLAYER_CACHE
    else -> PlaybackSourceKind.REMOTE
}
