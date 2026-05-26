package com.stash.feature.sync

import com.stash.core.data.blocklist.BlockSource
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadFailureType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FailedDownloadsViewModelTest {

    private val dao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val enqueuer: SingleTrackDownloadEnqueuer = mockk(relaxed = true)
    private val guard: BlocklistGuard = mockk(relaxed = true)
    private val vm by lazy {
        // dao.getFailedDownloads() is collected eagerly by the VM constructor's StateFlow.
        // Stub it before constructing the VM in each test so the stateIn pipeline has a source.
        coEvery { dao.getFailedDownloads() } returns flowOf(emptyList())
        FailedDownloadsViewModel(dao, trackDao, enqueuer, guard)
    }

    @Test fun `retry only enqueues when atomic claim succeeds`() = runTest {
        coEvery { dao.atomicallyClaimForRetry(42) } returns 1
        vm.retry(42)
        coVerify(exactly = 1) { enqueuer.enqueue(42) }
    }

    @Test fun `retry does not enqueue when row is no longer FAILED`() = runTest {
        coEvery { dao.atomicallyClaimForRetry(42) } returns 0
        vm.retry(42)
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test fun `retryGroup enqueues every claimed id`() = runTest {
        coEvery { dao.atomicallyClaimGroupForRetry(DownloadFailureType.AUTH_EXPIRED) } returns listOf(1L, 2L, 3L)
        vm.retryGroup(DownloadFailureType.AUTH_EXPIRED)
        coVerify(exactly = 1) { enqueuer.enqueue(1) }
        coVerify(exactly = 1) { enqueuer.enqueue(2) }
        coVerify(exactly = 1) { enqueuer.enqueue(3) }
    }

    @Test fun `retryAll enqueues every non-match claimed id`() = runTest {
        coEvery { dao.atomicallyClaimAllForRetry() } returns listOf(7L, 8L)
        vm.retryAll()
        coVerify(exactly = 1) { enqueuer.enqueue(7) }
        coVerify(exactly = 1) { enqueuer.enqueue(8) }
    }

    @Test fun `block resolves track then calls guard with FAILED_DOWNLOADS source`() = runTest {
        val track = mockk<TrackEntity>(relaxed = true)
        coEvery { trackDao.getById(7) } returns track
        vm.block(7)
        coVerify(exactly = 1) { guard.block(track, BlockSource.FAILED_DOWNLOADS) }
    }

    @Test fun `block is no-op when track not found`() = runTest {
        coEvery { trackDao.getById(99) } returns null
        vm.block(99)
        coVerify(exactly = 0) { guard.block(any(), any()) }
    }
}
