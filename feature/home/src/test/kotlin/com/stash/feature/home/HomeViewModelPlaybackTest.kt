package com.stash.feature.home

import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.tipjar.TipJarRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking

/**
 * Task 4 of the "Offline Stash Mix Visibility + Playback" plan: the
 * Home-screen single-mix play paths must honour the same Mix+connectivity
 * exemption that [com.stash.core.media.streaming.queuePlayableTracks]
 * encodes (already wired into PlaylistDetailViewModel).
 *
 * In Offline mode (streaming preference OFF) a STASH_MIX with a live
 * connection should still enqueue its stream-only members; with no
 * connection it falls back to downloaded-only; and a non-mix playlist
 * (CUSTOM) always falls back to downloaded-only regardless of connection.
 *
 * Mirrors the harness in :feature:library's MixOfflineTapGuardTest —
 * mockito-kotlin, StandardTestDispatcher, mock collaborators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPlaybackTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val downloaded = Track(
        id = 1L, title = "Local", artist = "A",
        isStreamable = true, isDownloaded = true,
        filePath = "/m/1.opus",
    )
    private val streamOnly = Track(
        id = 42L, title = "Cloud", artist = "A",
        isStreamable = true, isDownloaded = false, filePath = null,
    )

    @Test
    fun `playPlaylist offline + connected + mix enqueues downloaded and stream-only`() = runTest {
        val playlist = playlist(id = 7L, type = PlaylistType.STASH_MIX)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            connected = true,
        )

        vm.playPlaylist(playlist)
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L, 42L)
    }

    @Test
    fun `playPlaylist offline + disconnected + mix enqueues only downloaded`() = runTest {
        val playlist = playlist(id = 7L, type = PlaylistType.STASH_MIX)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            connected = false,
        )

        vm.playPlaylist(playlist)
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `playPlaylist offline + connected + custom playlist enqueues only downloaded`() = runTest {
        // Control: a non-mix playlist gets NO connectivity exemption.
        val playlist = playlist(id = 7L, type = PlaylistType.CUSTOM)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            connected = true,
        )

        vm.playPlaylist(playlist)
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `addPlaylistToQueue offline + connected + mix adds downloaded and stream-only`() = runTest {
        val playlist = playlist(id = 7L, type = PlaylistType.STASH_MIX)
        val playerRepo = mock<PlayerRepository>()
        val vm = buildVm(
            playlist = playlist,
            tracks = listOf(downloaded, streamOnly),
            playerRepository = playerRepo,
            connected = true,
        )

        vm.addPlaylistToQueue(playlist)
        runCurrent()

        val trackCaptor = argumentCaptor<Track>()
        verifyBlocking(playerRepo, times(2)) { addToQueue(trackCaptor.capture()) }
        assertThat(trackCaptor.allValues.map { it.id }).containsExactly(1L, 42L)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun playlist(id: Long, type: PlaylistType) = Playlist(
        id = id,
        name = "P",
        source = MusicSource.SPOTIFY,
        type = type,
    )

    private fun buildVm(
        playlist: Playlist,
        tracks: List<Track>,
        playerRepository: PlayerRepository,
        connected: Boolean,
    ): HomeViewModel {
        val musicRepo = mock<MusicRepository> {
            on { getTracksByPlaylist(playlist.id) } doReturn flowOf(tracks)
        }
        val streamingPreference = mock<StreamingPreference> {
            onBlocking { current() } doReturn false
        }
        val connectivity = mock<ConnectivityMonitor> {
            on { isConnected() } doReturn connected
        }
        // init {} block reads isStale() (suspend, primitive Boolean) — stub it
        // so the cold-start warm-up coroutine doesn't NPE on an unboxed null.
        val tipJar = mock<TipJarRepository> {
            onBlocking { isStale() } doReturn false
        }
        return HomeViewModel(
            musicRepository = musicRepo,
            playerRepository = playerRepository,
            lastFmSessionPreference = mock(),
            lastFmCredentials = mock(),
            listeningEventDao = mock(),
            losslessPrefs = mock(),
            settingsDeepLinkController = mock(),
            tipJarRepository = tipJar,
            recipeDao = mock(),
            discoveryQueueDao = mock(),
            downloadQueueDao = mock(),
            qobuzSource = mock(),
            aggregatorRateLimiter = mock(),
            downloadNetworkPreference = mock(),
            streamingPreference = streamingPreference,
            connectivityMonitor = connectivity,
            metadataBackfillState = mock(),
            lyricsBackfillState = mock(),
            context = mock(),
        )
    }
}
