package com.stash.core.media.service

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.data.prefs.CrossfadePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives an equal-power crossfade on **automatic** track-end → next-track
 * transitions only ("A owns the queue, B fades the tail").
 *
 * Player A ([playerA]) is the existing service player and stays the sole
 * MediaSession/queue owner. Player B is a transient, controller-invisible
 * second [ExoPlayer], built lazily via [buildPlayerB] on first fade and pooled
 * thereafter. When a fade arms, B takes over the **outgoing** track's tail
 * (seeked to A's current position) and ramps down while A jumps to the
 * incoming track at volume 0 and ramps up. On completion B is stopped and
 * returned to the pool with A at full volume on the new track.
 *
 * Every condition is re-checked each [onProgress] tick via the pure
 * [shouldArm]; the actual ramp uses the pure [equalPowerVolumes]. Any manual
 * transport ([cancelFade]) or unmet condition degrades to today's hard cut.
 *
 * All player access happens on [scope] (the service main scope); B operations
 * are guarded by the [fading] flag.
 */
@OptIn(UnstableApi::class)
class CrossfadeController(
    private val playerA: ExoPlayer,
    private val buildPlayerB: () -> ExoPlayer,
    crossfadePreference: CrossfadePreference,
    private val scope: CoroutineScope,
) {
    @Volatile private var enabled = false
    @Volatile private var durationMs = 6000L

    private var playerB: ExoPlayer? = null
    private var fadeJob: Job? = null

    @Volatile private var fading = false

    /**
     * Set just before the controller's own [Player.seekToNextMediaItem] so the
     * service's transition listener can tell that SEEK apart from a user skip
     * (both produce `MEDIA_ITEM_TRANSITION_REASON_SEEK`). Consumed once.
     */
    @Volatile private var selfSeek = false

    init {
        scope.launch { crossfadePreference.enabled.collect { enabled = it } }
        scope.launch { crossfadePreference.durationMs.collect { durationMs = it } }
    }

    /** True while a fade was started by this controller's own next-seek. Consumed once. */
    fun consumeSelfSeek(): Boolean {
        val v = selfSeek
        selfSeek = false
        return v
    }

    /**
     * Position-poll hook. Builds [ArmInputs] from the live player state and
     * starts the fade when [shouldArm] holds. No-ops cheaply when disabled or
     * already fading, so it is safe to call on every tick.
     */
    fun onProgress(positionMs: Long, durationMs: Long, hasResolvedNext: Boolean, repeatMode: Int) {
        if (fading || !enabled) return
        if (durationMs <= 0) return
        val inputs = ArmInputs(
            enabled = enabled,
            repeatOne = repeatMode == Player.REPEAT_MODE_ONE,
            hasResolvedNext = hasResolvedNext,
            remainingMs = durationMs - positionMs,
            trackDurationMs = durationMs,
            crossfadeMs = this.durationMs,
        )
        if (shouldArm(inputs)) startFade()
    }

    private fun startFade() {
        val outgoing = playerA.currentMediaItem ?: return
        val pos = playerA.currentPosition
        val fadeMs = durationMs
        fading = true
        fadeJob = scope.launch {
            val b = playerB ?: buildPlayerB().also { playerB = it }
            // Prime B on the outgoing tail and wait (bounded) for it to buffer
            // at A's position BEFORE muting A, so the A→B hand-off doesn't gap.
            // ponytail: poll B's state instead of wiring a one-shot listener.
            b.volume = 1f
            b.setMediaItem(outgoing)
            b.seekTo(pos)
            b.playWhenReady = false
            b.prepare()
            if (!awaitReady(b, timeoutMs = 1500)) {
                // B couldn't buffer in time → degrade to a normal hard cut.
                b.stop(); b.clearMediaItems()
                fading = false
                return@launch
            }
            // Fire: B carries the outgoing tail at full volume; A advances the
            // queue to the incoming track muted, then both ramp equal-power.
            selfSeek = true
            playerA.seekToNextMediaItem()
            playerA.volume = 0f
            b.playWhenReady = true

            var elapsed = 0L
            while (elapsed < fadeMs) {
                val (out, inc) = equalPowerVolumes(elapsed.toFloat() / fadeMs)
                b.volume = out
                playerA.volume = inc
                delay(STEP_MS)
                elapsed += STEP_MS
            }
            b.stop(); b.clearMediaItems()
            playerA.volume = 1f
            fading = false
        }
    }

    /** Polls B for [Player.STATE_READY] up to [timeoutMs]; true if it got there. */
    private suspend fun awaitReady(b: ExoPlayer, timeoutMs: Long): Boolean {
        var waited = 0L
        while (b.playbackState != Player.STATE_READY && waited < timeoutMs) {
            delay(READY_POLL_MS)
            waited += READY_POLL_MS
        }
        return b.playbackState == Player.STATE_READY
    }

    /** Cancels any pending/in-flight fade and restores A to full volume. */
    fun cancelFade() {
        fadeJob?.cancel()
        fadeJob = null
        playerB?.let { it.stop(); it.clearMediaItems() }
        playerA.volume = 1f
        fading = false
    }

    /** Propagate A's pause to B while fading. */
    fun onPause() {
        if (fading) playerB?.playWhenReady = false
    }

    /** Propagate A's resume to B while fading. */
    fun onResume() {
        if (fading) playerB?.playWhenReady = true
    }

    /** Release the pooled B. Call from the service's player-release path. */
    fun release() {
        fadeJob?.cancel()
        fadeJob = null
        playerB?.release()
        playerB = null
    }

    private companion object {
        const val STEP_MS = 50L
        const val READY_POLL_MS = 20L
    }
}
