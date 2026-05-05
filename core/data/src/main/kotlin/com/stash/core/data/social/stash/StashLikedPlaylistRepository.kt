package com.stash.core.data.social.stash

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.9.13: Manages the local "Liked Songs" playlist (one per install,
 * [PlaylistType.STASH_LIKED]). Lazy-seeded on the first heart-tap so a
 * fresh install carries no orphan playlist row until the user actually
 * uses the feature.
 *
 * Mirrors [com.stash.core.data.repository.MusicRepositoryImpl.linkTrackToDownloadsMix]
 * exactly — uses [MusicRepository.addTrackToPlaylist] so the playlist's
 * cached `track_count` and the cross-ref's `position` stay consistent
 * with the rest of the codebase. Raw [PlaylistDao.insertCrossRef] calls
 * skip both, so we go through the helper.
 *
 * Idempotent: tapping the heart twice on the same track is a no-op
 * after the first call (cross-ref existence check + early return), and
 * the `tracks.stash_liked_at` timestamp is only written once.
 */
@Singleton
class StashLikedPlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val musicRepository: MusicRepository,
) {
    /**
     * Add [trackId] to the Stash Liked Songs playlist. Seeds the
     * playlist on first call. Sets `tracks.stash_liked_at` only on the
     * first successful add — re-tapping a track that's already liked
     * leaves the existing timestamp untouched.
     */
    suspend fun add(trackId: Long) {
        val playlistId = ensureSeeded()
        if (playlistDao.getCrossRef(playlistId, trackId) != null) return
        // Reuse existing helper for trackCount + position handling.
        // Mirrors linkTrackToDownloadsMix at MusicRepositoryImpl.kt:294.
        musicRepository.addTrackToPlaylist(trackId = trackId, playlistId = playlistId)
        trackDao.markStashLiked(trackId, System.currentTimeMillis())
    }

    /**
     * Lazily create the "Liked Songs" playlist if it doesn't exist.
     * Same shape as [com.stash.core.data.repository.MusicRepositoryImpl.ensureDownloadsMixSeeded]
     * at MusicRepositoryImpl.kt:281 — fixed `sourceId` so the unique
     * `source_id` index keeps us at one row per install, `syncEnabled
     * = false` so it never feeds into the sync pipeline.
     */
    private suspend fun ensureSeeded(): Long {
        playlistDao.findBySourceId(STASH_LIKED_SOURCE_ID)?.let { return it.id }
        val entity = PlaylistEntity(
            name = "Liked Songs",
            source = MusicSource.BOTH,
            sourceId = STASH_LIKED_SOURCE_ID,
            type = PlaylistType.STASH_LIKED,
            syncEnabled = false,
        )
        return playlistDao.insert(entity)
    }

    companion object {
        private const val STASH_LIKED_SOURCE_ID = "stash_liked_songs"
    }
}
