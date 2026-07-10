package com.stash.core.data.radio

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmSimilarTrack
import com.stash.core.data.lastfm.LastFmTopTrack
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationGeneratorSongTest {
    private val lastFm: LastFmApiClient = mockk()
    private val yt: YTMusicApiClient = mockk()
    private fun gen() = RadioStationGenerator(lastFm, yt)

    @Test fun `song seed blends seed-artist tracks with similar tracks, resolves via search`() = runTest {
        coEvery { lastFm.getSimilarTracks("Lil Wayne", "A Milli", any()) } returns
            Result.success(listOf(
                LastFmSimilarTrack("Drake", "Forever", 0.9f),
                LastFmSimilarTrack("T.I.", "Whatever You Like", 0.7f)))
        coEvery { lastFm.getArtistTopTracks("Lil Wayne", any()) } returns
            Result.success(listOf(
                LastFmTopTrack("Lil Wayne", "6 Foot 7 Foot", 100),
                LastFmTopTrack("Lil Wayne", "Lollipop", 90)))
        coEvery { yt.searchCanonicalVideoId(any(), any()) } answers
            { "vid_${firstArg<String>()}_${secondArg<String>()}".replace(" ", "") }

        val (session, batch) = gen().start(RadioSeed.Song("A Milli", "Lil Wayne"))

        assertTrue(batch.isNotEmpty())
        assertTrue(batch.all { it.youtubeId != null })
        assertTrue("has seed share", batch.any { it.artist == "Lil Wayne" })
        assertTrue("has similar tracks", batch.any { it.artist != "Lil Wayne" })
        assertTrue(session.played.isNotEmpty())
    }
}
