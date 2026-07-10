package com.stash.core.media

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.radio.RadioSeed
import com.stash.core.data.radio.RadioSession
import com.stash.core.data.radio.RadioStationGenerator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerRepositoryRadioTest {

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
    private val radioGenerator: RadioStationGenerator = mockk()

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
            radioGenerator = radioGenerator,
        )
        repo.controllerDeferred = controller
    }

    private fun track(id: Long) = Track(id = id, title = "t$id", artist = "a", youtubeId = "v$id", isStreamable = true)

    @Test fun `startRadio returns false and does not arm when streaming is off`() = runTest {
        coEvery { streamingPreference.current() } returns false

        val started = repo.startRadio(RadioSeed.Artist("My Bloody Valentine", "id"))

        assertThat(started).isFalse()
        assertThat(repo.radioSeedLabel.value).isNull()
        coVerify(exactly = 0) { radioGenerator.start(any()) }
    }

    @Test fun `startRadio sets queue, plays, arms, and exposes the seed label`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(track(1), track(2)))

        val started = repo.startRadio(RadioSeed.Artist("My Bloody Valentine", "id"))

        assertThat(started).isTrue()
        verify { controller.setMediaItems(any<List<MediaItem>>(), 0, 0L) }
        verify { controller.play() }
        assertThat(repo.radioSeedLabel.value).isEqualTo("My Bloody Valentine")
    }

    @Test fun `startRadio returns false when the seed yields an empty batch`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to emptyList())

        assertThat(repo.startRadio(RadioSeed.Song("t", "a"))).isFalse()
        assertThat(repo.radioSeedLabel.value).isNull()
    }

    @Test fun `setQueue disarms the station`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val session = mockk<RadioSession>(relaxed = true)
        coEvery { radioGenerator.start(any()) } returns (session to listOf(track(1)))
        repo.startRadio(RadioSeed.Artist("MBV", "id"))
        assertThat(repo.radioSeedLabel.value).isEqualTo("MBV")

        repo.setQueue(listOf(track(9)))

        assertThat(repo.radioSeedLabel.value).isNull()
    }
}
