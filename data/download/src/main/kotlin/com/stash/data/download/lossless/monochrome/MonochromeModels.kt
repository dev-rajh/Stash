package com.stash.data.download.lossless.monochrome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Response shapes for `https://api.monochrome.tf` (running uimaxbai/hifi-api,
 * a fork of binimum/hifi-api, MIT-licensed). The API serves Tidal's catalog
 * with the operator-funded model: the operator runs hifi-api with their own
 * paid Tidal token, end users get unauthenticated access.
 *
 * All endpoints wrap their payload in a top-level `{"data": ...}` envelope.
 * Track-stream manifests are themselves base64-encoded JSON inside the
 * envelope — see [decodeManifest].
 *
 * Reference client (Python): github.com/dbv111m/musicgrabber — see
 * `search.py::_search_monochrome_api` and `downloads.py::_download_monochrome_direct`.
 */

// ── /search/ response ────────────────────────────────────────────────

@Serializable
data class TidalSearchResponse(
    val data: TidalSearchData? = null,
)

@Serializable
data class TidalSearchData(
    val items: List<TidalTrack> = emptyList(),
)

/**
 * Tidal track item from the search response. ISRC is sometimes present
 * (varies by upstream catalog row) — when both query and candidate carry
 * one we short-circuit confidence to 0.95.
 */
@Serializable
data class TidalTrack(
    val id: Long,
    val title: String,
    val duration: Int = 0,                   // seconds
    val isrc: String? = null,
    val streamReady: Boolean = false,
    val audioQuality: String? = null,        // "HI_RES_LOSSLESS" | "LOSSLESS" | "HIGH" | etc.
    val popularity: Int? = null,
    val artist: TidalArtist? = null,
    val artists: List<TidalArtist>? = null,  // multi-artist tracks
    val album: TidalAlbum? = null,
)

@Serializable
data class TidalArtist(
    val id: Long? = null,
    val name: String = "",
)

@Serializable
data class TidalAlbum(
    val id: Long? = null,
    val title: String? = null,
    val cover: String? = null,               // dash-separated UUID, e.g. "abc-def-ghi"
)

// ── /track/ response (envelope) ──────────────────────────────────────

@Serializable
data class TidalTrackResponse(
    val data: TidalTrackData? = null,
)

@Serializable
data class TidalTrackData(
    val manifest: String? = null,            // base64-encoded JSON; decode with decodeManifest()
)

/**
 * Decoded payload from [TidalTrackData.manifest]. The manifest itself is
 * base64-encoded JSON inside the API response.
 *
 * `encryptionType == "NONE"` means BTS (plain HTTP segments — what Stash
 * needs). Anything else (e.g. `"OLD_AES"` for legacy Tidal MPD) is a
 * Widevine/encrypted stream and must be skipped — Stash's download path
 * does not handle DRM.
 */
@Serializable
data class TidalDecodedManifest(
    val encryptionType: String = "NONE",
    val urls: List<String> = emptyList(),
    val mimeType: String? = null,            // typically "audio/flac" for LOSSLESS
)

// ── Helpers ──────────────────────────────────────────────────────────

/**
 * Decodes a base64-encoded JSON manifest string into [TidalDecodedManifest].
 * Returns null on any parse failure (caller treats as "skip this track").
 */
internal fun decodeManifest(base64Manifest: String, json: Json): TidalDecodedManifest? = runCatching {
    val decodedBytes = java.util.Base64.getDecoder().decode(base64Manifest)
    val decodedJson = String(decodedBytes, Charsets.UTF_8)
    json.decodeFromString(TidalDecodedManifest.serializer(), decodedJson)
}.getOrNull()

/**
 * Tidal cover IDs are stored as dash-separated UUIDs (e.g. "abc-def-ghi").
 * The CDN URL replaces dashes with slashes and appends a size + extension.
 *
 * Returns null if the input doesn't look like a Tidal cover UUID (basic
 * sanity guard — empty string, no dashes, etc.).
 */
internal fun coverIdToUrl(cover: String?, size: String = "1280x1280"): String? {
    if (cover.isNullOrBlank()) return null
    if (!cover.contains('-')) return null  // not a Tidal-shape UUID
    return "https://resources.tidal.com/images/${cover.replace('-', '/')}/$size.jpg"
}
