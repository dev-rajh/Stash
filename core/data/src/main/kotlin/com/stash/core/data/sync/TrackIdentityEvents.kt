package com.stash.core.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits a track id every time that track's `youtube_id` is REPLACED (not
 * first-set) — i.e. an existing platform identity is swapped for a
 * different one. Resync approval, wrong-match swap, and OMV→ATV
 * canonicalization all go through [emitIdentityChanged].
 *
 * Exists so `:core:media` (PlayerRepositoryImpl) can evict any
 * [com.stash.core.media.streaming.StreamUrlCache] entry keyed on the
 * OLD identity without `:core:data` call sites needing a direct
 * dependency on `:core:media` (which would be circular — StreamUrlCache
 * lives in `:core:media`, which already depends on `:core:data`).
 * Mirrors `MusicRepository.trackDeletions`, same reasoning.
 */
@Singleton
class TrackIdentityEvents @Inject constructor() {
    /**
     * Buffered generously and **never dropping**.
     *
     * This started as `extraBufferCapacity = 8, DROP_OLDEST`, which is the right
     * shape for a progress or UI signal where the newest value supersedes the last.
     * Cache invalidation is the opposite: every event is load-bearing and none is
     * made redundant by a later one. A dropped event leaves that track's stale
     * StreamUrl in place — the exact bug this class exists to fix, reappearing
     * intermittently instead of consistently.
     *
     * The emitters are the bulk paths (YtLibraryCanonicalizer sweeping OMV→ATV
     * across a library, batched resync approvals), so a small buffer would drop
     * during precisely the operations that change the most identities.
     *
     * `tryEmit` cannot suspend, so backpressure isn't available here; the buffer
     * is instead sized past any realistic burst, and [emitIdentityChanged] logs if
     * one is ever refused rather than losing it silently.
     */
    private val _changes = MutableSharedFlow<Long>(
        extraBufferCapacity = 512,
    )
    val changes: SharedFlow<Long> = _changes.asSharedFlow()

    fun emitIdentityChanged(trackId: Long) {
        if (!_changes.tryEmit(trackId)) {
            // Only reachable if the buffer is genuinely saturated. Says so out
            // loud: the consequence is a track that keeps serving a stale URL,
            // which otherwise presents as "won't play" or "played the wrong
            // song" with nothing in the logs to explain it.
            android.util.Log.w(
                "TrackIdentityEvents",
                "identity-change buffer full — stale StreamUrl may persist for track $trackId",
            )
        }
    }
}
