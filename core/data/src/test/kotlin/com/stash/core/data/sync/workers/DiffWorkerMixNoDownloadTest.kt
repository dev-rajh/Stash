package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #368: an auto-discovered algorithmic mix must never enqueue downloads, even in
 * Offline mode.
 *
 * The guard [shouldEnqueueForDownload] excluded DAILY_MIX from the day it was
 * written, and was unit-tested in ShouldEnqueueForDownloadTest — but DiffWorker's
 * enqueue site tested raw `!streamingMode`, so the guard was never consulted and
 * every track of every rotating Spotify/YT mix was queued. Users reported 6000+
 * unwanted downloads.
 *
 * These tests therefore run the WORKER and observe what reaches the DAO. A
 * helper-level test cannot catch this class of bug: it passes whether or not
 * anything calls the helper.
 *
 * Fixture mirrors DiffWorkerTest (Robolectric + in-memory Room + mockk DAOs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerMixNoDownloadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase

    private val remoteSnapshotDao = mockk<com.stash.core.data.db.dao.RemoteSnapshotDao>()
    private val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
    private val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true)
    private val syncStateManager = mockk<SyncStateManager>(relaxed = true)
    private val musicRepository = mockk<MusicRepository>(relaxed = true)
    private val syncPreferencesManager = mockk<SyncPreferencesManager>()
    private val blocklistGuard = mockk<BlocklistGuard>()
    private val streamingPreference = mockk<StreamingPreference>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coEvery { blocklistGuard.isBlocked(any(), any(), any(), any()) } returns false
        // Offline mode — the mode in which a mix used to pull its whole contents.
        coEvery { streamingPreference.current() } returns false
        every { syncPreferencesManager.spotifySyncMode } returns flowOf(SyncMode.ACCUMULATE)
        every { syncPreferencesManager.youtubeSyncMode } returns flowOf(SyncMode.ACCUMULATE)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `daily mix enqueues nothing in offline mode`() = runBlocking {
        // sync_enabled = true is the state defaultSyncEnabled used to produce
        // automatically for a discovered mix, and the state existing installs
        // still carry.
        seedPlaylistAndSnapshot(PlaylistType.DAILY_MIX, "spotify:playlist:dm", "Daily Mix 1")

        buildWorker().doWork()

        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
    }

    /**
     * Control: the fix must be "mixes don't download", not "nothing downloads".
     * Without this, disabling all enqueueing would pass the test above.
     */
    @Test
    fun `custom playlist still enqueues in offline mode`() = runBlocking {
        seedPlaylistAndSnapshot(PlaylistType.CUSTOM, "spotify:playlist:mine", "My Playlist")

        buildWorker().doWork()

        coVerify(atLeast = 1) { downloadQueueDao.insertAll(any()) }
    }

    /** Seeds a sync-enabled local playlist plus a remote snapshot of 3 new tracks. */
    private suspend fun seedPlaylistAndSnapshot(
        type: PlaylistType,
        sourceId: String,
        name: String,
    ) {
        db.playlistDao().insert(
            PlaylistEntity(
                name = name,
                source = MusicSource.SPOTIFY,
                sourceId = sourceId,
                type = type,
                syncEnabled = true,
            )
        )

        val snapshotId = 7L
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(1L) } returns listOf(
            RemotePlaylistSnapshotEntity(
                id = snapshotId,
                syncId = 1L,
                source = MusicSource.SPOTIFY,
                sourcePlaylistId = sourceId,
                playlistName = name,
                playlistType = type,
            )
        )
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(snapshotId) } returns (0 until 3).map { i ->
            RemoteTrackSnapshotEntity(
                syncId = 1L,
                snapshotPlaylistId = snapshotId,
                title = "Track $i",
                artist = "Artist $i",
                spotifyUri = "spotify:track:new$i",
                position = i,
            )
        }
    }

    private fun buildWorker(): DiffWorker = TestListenableWorkerBuilder<DiffWorker>(context)
        .setInputData(workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to 1L))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = DiffWorker(
                appContext, workerParameters,
                database = db,
                remoteSnapshotDao = remoteSnapshotDao,
                trackDao = db.trackDao(),
                playlistDao = db.playlistDao(),
                downloadQueueDao = downloadQueueDao,
                syncHistoryDao = syncHistoryDao,
                trackMatcher = TrackMatcher(),
                syncStateManager = syncStateManager,
                musicRepository = musicRepository,
                syncPreferencesManager = syncPreferencesManager,
                blocklistGuard = blocklistGuard,
                streamingPreference = streamingPreference,
            )
        })
        .build()
}
