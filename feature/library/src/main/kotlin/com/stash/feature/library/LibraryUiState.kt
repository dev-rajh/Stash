package com.stash.feature.library

import com.stash.core.model.Playlist
import com.stash.core.model.Track

/**
 * UI state for the Library screen.
 *
 * Artists and albums are split into multi-track (primary) and single-track
 * (collapsed) lists so the UI can show the main library prominently and
 * hide the noise from daily-mix one-off entries behind an expandable section.
 */
data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.TRACKS,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.RECENT,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    val downloadedNonFlacCount: Int = 0,
    val flacUpgrade: FlacUpgradeUiState = FlacUpgradeUiState(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),

    /** Recently downloaded tracks — feeds the Songs recently-downloaded rail. */
    val recentlyAdded: List<Track> = emptyList(),

    /** Artists with 2+ tracks, sorted by track count descending. */
    val artists: List<ArtistInfo> = emptyList(),
    /** Artists with exactly 1 track, collapsed by default. */
    val singleTrackArtists: List<ArtistInfo> = emptyList(),

    /** Albums with 2+ tracks, sorted by track count descending. */
    val albums: List<AlbumInfo> = emptyList(),
    /** Albums with exactly 1 track, collapsed by default. */
    val singleTrackAlbums: List<AlbumInfo> = emptyList(),

    val isLoading: Boolean = true,
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    val currentlyPlayingTrackId: Long? = null,

    /** Total downloaded songs in the library (unfiltered) — drives the Shuffle hero count. */
    val librarySongCount: Int = 0,
)

/** Tabs available in the library browser. LIKED sits between Songs and Playlists. */
enum class LibraryTab { PLAYLISTS, TRACKS, LIKED, ARTISTS, ALBUMS }

/** Source sift for the Liked subcategory: all likes, or one origin. */
enum class LikedFilter { ALL, STASH, SPOTIFY, YOUTUBE }

/**
 * Sort options applicable to every content tab. Track-centric fields
 * (artist, duration, play recency) fall back to the nearest sensible
 * ordering on the Playlists / Artists / Albums tabs, which don't carry
 * those columns — see the `when` blocks in [LibraryViewModel].
 */
enum class SortOrder {
    RECENT,            // Recently added (newest first)
    OLDEST,            // Oldest added first
    ALPHABETICAL,      // Title / name A–Z
    ALPHABETICAL_DESC, // Title / name Z–A
    ARTIST,            // Artist A–Z
    MOST_PLAYED,
    LEAST_PLAYED,
    LONGEST,           // Longest duration first
    SHORTEST,          // Shortest duration first
    RECENTLY_PLAYED,   // Most recently played first
}

/**
 * Top-level filter applied to the Tracks tab. Originally just service
 * source (Spotify / YouTube); [FLAC] / [NON_FLAC] piggyback on the same
 * control because the user-facing question is the same — "show me some
 * subset of my tracks". [FLAC] keeps only lossless-codec files (flac,
 * alac, wav, etc.); [NON_FLAC] keeps only the lossy ones.
 */
enum class SourceFilter { ALL, YOUTUBE, SPOTIFY, FLAC, NON_FLAC }

/**
 * Progress for the bulk "Find FLAC" action that upgrades existing lossy
 * downloads through the same lossless path as Now Playing.
 */
data class FlacUpgradeUiState(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
) {
    val progressText: String
        get() = if (total > 0) "$completed of $total" else ""
}

/**
 * @property name           Display name of the artist.
 * @property trackCount     Number of tracks by this artist in the library.
 * @property totalDurationMs Combined duration of all tracks in milliseconds.
 * @property artUrl         Remote artwork URL (album art proxy from their top track).
 */
data class ArtistInfo(
    val name: String,
    val trackCount: Int,
    val totalDurationMs: Long,
    val artUrl: String? = null,
)

/**
 * @property name       Album title.
 * @property artist     Primary artist on the album.
 * @property trackCount Number of tracks in this album.
 * @property artPath    Local file path to album artwork, or null.
 * @property artUrl     Remote artwork URL, or null.
 */
data class AlbumInfo(
    val name: String,
    val artist: String,
    val trackCount: Int,
    val artPath: String? = null,
    val artUrl: String? = null,
)
