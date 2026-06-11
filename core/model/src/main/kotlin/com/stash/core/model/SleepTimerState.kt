package com.stash.core.model

/**
 * Snapshot of the player's sleep-timer state.
 *
 * @property isActive    Whether a timer is currently armed.
 * @property remainingMs Milliseconds left before playback pauses. Ticks down
 *                       while a fixed-duration timer is running; `0` for an
 *                       [endOfTrack] timer (which has no fixed deadline).
 * @property endOfTrack  When `true`, playback pauses once the currently-playing
 *                       track finishes rather than at a fixed time.
 */
data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingMs: Long = 0L,
    val endOfTrack: Boolean = false,
)
