package com.stash.data.download.matching

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.spotify.SpotifyTrackCandidate
import org.junit.Test

/**
 * Contract tests for [SpotifySearchScorer] — the bulletproof core of the
 * Spotify-URI resolver. The scorer's job is to accept the ONE candidate that
 * is safe to route to antra, or return null. A missed match is fine; a wrong
 * match (live/remaster/cover/sped-up/karaoke/alt-mix) is unacceptable.
 */
class SpotifySearchScorerTest {

    private val scorer = SpotifySearchScorer(TrackMatcher())

    private fun track(
        title: String,
        artist: String,
        durMs: Long?,
    ) = TrackQuery(artist = artist, title = title, durationMs = durMs)

    private fun cand(
        name: String,
        artists: List<String>,
        durMs: Long,
        id: String = "id",
    ) = SpotifyTrackCandidate(
        id = id,
        name = name,
        artists = artists,
        albumName = "Album",
        durationMs = durMs,
        isrc = null,
        explicit = false,
    )

    @Test
    fun `accepts exact title and artist within duration tolerance`() {
        val t = track(title = "Song", artist = "Artist", durMs = 200_000)
        val c = cand(name = "Song", artists = listOf("Artist"), durMs = 201_000)

        val decision = scorer.pick(t, listOf(c))

        assertThat(decision.accepted).isEqualTo(c)
    }

    @Test
    fun `rejects live within 4s via raw-title version veto`() {
        // titleSim is ~1.0 because canonicalTitle strips "- Live", so ONLY the
        // raw-title version veto can save this. SAME duration.
        val t = track(title = "Foxey Lady", artist = "Jimi Hendrix", durMs = 200_000)
        val c = cand(name = "Foxey Lady (Live)", artists = listOf("Jimi Hendrix"), durMs = 200_000)

        val decision = scorer.pick(t, listOf(c))

        assertThat(decision.accepted).isNull()
    }
}
