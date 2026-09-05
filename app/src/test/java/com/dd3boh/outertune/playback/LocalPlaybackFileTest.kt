package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPlaybackFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun song(path: String? = null, artwork: String? = null, local: Boolean = true) = MediaMetadata(
        id = "LSIXAYsdfc",
        title = "Lost Frequency",
        artists = emptyList(),
        duration = 180,
        genre = null,
        isLocal = local,
        localPath = path,
        thumbnailUrl = artwork,
    )

    @Test
    fun disabledLocalSongRecoversExactAudioPathRetainedAsArtwork() {
        val audio = temporaryFolder.newFile("Lost Frequency.flac")
        val disabled = song(artwork = audio.absolutePath)
        val file = findLocalPlaybackFile(disabled, disabled)
        assertEquals(audio, file)
        assertEquals(
            PlaybackSourceKind.DATABASE_FILE,
            selectPlaybackSourceKind(false, file != null, false, false, isLocal = true),
        )
    }

    @Test
    fun existingDatabasePathWins() {
        val databaseFile = temporaryFolder.newFile("database.flac")
        val queueFile = temporaryFolder.newFile("queue.flac")
        assertEquals(databaseFile, findLocalPlaybackFile(song(databaseFile.path), song(queueFile.path)))
    }

    @Test
    fun staleDatabasePathDoesNotHideValidQueuePath() {
        val queueFile = temporaryFolder.newFile("queue.flac")
        assertEquals(queueFile, findLocalPlaybackFile(song("/missing.flac"), song(queueFile.path)))
    }

    @Test
    fun databasePathWinsOverOldArtwork() {
        val audio = temporaryFolder.newFile("audio.flac")
        val artwork = temporaryFolder.newFile("old.flac")
        assertEquals(audio, findLocalPlaybackFile(song(audio.path, artwork.path), null))
    }

    @Test
    fun remoteArtworkIsNeverTreatedAsAudio() {
        val cover = temporaryFolder.newFile("cover.jpg")
        assertNull(findLocalPlaybackFile(song(artwork = cover.path, local = false), null))
    }

    @Test
    fun missingLocalFileNeverReachesRemoteOrCaches() {
        val metadata = song()
        assertNull(findLocalPlaybackFile(metadata, metadata))
        assertEquals(
            PlaybackSourceKind.MISSING_LOCAL,
            selectPlaybackSourceKind(false, false, true, true, isLocalPlayback(metadata.id, metadata, metadata)),
        )
    }

    @Test
    fun missingMetadataDoesNotTurnGeneratedLocalIdIntoYouTubeId() {
        assertTrue(isLocalPlayback("LSIXAYsdfc", null, null))
        assertFalse(isLocalPlayback("LSIXAYsdfc0", null, null))
        assertFalse(isLocalPlayback("dQw4w9WgXcQ", null, null))
    }

    @Test
    fun directoriesAndRelativePathsAreRejected() {
        assertNull(findLocalPlaybackFile(song(temporaryFolder.root.path), null))
        assertNull(findLocalPlaybackFile(song("relative.flac"), null))
    }

    @Test
    fun ordinaryDownloadedYouTubeTrackStillUsesItsLocalPath() {
        val audio = temporaryFolder.newFile("download.mka")
        assertEquals(audio, findLocalPlaybackFile(song(audio.path, local = false), null))
    }
}
