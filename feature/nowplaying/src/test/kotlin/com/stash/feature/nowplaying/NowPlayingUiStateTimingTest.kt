package com.stash.feature.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingUiStateTimingTest {

    @Test
    fun `progressFraction is zero when duration is zero`() {
        val state = NowPlayingUiState(
            currentPositionMs = 42_000L,
            durationMs = 0L,
        )

        assertEquals(0f, state.progressFraction)
    }

    @Test
    fun `progressFraction clamps to one when position exceeds duration`() {
        val state = NowPlayingUiState(
            currentPositionMs = 240_000L,
            durationMs = 120_000L,
        )

        assertEquals(1f, state.progressFraction)
    }
}
