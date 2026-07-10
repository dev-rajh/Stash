package com.stash.core.data.radio

/**
 * One potential radio track before YouTube resolution.
 *
 * [videoId] is known for artist-radio picks (they come from a YT `popular`
 * list) and null for song-radio similar-track picks (resolved via
 * `searchCanonicalVideoId` at emit time). [weight] is the source artist's
 * relative frequency (seed share, or a neighbor's Last.fm match score); the
 * interleaver uses it to decide how often this candidate's artist appears.
 */
data class RadioCandidate(
    val artist: String,
    val title: String,
    val videoId: String?,
    val weight: Float,
)

/** Stable per-station identity for the no-repeat set. */
internal fun RadioCandidate.identity(): String =
    videoId?.takeIf { it.isNotBlank() }
        ?: (artist.trim().lowercase() + "|" + title.trim().lowercase())
