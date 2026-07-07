package com.stash.core.media.sleep

import com.stash.core.media.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The fixed set of sleep-timer durations offered in the UI. */
enum class SleepTimerOption(val label: String, val durationMs: Long?) {
    FIVE_MINUTES("5 minutes", 5 * 60_000L),
    TEN_MINUTES("10 minutes", 10 * 60_000L),
    FIFTEEN_MINUTES("15 minutes", 15 * 60_000L),
    THIRTY_MINUTES("30 minutes", 30 * 60_000L),
    FORTY_FIVE_MINUTES("45 minutes", 45 * 60_000L),
    ONE_HOUR("1 hour", 60 * 60_000L),

    /** Stop after the currently-playing track finishes. */
    END_OF_TRACK("End of track", null),
}

/** Current sleep-timer status, observed by the UI. */
sealed interface SleepTimerState {
    data object Inactive : SleepTimerState

    /** A countdown is running; pauses playback at [endAtEpochMs]. */
    data class Timed(val endAtEpochMs: Long) : SleepTimerState

    /** Armed to pause once the current track ends. */
    data object EndOfTrack : SleepTimerState
}

/**
 * App-scoped sleep timer. Pauses playback after a chosen duration or at the
 * end of the current track. Lives as a singleton (not in a ViewModel) so the
 * countdown keeps running while the user navigates away from Now Playing, and
 * so the mini player and full screen observe the same timer.
 *
 * "End of track" is implemented by watching the player for a track change:
 * when the current track id / index changes (auto-advance), playback is paused
 * immediately. That stops within a moment of the current track ending — there
 * is no player-level "pause at boundary" hook to be more precise.
 */
@Singleton
class SleepTimerManager @Inject constructor(
    private val playerRepository: PlayerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var job: Job? = null

    /** Arm the timer for [option], replacing any timer already running. */
    fun schedule(option: SleepTimerOption) {
        cancel()
        val durationMs = option.durationMs
        job = if (durationMs == null) scheduleEndOfTrack() else scheduleTimed(durationMs)
    }

    /** Cancel any running timer and reset to [SleepTimerState.Inactive]. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState.Inactive
    }

    private fun scheduleTimed(durationMs: Long): Job {
        val endAt = System.currentTimeMillis() + durationMs
        _state.value = SleepTimerState.Timed(endAt)
        return scope.launch {
            // Re-emit each second so the UI can show a live countdown.
            while (System.currentTimeMillis() < endAt) {
                _state.value = SleepTimerState.Timed(endAt)
                delay(1_000)
            }
            playerRepository.pause()
            _state.value = SleepTimerState.Inactive
        }
    }

    private fun scheduleEndOfTrack(): Job {
        _state.value = SleepTimerState.EndOfTrack
        return scope.launch {
            playerRepository.playerState
                .map { it.currentTrack?.id to it.currentIndex }
                .distinctUntilChanged()
                .drop(1) // ignore the current track; wait for the next change
                .first()
            playerRepository.pause()
            _state.value = SleepTimerState.Inactive
        }
    }
}
