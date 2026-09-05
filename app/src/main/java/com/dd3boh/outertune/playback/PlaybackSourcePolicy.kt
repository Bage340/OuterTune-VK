package com.dd3boh.outertune.playback

internal enum class PlaybackSourceKind {
    CUSTOM_DOWNLOAD,
    DATABASE_FILE,
    DOWNLOAD_CACHE,
    PLAYER_CACHE,
    MISSING_LOCAL,
    REMOTE,
}

internal fun selectPlaybackSourceKind(
    customDownloadFound: Boolean,
    databaseFileFound: Boolean,
    downloadCacheHit: Boolean,
    playerCacheHit: Boolean,
    isLocal: Boolean = false,
): PlaybackSourceKind = when {
    customDownloadFound -> PlaybackSourceKind.CUSTOM_DOWNLOAD
    databaseFileFound -> PlaybackSourceKind.DATABASE_FILE
    isLocal -> PlaybackSourceKind.MISSING_LOCAL
    downloadCacheHit -> PlaybackSourceKind.DOWNLOAD_CACHE
    playerCacheHit -> PlaybackSourceKind.PLAYER_CACHE
    else -> PlaybackSourceKind.REMOTE
}
