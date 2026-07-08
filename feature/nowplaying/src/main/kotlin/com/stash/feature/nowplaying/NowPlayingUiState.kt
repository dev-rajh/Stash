package com.stash.feature.nowplaying

import androidx.compose.ui.graphics.Color
import com.stash.core.model.Playlist
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.ui.components.PlaylistInfo

/**
 * Immutable snapshot of everything the Now Playing screen needs to render.
 *
 * Mapped from [com.stash.core.model.PlayerState] plus position ticks and
 * palette-extracted colors from the album art.
 */
data class NowPlayingUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isBuffering: Boolean = false,
    val queueSize: Int = 0,
    val currentIndex: Int = 0,
    /** Full list of tracks in the playback queue (including the currently playing track). */
    val queue: List<Track> = emptyList(),
    /** Dominant color extracted from the album art via Palette API. */
    val dominantColor: Color = Color(0xFF6750A4),
    /** Vibrant color extracted from the album art via Palette API. */
    val vibrantColor: Color = Color(0xFF8E24AA),
    /** Muted color extracted from the album art via Palette API. */
    val mutedColor: Color = Color(0xFF37474F),
    /** User-created playlists available for the "Save to Playlist" sheet. */
    val userPlaylists: List<PlaylistInfo> = emptyList(),
    /**
     * Active playlists the currently-playing track belongs to — rendered as
     * the tappable "Appears in" list on the Now Playing screen. Empty for
     * streaming-only tracks (id == 0) that have no library row.
     */
    val containingPlaylists: List<Playlist> = emptyList(),
    /**
     * File size of the current track resolved from disk/SAF, used when the
     * Room `file_size_bytes` column is 0 (notably SAF `content://` libraries,
     * which the on-disk size backfill can't read). 0 when unknown.
     */
    val currentFileSizeBytes: Long = 0L,
    /**
     * `true` when the currently-playing MediaItem is sourced from an
     * `http`/`https` URI (i.e. streamed from Kennyy) rather than a
     * local `file://` URI. Used by the Now Playing screen to render
     * a small wifi indicator on the quality line.
     */
    val isStreaming: Boolean = false,
) {

    /**
     * Playback progress as a fraction in `[0f, 1f]`.
     * Returns `0f` when duration is zero to avoid division-by-zero.
     */
    val progressFraction: Float
        get() = if (durationMs > 0) {
            (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

    /** `true` when a track is loaded and ready to display. */
    val hasTrack: Boolean
        get() = currentTrack != null
}
