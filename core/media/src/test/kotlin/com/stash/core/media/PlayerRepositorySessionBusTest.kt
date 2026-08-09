package com.stash.core.media

import android.os.Looper
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pins the repository half of the idle-stop handshake ([PlaybackSessionBus]).
 *
 * When the service announces it is stopping, the repository must release its
 * MediaController — that binding is what kept a stopped service (and the
 * process, and two ExoPlayers) alive around the clock. But releasing must
 * NOT wipe the last published player state: the mini player keeps showing
 * the paused track, and play() rebuilds from the persisted queue.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositorySessionBusTest {

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
    private val trackIdentityEvents: TrackIdentityEvents = mockk {
        every { changes } returns MutableSharedFlow()
    }
    private val bus = PlaybackSessionBus()

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
            playbackResumer = mockk(relaxed = true),
            radioGenerator = mockk(relaxed = true),
            trackIdentityEvents = trackIdentityEvents,
            playbackSessionBus = bus,
        )
        every { controller.isConnected } returns true
        repo.controllerDeferred = controller
    }

    @Test
    fun `service stopping releases the controller so the binding cannot pin the service`() = runTest {
        repo.onSessionAliveChanged(false)

        verify(exactly = 1) { controller.release() }
        assertThat(repo.controllerDeferred).isNull()
    }

    @Test
    fun `service stopping keeps the last player state for the UI`() = runTest {
        // Flush the init-time work while the seam mock is still installed —
        // draining AFTER the release would let the queued eager connect
        // build a real controller against Robolectric's fake binder.
        shadowOf(Looper.getMainLooper()).idle()
        val before = repo.playerState.value

        repo.onSessionAliveChanged(false)

        assertThat(repo.playerState.value).isEqualTo(before)
    }

    @Test
    fun `session alive with a live controller is a no-op`() = runTest {
        repo.onSessionAliveChanged(true)

        verify(exactly = 0) { controller.release() }
        assertThat(repo.controllerDeferred).isSameInstanceAs(controller)
    }

    @Test
    fun `the bus signal itself drives the release`() = runTest {
        // End-to-end through the init collector, not just the handler:
        // this is the wiring a green unit test failed to prove last time.
        bus.onServiceStopping()
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { controller.release() }
        assertThat(repo.controllerDeferred).isNull()
    }
}
