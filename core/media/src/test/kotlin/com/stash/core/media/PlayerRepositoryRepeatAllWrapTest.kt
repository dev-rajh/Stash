package com.stash.core.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ExoPlayer timeline is a bounded rolling window over the LOGICAL queue
 * (seeded with the tapped track + [BACKGROUND_FILL_LOOKAHEAD] ahead). Under
 * REPEAT_MODE_ALL, Media3's native next/previous seeks WRAP that window —
 * `hasNextMediaItem()` is true at the frontier and `seekToNextMediaItem()`
 * lands on timeline index 0, which is the first track of the window, NOT the
 * logical successor. Real-user symptom: rapid-skip 10 tracks and the 11th is
 * the first track you skipped.
 *
 * These tests pin the fix: native seeks are only taken when they do NOT
 * mis-wrap the partial window; otherwise the skip routes through the logical
 * queue. A genuinely fully-materialized timeline keeps the cheap native wrap.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryRepeatAllWrapTest {

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
    private val timeline: Timeline = mockk()

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
        // 30-track logical queue, ids 1..30.
        repo.currentQueueTracks = (1L..30L).map { Track(id = it, title = "t$it", artist = "a") }

        // Bounded window: 11 materialized items, current at the frontier
        // (timeline index 10 = last window in play order).
        every { timeline.getLastWindowIndex(false) } returns 10
        every { timeline.getFirstWindowIndex(false) } returns 0
        every { controller.currentTimeline } returns timeline
        every { controller.shuffleModeEnabled } returns false
        every { controller.repeatMode } returns Player.REPEAT_MODE_ALL
        every { controller.mediaItemCount } returns 11
    }

    private fun item(trackId: Long) = MediaItem.Builder()
        .setMediaId(trackId.toString())
        .setUri("https://x/$trackId")
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply { putLong(EXTRA_TRACK_ID, trackId) },
            ).build(),
        )
        .build()

    // ---- skipNext ----

    @Test
    fun `skipNext at the frontier under repeat-all routes logically instead of wrapping the window`() = runTest {
        every { controller.hasNextMediaItem() } returns true // true because repeat-all wraps
        every { controller.currentMediaItemIndex } returns 10
        every { controller.currentMediaItem } returns item(20) // logical index 19

        repo.skipNext()

        verify(exactly = 0) { controller.seekToNextMediaItem() }
        assertThat(repo.pendingNavIndex).isEqualTo(20)
    }

    @Test
    fun `skipNext under repeat-all seeks natively when the whole queue is materialized`() = runTest {
        every { controller.hasNextMediaItem() } returns true
        every { controller.currentMediaItemIndex } returns 10
        every { controller.mediaItemCount } returns 30 // full logical queue in the timeline

        repo.skipNext()

        verify(exactly = 1) { controller.seekToNextMediaItem() }
        assertThat(repo.pendingNavIndex).isNull()
    }

    @Test
    fun `skipNext under repeat-all mid-window still seeks natively`() = runTest {
        every { controller.hasNextMediaItem() } returns true
        every { controller.currentMediaItemIndex } returns 3 // not the frontier

        repo.skipNext()

        verify(exactly = 1) { controller.seekToNextMediaItem() }
        assertThat(repo.pendingNavIndex).isNull()
    }

    @Test
    fun `skipNext at the true end of the queue under repeat-all wraps logically to track 0`() = runTest {
        every { controller.hasNextMediaItem() } returns true
        every { controller.currentMediaItemIndex } returns 10
        every { controller.currentMediaItem } returns item(30) // logical index 29 = last

        repo.skipNext()

        verify(exactly = 0) { controller.seekToNextMediaItem() }
        assertThat(repo.pendingNavIndex).isEqualTo(0)
    }

    // ---- skipPrevious ----

    @Test
    fun `skipPrevious at the window start under repeat-all routes logically instead of jumping to the frontier`() = runTest {
        every { controller.hasPreviousMediaItem() } returns true // true because repeat-all wraps
        every { controller.currentMediaItemIndex } returns 0
        every { controller.currentMediaItem } returns item(20) // logical index 19

        repo.skipPrevious()

        verify(exactly = 0) { controller.seekToPreviousMediaItem() }
        assertThat(repo.pendingNavIndex).isEqualTo(18)
    }

    @Test
    fun `skipPrevious at logical track 0 under repeat-all wraps logically to the last track`() = runTest {
        every { controller.hasPreviousMediaItem() } returns true
        every { controller.currentMediaItemIndex } returns 0
        every { controller.currentMediaItem } returns item(1) // logical index 0

        repo.skipPrevious()

        verify(exactly = 0) { controller.seekToPreviousMediaItem() }
        assertThat(repo.pendingNavIndex).isEqualTo(29)
    }

    // ---- auto-advance wrap redirect ----

    @Test
    fun `auto-advance wrap under repeat-all redirects to the logical successor`() {
        repo.maybeRedirectRepeatAllWindowWrap(
            controller,
            oldIndex = 10,
            newIndex = 0,
            oldItem = item(20), // logical index 19
        )

        assertThat(repo.pendingNavIndex).isEqualTo(20)
    }

    @Test
    fun `auto-advance wrap with a fully materialized timeline is left alone`() {
        every { controller.mediaItemCount } returns 30

        repo.maybeRedirectRepeatAllWindowWrap(
            controller,
            oldIndex = 10,
            newIndex = 0,
            oldItem = item(20),
        )

        assertThat(repo.pendingNavIndex).isNull()
    }

    @Test
    fun `non-wrap auto-advance is not redirected`() {
        repo.maybeRedirectRepeatAllWindowWrap(
            controller,
            oldIndex = 4,
            newIndex = 5,
            oldItem = item(20),
        )

        assertThat(repo.pendingNavIndex).isNull()
    }

    // ---- shared wrap decision ----

    @Test
    fun `nativeSeekWouldMisWrap decision table`() {
        val player: Player = mockk()
        every { player.currentTimeline } returns timeline
        every { player.shuffleModeEnabled } returns false
        every { player.mediaItemCount } returns 11
        every { player.currentMediaItemIndex } returns 10
        every { player.repeatMode } returns Player.REPEAT_MODE_ALL

        // Repeat-all, partial window, at the frontier: forward seek mis-wraps.
        assertThat(nativeSeekWouldMisWrap(player, logicalCount = 30, forward = true)).isTrue()
        // Backward from the frontier is fine.
        assertThat(nativeSeekWouldMisWrap(player, logicalCount = 30, forward = false)).isFalse()

        // At the window start: backward seek mis-wraps, forward is fine.
        every { player.currentMediaItemIndex } returns 0
        assertThat(nativeSeekWouldMisWrap(player, logicalCount = 30, forward = false)).isTrue()
        assertThat(nativeSeekWouldMisWrap(player, logicalCount = 30, forward = true)).isFalse()

        // Fully materialized timeline: the native wrap is correct — never intercept.
        every { player.mediaItemCount } returns 30
        assertThat(nativeSeekWouldMisWrap(player, logicalCount = 30, forward = false)).isFalse()

        // Repeat OFF/ONE never wrap on navigation (Media3 treats ONE as OFF here).
        every { player.mediaItemCount } returns 11
        every { player.repeatMode } returns Player.REPEAT_MODE_OFF
        assertThat(nativeSeekWouldMisWrap(player, logicalCount = 30, forward = true)).isFalse()
        every { player.repeatMode } returns Player.REPEAT_MODE_ONE
        assertThat(nativeSeekWouldMisWrap(player, logicalCount = 30, forward = true)).isFalse()
    }
}
