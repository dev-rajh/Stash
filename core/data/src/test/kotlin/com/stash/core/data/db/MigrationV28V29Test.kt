package com.stash.core.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v29 stores user-approved manual YouTube matches separately from the
 * mutable youtube_id field so future syncs can honor the user's choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV28V29Test {

    private val DB_NAME = "migration-v28v29-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v28 to v29 adds nullable pinned_youtube_video_id`() {
        helper.createDatabase(DB_NAME, 28).use { db ->
            db.insertTrackV28(id = 1L, youtubeId = "old-video")
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 29, true, StashDatabase.MIGRATION_28_29,
        )

        migrated.query("SELECT youtube_id, pinned_youtube_video_id FROM tracks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("old-video", c.getString(0))
            assertTrue("legacy rows should not become pinned automatically", c.isNull(1))
        }
    }

    @Test
    fun `pinned_youtube_video_id round-trips after migration`() {
        helper.createDatabase(DB_NAME, 28).use { db -> db.insertTrackV28(id = 1L) }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 29, true, StashDatabase.MIGRATION_28_29,
        )

        val cv = ContentValues().apply { put("pinned_youtube_video_id", "manual-video") }
        migrated.update("tracks", SQLiteDatabase.CONFLICT_REPLACE, cv, "id = 1", null)

        migrated.query("SELECT pinned_youtube_video_id FROM tracks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("manual-video", c.getString(0))
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertTrackV28(
        id: Long,
        youtubeId: String? = null,
    ) {
        val cv = ContentValues().apply {
            put("id", id)
            put("title", "Test Track $id")
            put("artist", "Test Artist")
            put("album", "")
            put("album_artist", "")
            put("duration_ms", 0L)
            put("file_format", "opus")
            put("quality_kbps", 0)
            put("file_size_bytes", 0L)
            put("source", "SPOTIFY")
            put("youtube_id", youtubeId)
            put("date_added", 0L)
            put("play_count", 0)
            put("is_downloaded", 0)
            put("canonical_title", "test track $id")
            put("canonical_artist", "test artist")
            put("match_confidence", 0f)
            put("match_dismissed", 0)
            put("match_flagged", 0)
            put("lastfm_user_loved", 0)
            put("is_streamable", 0)
            putNull("metadata_embedded_at")
            putNull("lyrics_fetched_at")
        }
        insert("tracks", SQLiteDatabase.CONFLICT_REPLACE, cv)
    }
}
