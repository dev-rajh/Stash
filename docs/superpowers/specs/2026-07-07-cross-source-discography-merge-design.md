# Cross-Source Discography Merge — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorm), pre-plan
**Workstream:** Discoverability feature #2 (after Now Playing → artist profile; before artist/song radios)

## Problem

The artist profile's discography is 100% YouTube-Music-powered. YT Music's
catalog has gaps: My Bloody Valentine's Official Artist Channel exposes only 2
album entities (`m b v` 2013 + the `you made me realise` EP). *Loveless* and
*Isn't Anything* do not exist as album entities anywhere on YT Music (confirmed
via album-filtered search) — only as artist-uploaded playlists. So the profile
shows a near-empty discography for a canonical artist. This is a YouTube catalog
gap, **not** a Stash parser bug (`getArtist` already follows "View all" grids).

The app's own lossless source, Qobuz (via `qbdlx` — the direct Qobuz API with a
rotating per-account token pool), carries MBV's full catalog. This feature
supplements the YT-sourced discography with Qobuz albums so the profile shows the
complete discography, and — per the approved decision — plays those albums
**natively from Qobuz** (guaranteed-lossless FLAC), not by re-resolving through
YouTube.

## Approved decisions

1. **Native Qobuz playback.** A supplemented album plays directly from its Qobuz
   track ids as FLAC, not by search-resolving a YouTube `videoId`. Guaranteed
   lossless and complete.
2. **Union merge, Qobuz-preferred on collision.** Show both catalogs merged;
   when the same album appears in both, keep the Qobuz entry (native lossless)
   and drop the YouTube duplicate; keep YouTube-only albums Qobuz lacks. Never
   loses an album; maximizes native-lossless playback.

## Architecture

### The seam

`ArtistCache` (`core:data`) currently calls `YTMusicApiClient.getArtist()` and
caches the `ArtistProfile`. To avoid dragging `core:data` into a hard dependency
on the download module, introduce an interface that `core:data` owns:

```kotlin
interface DiscographySupplement {
    /** Qobuz albums for an artist, or empty when no confident match /
     *  qbdlx unavailable. Best-effort; never throws to the caller. */
    suspend fun forArtist(artistName: String): List<AlbumSummary>
}
```

- Default binding = no-op (empty list), so with Qobuz disabled the profile is
  byte-for-byte today's behavior.
- Real implementation lives in the module with Qobuz access; bound via Hilt
  `@Binds` declared in that module.
- `ArtistCache` calls `getArtist()` then `supplement.forArtist(name)`, merges,
  and caches the merged profile as **one unit** — so taps route correctly even
  when the profile is served from cache.

### New / changed components

- **`QbdlxApiClient`** (existing, `data:download`) — add two metadata calls
  reusing the existing app_id + token-pool auth:
  - `getArtistAlbums(artistName)` → resolve the Qobuz artist by name and page
    the full album list.
  - `getAlbum(qobuzAlbumId)` → album detail with track list (each track's id,
    title, performer, duration, disc/track number).
  All Qobuz HTTP stays in this one class.

- **`QobuzDiscographyProvider`** (`data:download`) — implements
  `DiscographySupplement`. Gates on qbdlx being stream-enabled with live tokens;
  resolves the artist (confidence-gated), pages the discography, buckets each
  release into albums vs singles/EPs by Qobuz release type, maps Qobuz DTOs →
  `AlbumSummary(source = QOBUZ)`.

- **`DiscographyMerger`** — pure function (no I/O), independently testable: takes
  YT albums/singles + Qobuz albums, dedups by normalized title (reusing
  `QobuzCandidateMatcher` normalization), prefers the Qobuz entry on collision,
  unions YT-only entries, orders each lane newest-first by year.

- **`AlbumSummary`** — gains `source: AlbumSource` (`YOUTUBE` | `QOBUZ`). Its
  `id` stays the source-native id (YT browseId or Qobuz album_id). `AlbumCache`
  routes on `source`.

- **`AlbumCache`** — routes `get()` by `source`: `YOUTUBE` → existing YT album
  page parse; `QOBUZ` → `QbdlxApiClient.getAlbum()` → `AlbumDetail`.

- **`Track` / `MusicSource`** — add `MusicSource.QOBUZ` and thread the Qobuz
  **track id** (new nullable field, e.g. `qobuzTrackId: Long?`) from
  `AlbumDetail` → `Track` → MediaItem extras → resolver. Native tracks get a
  synthetic queue id derived from the Qobuz track id (distinct from the
  `videoId.hashCode()` scheme) and `youtubeId = null`.

- **`QbdlxQobuzSource`** — add `resolveById(qobuzTrackId, requestedQuality)` that
  skips `search()`/match and goes straight to `resolveFile(trackId…)`, reusing
  the existing token-rotation logic.

- **`QbdlxStreamResolver`** — fast path: if the incoming track carries a Qobuz
  track id, call `resolveById` directly; otherwise the existing search/match
  path. Preserves behavior for all non-Qobuz tracks.

### Module-graph note (for the plan)

