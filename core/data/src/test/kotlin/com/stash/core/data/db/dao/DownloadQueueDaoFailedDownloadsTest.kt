package com.stash.core.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoFailedDownloadsTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var trackDao: TrackDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.downloadQueueDao()
        trackDao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    // ---- atomicallyClaimForRetry ----------------------------------

    @Test fun atomicallyClaimForRetry_returns_1_when_row_is_FAILED() = runTest {
        seedTrack(id = 1, title = "Auth failure", artist = "X")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        val affected = dao.atomicallyClaimForRetry(1)
        assertEquals(1, affected)
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.PENDING, row.status)
        assertNull(row.errorMessage)
        assertEquals(DownloadFailureType.NONE, row.failureType)
    }

    @Test fun atomicallyClaimForRetry_returns_0_when_row_is_PENDING() = runTest {
        seedTrack(id = 1, title = "Auth failure", artist = "X")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        dao.atomicallyClaimForRetry(1)              // first claim succeeds
        val affected = dao.atomicallyClaimForRetry(1) // second claim no-ops
        assertEquals(0, affected)
    }

    // ---- markFailed ------------------------------------------------

    @Test fun markFailed_writes_status_and_failureType_and_completedAt() = runTest {
        seedTrack(id = 1, title = "Pending track", artist = "X")
        seedPendingQueueRow(queueId = 1, trackId = 1)
        dao.markFailed(queueId = 1, errorMessage = "boom", failureType = DownloadFailureType.NETWORK)
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals("boom", row.errorMessage)
        assertEquals(DownloadFailureType.NETWORK, row.failureType)
        assertNotNull(row.completedAt)
    }

    // ---- getFailedDownloads ---------------------------------------

    @Test fun getFailedDownloads_excludes_NONE_and_NO_MATCH() = runTest {
        seedTrack(id = 1, title = "Auth failure", artist = "X")
        seedTrack(id = 2, title = "No match", artist = "Y")
        seedTrack(id = 3, title = "Pending", artist = "Z")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        seedFailedQueueRow(queueId = 2, trackId = 2, type = DownloadFailureType.NO_MATCH)
        seedPendingQueueRow(queueId = 3, trackId = 3)  // NONE
        val rows = dao.getFailedDownloads().first()
        assertEquals(1, rows.size)
        assertEquals(1L, rows[0].queueId)
        assertEquals(DownloadFailureType.AUTH_EXPIRED, rows[0].failureType)
    }

    // ---- Helpers --------------------------------------------------

    private suspend fun seedTrack(id: Long, title: String, artist: String) {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                source = MusicSource.YOUTUBE,
                isDownloaded = false,
            )
        )
    }

    private suspend fun seedFailedQueueRow(queueId: Long, trackId: Long, type: DownloadFailureType) {
        dao.insert(
            DownloadQueueEntity(
                id = queueId,
                trackId = trackId,
                status = DownloadStatus.FAILED,
                syncId = null,
                searchQuery = "q",
                failureType = type,
                errorMessage = "previous error",
            )
        )
    }

    private suspend fun seedPendingQueueRow(queueId: Long, trackId: Long) {
        dao.insert(
            DownloadQueueEntity(
                id = queueId,
                trackId = trackId,
                status = DownloadStatus.PENDING,
                syncId = null,
                searchQuery = "q",
                failureType = DownloadFailureType.NONE,
            )
        )
    }
}
