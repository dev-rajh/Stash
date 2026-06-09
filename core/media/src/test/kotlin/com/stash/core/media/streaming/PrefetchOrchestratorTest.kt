package com.stash.core.media.streaming

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [PrefetchOrchestrator] fires a *speculative* resolve for the next queue
 * item ahead of auto-advance. Speculation must never spend antra quota
 * (1 single per resolve, 60-120s exclusive job slot), so the resolve goes
 * out with `allowAntra = false` — antra is reserved for the track the
 * user is actually playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrefetchOrchestratorTest {

    private val streamingPreference: StreamingPreference = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val trackDao: TrackDao = mockk()

    private fun orchestrator() = PrefetchOrchestrator(
        streamingPreference = streamingPreference,
        streamResolver = streamResolver,
        streamUrlCache = streamUrlCache,
        trackDao = trackDao,
    )

    @Test
    fun prefetch_resolves_with_allowAntra_false() = runTest {
        coEvery { streamingPreference.current() } returns true
        every { streamUrlCache.get(5L) } returns null
        val track = TrackEntity(
            id = 5L,
            title = "Next Up",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            youtubeId = "abc123",
            isDownloaded = false,
            isStreamable = true,
        )
        coEvery { trackDao.getById(5L) } returns track
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, allowAntra = false)
        } returns null

        orchestrator().onPlaybackProgress(
            scope = this,
            nextTrackId = 5L,
            positionMs = 70_000L,
            durationMs = 100_000L,
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = true, allowAntra = false)
        }
    }
}
