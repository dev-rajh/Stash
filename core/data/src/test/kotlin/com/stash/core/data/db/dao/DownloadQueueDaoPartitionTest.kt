package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoPartitionTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var syncHistoryDao: SyncHistoryDao

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
        syncHistoryDao = db.syncHistoryDao()

        // DownloadQueueEntity has FK sync_id → sync_history.id (Room enables
        // FK enforcement by default). Seed a single sync_history row so the
        // sync-side test fixtures can reference syncId = 5L without crashing.
        syncHistoryDao.insert(SyncHistoryEntity(id = 5L))
    }

    @After fun tearDown() { db.close() }

    @Test fun `getAllPendingBySources excludes rows with sync_id IS NULL`() = runTest {
        seedTrackInSyncEnabledPlaylist(trackId = 1L, source = MusicSource.YOUTUBE)
        seedTrackInSyncEnabledPlaylist(trackId = 2L, source = MusicSource.YOUTUBE)

        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))
        dao.insert(pendingRow(id = 101L, trackId = 2L, syncId = null))

        val result = dao.getAllPendingBySources(listOf("YOUTUBE"))

        assertEquals("expected only sync row", listOf(100L), result.map { it.id })
    }

    @Test fun `getRetryableBySources excludes rows with sync_id IS NULL`() = runTest {
        seedTrackInSyncEnabledPlaylist(trackId = 1L, source = MusicSource.YOUTUBE)
        seedTrackInSyncEnabledPlaylist(trackId = 2L, source = MusicSource.YOUTUBE)

        dao.insert(failedRow(id = 100L, trackId = 1L, syncId = 5L, retryCount = 1))
        dao.insert(failedRow(id = 101L, trackId = 2L, syncId = null, retryCount = 1))

        val result = dao.getRetryableBySources(listOf("YOUTUBE"))

        assertEquals("expected only sync row", listOf(100L), result.map { it.id })
    }

    @Test fun `pendingDiscoveryDownloads returns only sync_id IS NULL rows in PENDING or retryable FAILED`() = runTest {
        seedTrack(trackId = 1L)
        seedTrack(trackId = 2L)
        seedTrack(trackId = 3L)
        seedTrack(trackId = 4L)

        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))
        dao.insert(pendingRow(id = 101L, trackId = 2L, syncId = null))
        dao.insert(failedRow(id = 102L, trackId = 3L, syncId = null, retryCount = 1))
        dao.insert(failedRow(id = 103L, trackId = 4L, syncId = null, retryCount = 3))

        val result = dao.pendingDiscoveryDownloads()

        assertEquals(setOf(101L, 102L), result.map { it.id }.toSet())
    }

    @Test fun `pendingDiscoveryDownloads excludes WAITING_FOR_LOSSLESS and IN_PROGRESS`() = runTest {
        seedTrack(trackId = 1L)
        seedTrack(trackId = 2L)

        dao.insert(
            DownloadQueueEntity(
                id = 100L,
                trackId = 1L,
                status = DownloadStatus.WAITING_FOR_LOSSLESS,
                syncId = null,
                searchQuery = "q",
            )
        )
        dao.insert(
            DownloadQueueEntity(
                id = 101L,
                trackId = 2L,
                status = DownloadStatus.IN_PROGRESS,
                syncId = null,
                searchQuery = "q",
            )
        )

        val result = dao.pendingDiscoveryDownloads()

        assertTrue("expected empty, got $result", result.isEmpty())
    }

    // ---- STASH_MIX stream-only exclusion (v0.9.48) ----
    //
    // Stash Mixes are stream-only by design (v0.9.37 seam). Their playlists
    // are sync_enabled=1 so they stay visible offline, but their stub tracks
    // must NEVER be treated as download-eligible. "Download-eligible" =
    // member of a sync-enabled, NON-STASH_MIX playlist.

    @Test fun `getAllPendingBySources excludes a track whose only sync-enabled playlist is a Stash Mix`() = runTest {
        seedTrack(trackId = 1L)
        addPlaylistMembership(trackId = 1L, type = PlaylistType.STASH_MIX, syncEnabled = true)
        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))

        val result = dao.getAllPendingBySources(listOf("YOUTUBE"))

        assertTrue("mix-only track must not drain, got $result", result.isEmpty())
    }

    @Test fun `getAllPendingBySources includes a track in both a Stash Mix and a non-mix playlist`() = runTest {
        seedTrack(trackId = 1L)
        addPlaylistMembership(trackId = 1L, type = PlaylistType.STASH_MIX, syncEnabled = true)
        addPlaylistMembership(trackId = 1L, type = PlaylistType.LIKED_SONGS, syncEnabled = true)
        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))

        val result = dao.getAllPendingBySources(listOf("YOUTUBE"))

        assertEquals("overlap track in Liked Songs must still download", listOf(100L), result.map { it.id })
    }

    @Test fun `getRetryableBySources excludes a track whose only sync-enabled playlist is a Stash Mix`() = runTest {
        seedTrack(trackId = 1L)
        addPlaylistMembership(trackId = 1L, type = PlaylistType.STASH_MIX, syncEnabled = true)
        dao.insert(failedRow(id = 100L, trackId = 1L, syncId = 5L, retryCount = 1))

        val result = dao.getRetryableBySources(listOf("YOUTUBE"))

        assertTrue("mix-only retry row must not resurface, got $result", result.isEmpty())
    }

    @Test fun `getUnqueuedTrackIds excludes Stash-Mix-only tracks but keeps non-mix tracks`() = runTest {
        seedTrack(trackId = 1L) // mix-only
        addPlaylistMembership(trackId = 1L, type = PlaylistType.STASH_MIX, syncEnabled = true)
        seedTrack(trackId = 2L) // genuine library track
        addPlaylistMembership(trackId = 2L, type = PlaylistType.LIKED_SONGS, syncEnabled = true)

        val result = dao.getUnqueuedTrackIds(listOf("YOUTUBE"))

        assertEquals("only the non-mix track should be re-queued", listOf(2L), result)
    }

    @Test fun `deleteOrphanedQueueEntries evicts a Stash-Mix-only track's queue row`() = runTest {
        seedTrack(trackId = 1L)
        addPlaylistMembership(trackId = 1L, type = PlaylistType.STASH_MIX, syncEnabled = true)
        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))

        val deleted = dao.deleteOrphanedQueueEntries()

        assertEquals("mix-only queue row should be swept", 1, deleted)
        assertTrue(dao.getAllPendingBySources(listOf("YOUTUBE")).isEmpty())
    }

    @Test fun `deleteOrphanedQueueEntries spares a track in both a Stash Mix and a non-mix playlist`() = runTest {
        seedTrack(trackId = 1L)
        addPlaylistMembership(trackId = 1L, type = PlaylistType.STASH_MIX, syncEnabled = true)
        addPlaylistMembership(trackId = 1L, type = PlaylistType.LIKED_SONGS, syncEnabled = true)
        dao.insert(pendingRow(id = 100L, trackId = 1L, syncId = 5L))

        val deleted = dao.deleteOrphanedQueueEntries()

        assertEquals("overlap track must keep its queue row", 0, deleted)
    }

    // ---- helpers ----

    private suspend fun seedTrack(trackId: Long) {
        trackDao.insert(
            TrackEntity(
                id = trackId,
                title = "Track $trackId",
                artist = "Artist $trackId",
                canonicalTitle = "track $trackId",
                canonicalArtist = "artist $trackId",
                source = MusicSource.YOUTUBE,
                isDownloaded = false,
            )
        )
    }

    private suspend fun seedTrackInSyncEnabledPlaylist(trackId: Long, source: MusicSource) {
        trackDao.insert(
            TrackEntity(
                id = trackId,
                title = "Track $trackId",
                artist = "Artist $trackId",
                canonicalTitle = "track $trackId",
                canonicalArtist = "artist $trackId",
                source = source,
                isDownloaded = false,
            )
        )
        // A genuine downloadable playlist (imported Liked Songs). NOT a
        // STASH_MIX — mix tracks are stream-only and must be excluded from
        // the download-eligibility queries (see the STASH_MIX exclusion
        // tests below).
        addPlaylistMembership(trackId = trackId, type = PlaylistType.LIKED_SONGS, syncEnabled = true)
    }

    /**
     * Link [trackId] into a fresh playlist of [type]. Unique sourceId per
     * (track, type) so a single track can sit in several playlists at once
     * (e.g. both a Stash Mix and Liked Songs) for the overlap tests.
     */
    private suspend fun addPlaylistMembership(
        trackId: Long,
        type: PlaylistType,
        syncEnabled: Boolean,
    ) {
        val playlistId = playlistDao.insert(
            PlaylistEntity(
                name = "$type playlist $trackId",
                source = MusicSource.BOTH,
                sourceId = "playlist_${type}_$trackId",
                type = type,
                trackCount = 0,
                syncEnabled = syncEnabled,
                isActive = true,
            )
        )
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = 0,
                addedAt = Instant.EPOCH,
            )
        )
    }

    private fun pendingRow(id: Long, trackId: Long, syncId: Long?) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.PENDING,
        syncId = syncId,
        searchQuery = "artist - title",
    )

    private fun failedRow(id: Long, trackId: Long, syncId: Long?, retryCount: Int) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.FAILED,
        syncId = syncId,
        searchQuery = "artist - title",
        retryCount = retryCount,
    )
}
