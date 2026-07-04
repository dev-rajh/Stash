package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Full-timeline queue: setQueue hands ExoPlayer EVERY playable track as a
 * MediaItem immediately (stream tracks as stash-resolve:// placeholders), so
 * native next/prev/repeat/shuffle operate on the whole queue and the old
 * rolling-window machinery (fill window, pending-nav skip chain, end-of-
 * timeline recovery, repeat-all wrap patches) is gone.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryFullTimelineTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk(relaxed = true)
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val controller: MediaController = mockk(relaxed = true)

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
        )
        repo.controllerDeferred = controller
    }

    @Test
    fun `setQueue materializes the whole queue as media items immediately`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val tracks = (1L..57L).map { Track(id = it, title = "t$it", artist = "a") }
        val items = slot<List<MediaItem>>()
        every { controller.setMediaItems(capture(items), any<Int>(), any<Long>()) } returns Unit

        repo.setQueue(tracks, startIndex = 0)

        assertThat(items.captured).hasSize(57)
        assertThat(items.captured[5].localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
        assertThat(items.captured[5].mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)).isEqualTo(6L)
    }

    @Test
    fun `skipNext is always a native seek`() = runTest {
        every { controller.hasNextMediaItem() } returns true

        repo.skipNext()

        verify { controller.seekToNextMediaItem() }
    }

    @Test
    fun `skipPrevious is always a native seek`() = runTest {
        every { controller.hasPreviousMediaItem() } returns true

        repo.skipPrevious()

        verify { controller.seekToPreviousMediaItem() }
    }

    @Test
    fun `setQueue starts playback at the tapped index`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val tracks = (1L..20L).map { Track(id = it, title = "t$it", artist = "a") }

        repo.setQueue(tracks, startIndex = 7)

        verify { controller.setMediaItems(any(), 7, 0L) }
        verify { controller.prepare() }
        verify { controller.play() }
    }
}
