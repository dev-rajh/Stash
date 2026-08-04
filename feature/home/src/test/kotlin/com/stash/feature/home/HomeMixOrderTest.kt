package com.stash.feature.home

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.MusicSource
import org.junit.Test

/**
 * Ordering contract for Home's mix rails.
 *
 * Home used to render them in the DAO's `ORDER BY p.name ASC`, so the head of
 * every rail was a fixed alphabetical prefix — on a real library the first cards
 * were "'00s R&B", "'70s Lite Hits", "'70s Rock" and stayed that way forever.
 * A sync could add hundreds of songs and Home looked identical, because the
 * mixes that actually changed sorted into the middle of an uncapped rail.
 *
 * These pin the replacement: freshest first, missing recency last, name as a
 * stable tie-break.
 */
class HomeMixOrderTest {

    private fun mix(id: Long, title: String) =
        HomeMix(id = id, title = title, artUrl = null, source = MusicSource.SPOTIFY, trackCount = 10)

    @Test
    fun `the mix that most recently gained a track leads the rail`() {
        val rail = listOf(
            mix(1, "'00s R&B"),      // alphabetically first, stalest
            mix(2, "Soul Mix"),
            mix(3, "Your Top Songs 2025"),
        )
        val recency = mapOf(1L to 1_000L, 2L to 5_000L, 3L to 9_000L)

        assertThat(rail.freshestFirst(recency).map { it.title })
            .containsExactly("Your Top Songs 2025", "Soul Mix", "'00s R&B")
            .inOrder()
    }

    /**
     * The reported symptom, directly: alphabetical order must NOT survive when
     * the alphabetically-first mix is the one nothing was added to.
     */
    @Test
    fun `alphabetically first mix does not lead when it is stale`() {
        val rail = listOf(mix(1, "'00s R&B"), mix(2, "Zydeco Mix"))
        val recency = mapOf(1L to 1L, 2L to 2L)

        assertThat(rail.freshestFirst(recency).first().title).isEqualTo("Zydeco Mix")
    }

    /** A mix with no live memberships has no recency row — it must not jump the queue. */
    @Test
    fun `mix with no recency sorts last`() {
        val rail = listOf(mix(1, "Empty Mix"), mix(2, "Soul Mix"))
        val recency = mapOf(2L to 5_000L)

        assertThat(rail.freshestFirst(recency).map { it.title })
            .containsExactly("Soul Mix", "Empty Mix")
            .inOrder()
    }

    /**
     * A sync writes many memberships in the same millisecond, so ties are the
     * common case, not an edge one. Without a tie-break the rail would reshuffle
     * on every emission.
     */
    @Test
    fun `equal timestamps fall back to name so the rail is stable`() {
        val rail = listOf(mix(1, "Soul Mix"), mix(2, "Ambient Mix"), mix(3, "Focus Mix"))
        val recency = mapOf(1L to 7_000L, 2L to 7_000L, 3L to 7_000L)

        assertThat(rail.freshestFirst(recency).map { it.title })
            .containsExactly("Ambient Mix", "Focus Mix", "Soul Mix")
            .inOrder()
    }
}
