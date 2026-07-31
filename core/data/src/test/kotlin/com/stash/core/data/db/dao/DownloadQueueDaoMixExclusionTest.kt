package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * #368: mix membership must never make a track download-eligible, and must never
 * spare its queue row from a sweep.
 *
 * Three predicates enforce "download-eligible" and they disagreed:
 * [DownloadQueueDao.getUnqueuedTrackIds] and
 * [DownloadQueueDao.deleteOrphanedQueueEntries] excluded only STASH_MIX, and
 * [DownloadQueueDao.cancelDownloadsWithNoEnabledPlaylist] excluded no types at
 * all. So an auto-enabled DAILY_MIX kept its tracks download-eligible, and the
 * v0.9.85 sweep spared them because they sat in a sync_enabled playlist.
 *
 * One rule, applied at all three sites: a track is download-eligible only via a
 * membership in an active, sync-enabled, NON-mix playlist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoMixExclusionTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao

    private var dailyMixQueued = 0L
    private var stashMixQueued = 0L
    private var customQueued = 0L
    private var dailyMixUnqueued = 0L
    private var customUnqueued = 0L
    private var dailyMixManual = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
        trackDao = db.trackDao()
        playlistDao = db.playlistDao()

        // All three parents are sync_enabled + active. The mixes mimic the state
        // the old auto-enable produced and that existing installs still carry.
        val dailyMix = newPlaylist("Daily Mix 1", "spotify:playlist:dm", PlaylistType.DAILY_MIX)
        val stashMix = newPlaylist("Deep Cuts", "stash_mix_1", PlaylistType.STASH_MIX)
        val custom = newPlaylist("My Playlist", "spotify:playlist:mine", PlaylistType.CUSTOM)

        // Sync-created queue rows carry a sync_id — these are the phantom rows
        // the sweeps exist to drain.
        db.syncHistoryDao().insert(SyncHistoryEntity(id = 5L))
        dailyMixQueued = newTrack("A", dailyMix, queued = true, syncId = 5L)
        stashMixQueued = newTrack("B", stashMix, queued = true, syncId = 5L)
        customQueued = newTrack("C", custom, queued = true, syncId = 5L)
        dailyMixUnqueued = newTrack("D", dailyMix, queued = false)
        customUnqueued = newTrack("E", custom, queued = false)
        // A user's explicit Download tap on a mix-only track: manual partition
        // (sync_id NULL), no eligible parent playlist.
        dailyMixManual = newTrack("F", dailyMix, queued = true, syncId = null)
    }

    @After fun tearDown() { db.close() }

    @Test fun `daily-mix-only track is not requeue-eligible`() = runTest {
        val eligible = dao.getUnqueuedTrackIds(listOf(MusicSource.SPOTIFY.name))
        assertThat(eligible).doesNotContain(dailyMixUnqueued)
        assertThat(eligible).contains(customUnqueued)
    }

    @Test fun `orphan sweep evicts mix-only queue rows`() = runTest {
        dao.deleteOrphanedQueueEntries()

        assertThat(dao.getByTrackId(dailyMixQueued)).isNull()
        assertThat(dao.getByTrackId(stashMixQueued)).isNull()
        assertThat(dao.getByTrackId(customQueued)).isNotNull()
    }

    @Test fun `enabled-playlist sweep evicts mix-only queue rows`() = runTest {
        dao.cancelDownloadsWithNoEnabledPlaylist()

        assertThat(dao.getByTrackId(dailyMixQueued)).isNull()
        assertThat(dao.getByTrackId(stashMixQueued)).isNull()
        assertThat(dao.getByTrackId(customQueued)).isNotNull()
    }

    /**
     * The other half of "only download what the user wants": a Download tap is
     * the strongest signal of intent there is, so neither sweep may evict it.
     * Manual rows live in the sync_id NULL partition; the sweeps exist to drain
     * phantom rows that a *sync* created, not a user's explicit request. Without
     * this, tapping Download on a track that lives only in a mix silently
     * produced nothing — the same class as the search-tab downloads that vanish
     * on relaunch.
     */
    @Test fun `neither sweep evicts a manually requested download`() = runTest {
        dao.deleteOrphanedQueueEntries()
        assertThat(dao.getByTrackId(dailyMixManual)).isNotNull()

        dao.cancelDownloadsWithNoEnabledPlaylist()
        assertThat(dao.getByTrackId(dailyMixManual)).isNotNull()
    }

    private suspend fun newPlaylist(name: String, sourceId: String, type: PlaylistType): Long =
        playlistDao.insert(
            PlaylistEntity(
                name = name,
                source = MusicSource.SPOTIFY,
                sourceId = sourceId,
                type = type,
                syncEnabled = true,
                isActive = true,
            )
        )

    private suspend fun newTrack(
        tag: String,
        playlistId: Long,
        queued: Boolean,
        syncId: Long? = null,
    ): Long {
        val trackId = trackDao.insert(
            TrackEntity(
                title = "Track $tag",
                artist = "Artist $tag",
                canonicalTitle = "track ${tag.lowercase()}",
                canonicalArtist = "artist ${tag.lowercase()}",
                source = MusicSource.SPOTIFY,
                isDownloaded = false,
            )
        )
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = 0,
                addedAt = Instant.parse("2026-07-01T00:00:00Z"),
                removedAt = null,
            )
        )
        if (queued) {
            dao.insert(
                DownloadQueueEntity(
                    trackId = trackId,
                    syncId = syncId,
                    status = DownloadStatus.PENDING,
                    searchQuery = "Artist $tag - Track $tag",
                )
            )
        }
        return trackId
    }
}
