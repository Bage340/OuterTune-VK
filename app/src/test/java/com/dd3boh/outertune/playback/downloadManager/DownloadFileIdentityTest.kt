package com.dd3boh.outertune.playback.downloadManager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFileIdentityTest {
    @Test
    fun exactMediaIdComesFromTheFinalBracketPair() {
        assertEquals("R4v3-B0y", mediaIdFromDownloadFileName("S3RL [live] [R4v3-B0y].mka"))
    }

    @Test
    fun malformedNamesAreNotIndexed() {
        assertNull(mediaIdFromDownloadFileName(null))
        assertNull(mediaIdFromDownloadFileName("track.mka"))
        assertNull(mediaIdFromDownloadFileName("track [].mka"))
        assertNull(mediaIdFromDownloadFileName("notes [R4v3-B0y].bak"))
        assertNull(mediaIdFromDownloadFileName("notes[R4v3-B0y].mka"))
        assertNull(mediaIdFromDownloadFileName("notes [R4v3-B0y].mka.bak"))
    }
}
