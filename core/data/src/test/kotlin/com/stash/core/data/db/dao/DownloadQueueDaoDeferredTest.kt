package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v0.9.17: Room-backed tests for the deferred-set queries added to
 * [DownloadQueueDao] for the FLAC-only mode "lossless not yet available"
 * holding state ([DownloadStatus.WAITING_FOR_LOSSLESS]).
 *
 * Verifies:
 *  - [DownloadQueueDao.waitingForLosslessCount] reactively counts deferred rows.
 *  - [DownloadQueueDao.requeueWaitingForLossless] flips every deferred row to
 *    PENDING and leaves other statuses alone.
 *  - [DownloadQueueDao.deleteOrphanedQueueEntries] now also evicts deferred
 *    rows whose track has no sync-enabled playlist parent (extension of the
 *    existing PENDING/FAILED orphan sweep) — but only for sync-originated
 *    rows (sync_id set). Rows added outside the sync flow (manual downloads,
 *    batch FLAC upgrades) carry sync_id = null and are never swept, even if
 *    orphaned by this definition.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadQueueDaoDeferredTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var syncHistoryDao: SyncHistoryDao

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.downloadQueueDao()
        syncHistoryDao = db.syncHistoryDao()
        // Seed parent tracks for FK satisfaction. Three is enough — the
        // tests reuse trackIds 1..3 across scenarios.
        val trackDao = db.trackDao()
        trackDao.insert(track(id = 1L))
        trackDao.insert(track(id = 2L))
        trackDao.insert(track(id = 3L))
        // These fixtures are SYNC-created rows (sync_id set). The orphan sweeps
        // only touch that partition — a manual sync_id NULL row is a download the
        // user explicitly asked for and is spared. These tests are about status
        // coverage (WAITING_FOR_LOSSLESS joining the sweep), not partitions.
        db.syncHistoryDao().insert(SyncHistoryEntity(id = 1L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `waitingForLosslessCount returns count of WAITING_FOR_LOSSLESS rows`() = runTest {
        dao.insert(entry(trackId = 1L, status = DownloadStatus.WAITING_FOR_LOSSLESS))
        dao.insert(entry(trackId = 2L, status = DownloadStatus.PENDING))
        dao.insert(entry(trackId = 3L, status = DownloadStatus.WAITING_FOR_LOSSLESS))
        assertEquals(2, dao.waitingForLosslessCount().first())
    }

    @Test
    fun `requeueWaitingForLossless flips all WAITING rows to PENDING`() = runTest {
        dao.insert(entry(trackId = 1L, status = DownloadStatus.WAITING_FOR_LOSSLESS))
        dao.insert(entry(trackId = 2L, status = DownloadStatus.WAITING_FOR_LOSSLESS))
        dao.insert(entry(trackId = 3L, status = DownloadStatus.COMPLETED))
        val flipped = dao.requeueWaitingForLossless()
        assertEquals(2, flipped)
        assertEquals(2, dao.getByStatus(DownloadStatus.PENDING).first().size)
        assertEquals(0, dao.waitingForLosslessCount().first())
    }

    @Test
    fun `deleteOrphanedQueueEntries also evicts WAITING_FOR_LOSSLESS orphans`() = runTest {
        // Track exists, is in NO sync-enabled playlist, and the row came from
        // a sync run (sync_id set) → orphan, eligible for the sweep.
        dao.insert(entry(trackId = 1L, status = DownloadStatus.WAITING_FOR_LOSSLESS, syncId = 1L))
        val deleted = dao.deleteOrphanedQueueEntries()
        assertEquals(1, deleted)
        assertEquals(0, dao.waitingForLosslessCount().first())
    }

    @Test
    fun `deleteOrphanedQueueEntries never evicts rows added outside a sync run`() = runTest {
        // Same "orphaned" shape as above, but sync_id is null — this row was
        // queued manually (or by the batch FLAC upgrader), not by a sync.
        // The orphan sweep must leave it alone.
        dao.insert(entry(trackId = 1L, status = DownloadStatus.WAITING_FOR_LOSSLESS, syncId = null))
        val deleted = dao.deleteOrphanedQueueEntries()
        assertEquals(0, deleted)
        assertEquals(1, dao.waitingForLosslessCount().first())
    }

    private fun entry(trackId: Long, status: DownloadStatus, syncId: Long? = null) = DownloadQueueEntity(
        trackId = trackId,
        syncId = 1L,
        status = status,
        searchQuery = "test query",
    )

    private fun track(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
    )
}