`AlbumSummary` currently lives in `data:ytmusic`. Because
`DiscographySupplement.forArtist` returns `List<AlbumSummary>` and the interface
is owned by `core:data` with the impl in `data:download`, `AlbumSummary` may need
to move to a shared model module (`core:model`) to avoid a
`data:download → data:ytmusic` edge. The plan confirms the exact wiring; the
design does not depend on which module it lands in.

## Flows

### Merge flow (once per artist per 6h, inside the `ArtistCache` miss)

1. `YTMusicApiClient.getArtist()` → YT `ArtistProfile` (name, popular, albums,
   singles, related), unchanged.
2. `supplement.forArtist(name)`:
   - **Gate:** qbdlx stream-enabled + live tokens. Else empty (YT-only).
   - **Artist match (correctness crux):** resolve the Qobuz artist by name;
     require **normalized-name equality** (reuse matcher normalization:
     lowercase, strip punctuation / "the" / "feat"). Take Qobuz's top-ranked
     artist; if its normalized name ≠ the YT artist's, **skip the supplement**
     — under-supplementing beats showing a wrong artist's records. When the YT
     profile already has albums, album-title overlap is a confirmation booster
     but is not required (some artists have zero YT albums).
   - Page the matched artist's full discography; bucket by Qobuz release type;
     map → `AlbumSummary(source = QOBUZ)`.
3. `DiscographyMerger.merge(...)`: dedup by normalized title, Qobuz wins
   collisions, union YT-only; order newest-first by year per lane.
4. Merged profile cached as one unit (6h SWR, 20-entry LRU, unchanged).

### Native playback flow (album tap)

1. `AlbumCache.get()` sees `source = QOBUZ` → `QbdlxApiClient.getAlbum(id)` →
   `AlbumDetail` whose tracks carry Qobuz track ids.
2. Track rows → domain `Track`s: `source = QOBUZ`, `qobuzTrackId` threaded,
   `youtubeId = null`, synthetic queue id from the Qobuz id, art from Qobuz.
3. `setQueue` → the Qobuz id rides in the MediaItem extras.
4. Resolver chain: `QbdlxStreamResolver` sees the Qobuz id → `resolveById` →
   `getFileUrl(id)` directly. No fuzzy matching. Guaranteed-correct,
   guaranteed-lossless.
5. No `youtubeId` → YouTube fallback skipped (correct: it came from Qobuz).
   Now Playing shows the real FLAC badge; `streamOrigin = "qbdlx"`.

### Reuse

"Play Artist" already fills its queue from `albums + singles` through
`AlbumCache` (`ArtistProfileViewModel.playArtist`). Once `AlbumCache` routes by
source, Play-Artist plays the merged catalog natively with **no change to that
code**. Popular tracks and related artists stay YouTube-sourced.

## Error handling & resilience

- Supplement is best-effort everywhere (mirrors the existing "View all"
  grid-expansion pattern): any failure — network, auth, no confident match,
  qbdlx disabled, all tokens dead — collapses to an empty supplement → YT-only
  profile. Never errors or blocks the artist page.
- Gating on qbdlx-streamable preserves the invariant: **every shown album is
  playable.**
- If qbdlx dies after caching, a Qobuz album tap fails visibly like any
  streaming outage (same UX as an all-sources-down YouTube track).

## Cost

- Supplement runs inside the cache miss → cached with the profile (6h SWR,
  20-entry LRU): ~2 extra Qobuz calls per artist per 6h.
- Album detail rides the existing `AlbumCache`.
- Merge is pure and instant.

## Known limitations

- A remaster/deluxe whose title differs ("Loveless" vs "Loveless (Remastered)")
  won't dedup and may appear twice — deliberate; safer than false-merging
  distinct releases.
- Download-by-id for native tracks is out of scope; the existing lossless
  download path search-matches (works). Download-by-id is a possible future
  refinement for exactness.

## Testing

- **`DiscographyMerger`** (pure): dedup, Qobuz-preference on collision, YT-only
  retention, ordering, empty-supplement passthrough.
- **`QobuzDiscographyProvider`**: match-gate (accept exact-normalized, reject
  mismatch), pagination assembly, disabled / no-token → empty. Fake client.
- **`QbdlxApiClient`** new methods: parse offline JSON fixtures (as existing
  qbdlx tests do) for artist albums + album detail.
- **`QbdlxQobuzSource.resolveById` / `QbdlxStreamResolver`**: a track with a
  Qobuz id calls `getFileUrl` without search; falls through to search when
  absent.
- **`AlbumCache`** routing: `source = QOBUZ` → Qobuz path; `YOUTUBE` → YT path.
- **Track identity**: synthetic id from the Qobuz id is stable and distinct from
  the `videoId.hashCode()` scheme.
- **UI** (light): merged albums render; source tag drives tap routing.

## Out of scope

- Search-tab Qobuz results and the latent `searchAll` flat-shape bug (separate
  workstream item).
- Artist/song radios (feature #3).
- Persisted YT-artist → Qobuz-artist_id mapping and a manual "wrong artist?"
  correction (possible future robustness; YAGNI for v1).
