package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking

/**
 * Local-first playback contract for ordinary (non-mix) playlists.
 *
 * A track that is part of the playlist but NOT present in local storage
 * (isDownloaded = false) is shown but dimmed/disabled in the UI. The ViewModel
 * mirrors that: tapping such a row must not enqueue anything, and Play All /
 * Shuffle skip it — even when streaming mode is on. (Daily / Stash Mixes keep
 * the streaming behaviour; see [MixOfflineTapGuardTest].)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailLocalFirstTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `playTrack on non-downloaded row in custom playlist does not enqueue`() = runTest {
        val streamable = Track(
            id = 42L, title = "Cloud", artist = "A",
            isStreamable = true, isDownloaded = false, filePath = null,
        )
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        }
        // Streaming ON — proves the local-first gate ignores streaming mode for
        // ordinary playlists (unlike a Stash Mix, which would stream this row).
        val onlinePref = mock<StreamingPreference> { onBlocking { current() } doReturn true }
        val vm = buildVm(
            tracks = listOf(streamable),
            playlist = customPlaylist(),
            playerRepository = playerRepo,
            streamingPreference = onlinePref,
        )

        val uiJob = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playTrack(trackId = 42L)
        runCurrent()

        verifyBlocking(playerRepo, never()) { setQueue(any(), any()) }
        uiJob.cancel()
    }

    @Test
    fun `playAll in custom playlist enqueues only downloaded tracks`() = runTest {
        val downloaded = Track(
            id = 1L, title = "Local", artist = "A",
            isStreamable = true, isDownloaded = true,
            filePath = "/storage/emulated/0/Music/local.opus",
        )
        val streamable = Track(
            id = 42L, title = "Cloud", artist = "A",
            isStreamable = true, isDownloaded = false, filePath = null,
        )
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        }
        val onlinePref = mock<StreamingPreference> { onBlocking { current() } doReturn true }
        val vm = buildVm(
            tracks = listOf(downloaded, streamable),
            playlist = customPlaylist(),
            playerRepository = playerRepo,
            streamingPreference = onlinePref,
        )

        val uiJob = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playAll()
        runCurrent()

        val queueCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepo) { setQueue(queueCaptor.capture(), any()) }
        assertThat(queueCaptor.firstValue.map { it.id }).containsExactly(1L)
        uiJob.cancel()
    }

    // ------------------------------------------------------------------

    private fun customPlaylist() = Playlist(
        id = 7L,
        name = "My Playlist",
        source = MusicSource.SPOTIFY,
        type = PlaylistType.CUSTOM,
    )

    private fun buildVm(
        tracks: List<Track>,
        playlist: Playlist,
        playerRepository: PlayerRepository,
        streamingPreference: StreamingPreference,
    ): PlaylistDetailViewModel {
        val musicRepo = mock<MusicRepository> {
            on { getTracksByPlaylist(playlist.id) } doReturn flowOf(tracks)
            on { getAllPlaylistTracks(playlist.id) } doReturn flowOf(tracks)
            onBlocking { getPlaylistWithTracks(playlist.id) } doReturn playlist
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        }
        return PlaylistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("playlistId" to playlist.id)),
            musicRepository = musicRepo,
            playerRepository = playerRepository,
            playlistImageHelper = mock(),
            streamingPreference = streamingPreference,
            connectivityMonitor = mock<ConnectivityMonitor> { on { isConnected() } doReturn true },
            recipeDao = mock<StashMixRecipeDao> { on { observeAll() } doReturn flowOf(emptyList()) },
            discoveryQueueDao = mock<DiscoveryQueueDao> {
                on { observeNonFailedCountsByRecipe() } doReturn flowOf(emptyList())
            },
        )
    }
}
