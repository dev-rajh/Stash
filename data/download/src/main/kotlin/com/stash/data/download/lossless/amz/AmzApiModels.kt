package com.stash.data.download.lossless.amz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the amz.squid.wtf JSON API (an Amazon Music proxy).
 *
 * We model only the fields the matcher and resolver consume; everything
 * else (lyrics, composer, copyright, label, track_number, …) falls
 * through under the lenient parser's `ignoreUnknownKeys = true` and
 * disappears silently. Anything not guaranteed by the live API is
 * nullable or defaulted so a wire-shape drift degrades to safe
 * zero/null values rather than failing deserialisation — what we want
 * for a third-party reverse-engineered API.
 *
 * NOTE: the `/api/track` response has NO duration field — none is
 * modelled here. Duration must be obtained post-download via the
 * audio extractor, not from this API.
 */

// ── Search (`POST /api/search`) ──────────────────────────────────────────

@Serializable
data class AmzSearchResponse(
    val trackList: List<AmzSearchItem> = emptyList(),
)

@Serializable
data class AmzSearchItem(
    val asin: String,
    val title: String,
    val primaryArtistName: String? = null,
    val artistName: String? = null,
    val albumArtistName: String? = null,
    val album: AmzSearchAlbum? = null,
)

@Serializable
data class AmzSearchAlbum(
    val title: String? = null,
    val image: String? = null,
)

// ── Track (`POST /api/track`) ────────────────────────────────────────────

@Serializable
data class AmzTrackResponse(
    val metadata: AmzTrackMeta? = null,
)

/**
 * Full per-track metadata. `cover`/`coverCdn` are the same Amazon CDN
 * art URL in captured responses; prefer [coverCdn] when present. There
 * is deliberately NO duration field — the API does not return one.
 */
@Serializable
data class AmzTrackMeta(
    val asin: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    val cover: String? = null,
    @SerialName("cover_cdn") val coverCdn: String? = null,
    val isrc: String? = null,
    @SerialName("is_explicit") val isExplicit: Boolean = false,
)
