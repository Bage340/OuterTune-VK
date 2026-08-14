package com.dd3boh.outertune.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration20To21Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, InternalDatabase::class.java)

    @After
    fun deleteDatabase() {
        instrumentation.targetContext.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate20To21_preservesDataAndBackfillsProviderIdentity() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            db.execSQL(
                """
                INSERT INTO song (id, title, duration, isLocal, liked)
                VALUES ('yt-track', 'Network track', 213, 0, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO song (id, title, duration, isLocal, liked)
                VALUES ('LS-local', 'Local track', 99, 1, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO playlist (id, name, browseId, isEditable, isLocal)
                VALUES ('LP-remote', 'Remote playlist', 'VL-remote', 1, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO playlist_song_map (playlistId, songId, position, setVideoId)
                VALUES ('LP-remote', 'yt-track', 7, 'opaque-set-video-id')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO playlist_song_map (playlistId, songId, position, setVideoId)
                VALUES ('LP-remote', 'yt-track', 2, 'opaque-earlier-item')
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21).use { db ->
            assertEquals(
                "Network track",
                db.singleString("SELECT title FROM song WHERE id = 'yt-track'"),
            )
            assertEquals(
                "Remote playlist",
                db.singleString("SELECT name FROM playlist WHERE id = 'LP-remote'"),
            )

            db.query(
                """
                SELECT localSongId, syncState, duration, confidence
                FROM remote_track_mapping
                WHERE provider = 'YOUTUBE' AND remoteTrackId = 'yt-track'
                """.trimIndent()
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("yt-track", cursor.getString(0))
                assertEquals("LINKED", cursor.getString(1))
                assertEquals(213, cursor.getInt(2))
                assertEquals(1.0, cursor.getDouble(3), 0.0)
            }
            assertEquals(
                0,
                db.singleInt(
                    "SELECT COUNT(*) FROM remote_track_mapping WHERE localSongId = 'LS-local'"
                ),
            )

            db.query(
                """
                SELECT localPlaylistId, syncMode
                FROM remote_playlist_mapping
                WHERE provider = 'YOUTUBE' AND remotePlaylistId = 'VL-remote'
                """.trimIndent()
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("LP-remote", cursor.getString(0))
                assertEquals("ADD_ONLY", cursor.getString(1))
            }

            db.query(
                """
                SELECT membershipId, remoteTrackId, localSongId, position
                FROM provider_playlist_item
                WHERE provider = 'YOUTUBE' AND remotePlaylistId = 'VL-remote'
                ORDER BY position, membershipId
                """.trimIndent()
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("opaque-earlier-item", cursor.getString(0))
                assertEquals("yt-track", cursor.getString(1))
                assertEquals("yt-track", cursor.getString(2))
                assertEquals(2, cursor.getInt(3))
                check(cursor.moveToNext())
                assertEquals("opaque-set-video-id", cursor.getString(0))
                assertEquals("yt-track", cursor.getString(1))
                assertEquals("yt-track", cursor.getString(2))
                assertEquals(7, cursor.getInt(3))
                check(!cursor.moveToNext())
            }
        }
    }

    @Test
    fun migrate20To21_enforcesMappingForeignKeysAndIdempotency() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            db.execSQL(
                """
                INSERT INTO song (id, title, duration, isLocal, liked)
                VALUES ('yt-track', 'Network track', 213, 0, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO song (id, title, duration, isLocal, liked)
                VALUES ('LS-other', 'Other track', 213, 1, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO playlist (id, name, browseId, isEditable, isLocal)
                VALUES ('LP-remote', 'Remote playlist', 'VL-remote', 1, 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21).use { db ->
            // MigrationTestHelper exposes a raw SupportSQLiteDatabase connection. Enable FK
            // enforcement explicitly before verifying the ON DELETE behavior Room uses at runtime.
            db.setForeignKeyConstraintsEnabled(true)
            assertEquals(1, db.singleInt("PRAGMA foreign_keys"))

            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    """
                    INSERT INTO remote_track_mapping (
                        provider, remoteTrackId, localSongId, syncState
                    ) VALUES ('YOUTUBE', 'yt-track', 'LS-other', 'LINKED')
                    """.trimIndent()
                )
            }

            db.execSQL(
                """
                INSERT INTO provider_playlist_item (
                    provider, remotePlaylistId, membershipId, remoteTrackId, localSongId, position
                ) VALUES ('YOUTUBE', 'VL-remote', 'opaque-membership', 'yt-track', 'yt-track', 4)
                """.trimIndent()
            )
            db.execSQL("DELETE FROM song WHERE id = 'yt-track'")
            assertEquals(
                0,
                db.singleInt(
                    "SELECT COUNT(*) FROM remote_track_mapping WHERE remoteTrackId = 'yt-track'"
                ),
            )
            assertNull(
                db.singleNullableString(
                    "SELECT localSongId FROM provider_playlist_item WHERE membershipId = 'opaque-membership'"
                )
            )

            db.execSQL(
                """
                INSERT INTO sync_operation (
                    id, provider, operationType, entityType, payloadHash, idempotencyKey,
                    createdAt, updatedAt, attemptCount, state
                ) VALUES (
                    'operation-1', 'VK', 'ADD_TO_LIBRARY', 'TRACK', 'payload-hash',
                    'same-idempotency-key', 1, 1, 0, 'PENDING'
                )
                """.trimIndent()
            )
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    """
                    INSERT INTO sync_operation (
                        id, provider, operationType, entityType, payloadHash, idempotencyKey,
                        createdAt, updatedAt, attemptCount, state
                    ) VALUES (
                        'operation-2', 'VK', 'ADD_TO_LIBRARY', 'TRACK', 'payload-hash',
                        'same-idempotency-key', 2, 2, 0, 'PENDING'
                    )
                    """.trimIndent()
                )
            }

            db.execSQL("DELETE FROM playlist WHERE id = 'LP-remote'")
            assertEquals(
                0,
                db.singleInt(
                    "SELECT COUNT(*) FROM remote_playlist_mapping WHERE remotePlaylistId = 'VL-remote'"
                ),
            )
            assertEquals(
                0,
                db.singleInt(
                    "SELECT COUNT(*) FROM provider_playlist_item WHERE remotePlaylistId = 'VL-remote'"
                ),
            )
        }
    }

    private fun SupportSQLiteDatabase.singleInt(query: String): Int =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.singleString(query: String): String =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.singleNullableString(query: String): String? =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private companion object {
        const val TEST_DB = "migration-20-21-test"
    }
}
