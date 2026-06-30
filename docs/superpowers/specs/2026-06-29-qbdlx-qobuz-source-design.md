# qbdlx Qobuz Lossless Source — Design

**Date:** 2026-06-29
**Status:** Design (approved approach + token model; pending spec review + user review)
**Branch:** `feat/qbdlx-qobuz-source`

## 1. Goal

Add a 5th lossless source to Stash backed by **direct Qobuz API calls** using credentials sourced from `qbdlx.launchpd.cloud` (a QobuzDownloaderX instance). The win is **uptime + token-pool resilience**: where kennyy/squid depend on a single operator/account, qbdlx provides a pool of 5–6 real Qobuz account tokens across countries, so one banned account or a downed proxy host does not take the source down. Scope: **both download and streaming**, FLAC.

It is *not* a new catalog (same Qobuz catalog as kennyy/squid) and rides the same Qobuz `app_id 798273057` kennyy currently uses — so it is **not** independent of a Qobuz-level app_id ban. Its value is operator/account redundancy and forward-compatibility (Qobuz is migrating toward the new secure format; a direct client keeps Qobuz reachable if the proxies' plain path degrades).

## 2. Verified facts (live, 2026-06-29)

All of the following were confirmed against the real Qobuz API during brainstorming, not assumed:

- **Signing algorithm (exact, reproduced live):** `request_sig = md5(object+method + sorted_params + request_ts + app_secret)`. For `track/getFileUrl`: `md5("trackgetFileUrl" + "format_id"+fmt + "intentstream" + "track_id"+id + ts + secret)`. Verified: correct sig → HTTP 200; wrong secret → HTTP 400 "Invalid Request Signature". Reproduced all 4 HAR signatures exactly.
- **Credentials:** `app_id = 798273057`, `app_secret = abb21364945c0583309667d13ca3d93a` (constant across the whole token pool; verified — reproduces HAR sigs and signs fresh requests that return 200).
- **Search:** `GET /api.json/0.2/catalog/search?query=<artist title>&type=tracks&limit=N&app_id=<id>` with `X-User-Auth-Token` header. **No signature required.** Returns `tracks.items[]` with `id`, `title`, `performer.name`, `isrc`, `hires`, `maximum_bit_depth`.
- **Resolve (download + stream):** `GET /api.json/0.2/track/getFileUrl?track_id=&format_id=&app_id=&request_ts=&request_sig=&intent=stream` (signed) → JSON with a plain `url` field = a pre-signed Akamai FLAC URL (`profile=raw`). Verified: CDN returns `206`, `Content-Type: audio/flac`, magic bytes `fLaC`, Range-seekable. **This is the same mechanism kennyy/squid use** — progressive-streamable and downloadable, no DRM, no segments.
- **NOT needed:** the `file/url` secure-segmented endpoint (HKDF→AES-CBC→AES-CTR, 16 segments, `key`/`blob`) that qbdlx's *web player* uses for instant streaming. It requires a browser `session/start` (401 on replay) and client-side decrypt. Stash streams the plain FLAC URL progressively (like kennyy), so this path is out of scope.
- **Token binding:** a `user_auth_token` is bound to the `app_id` it was minted under. The pool tokens are minted under `798273057`; using one with a different app_id yields `restrictions:[UserUnauthenticated]` + a 30s preview (`fmt=5`, `range=20-30`). A live, correctly-paired token returns full FLAC.
- **Quality:** `format_id` 27 = hi-res ≤192kHz, 7 = hi-res ≤96kHz, 6 = CD 16/44.1, 5 = MP3. Per-track/account availability varies (`FormatRestrictedByFormatAvailability` downgrades hi-res→CD silently); request best, accept what's served.

## 3. Architecture

### 3.1 Reuse (no new code)
- `LosslessSource` interface, `SourceResult`, `TrackQuery`, `AudioFormat` — unchanged.
- `AggregatorRateLimiter` — add a `configs["qbdlx_qobuz"]` entry (conservative, like the others; a pool of shared accounts must not be hammered).
- `LosslessUrlInspector` / `LosslessSourceHealthGate` — already detect the `range=`/`fmt=5`/preview degradation a dead or wrong-region token produces → auto-skip + failover. **Free.**
- `QobuzSource`'s matcher (`normalize` / `jaccard` / `artistSimilarity` / `confidence` / `MIN_CONFIDENCE`) — extract to a shared helper or reuse directly; identical Qobuz catalog shape.
- `LosslessSourceRegistry` (download) + `StreamSourceRegistry` (stream) — register the new source/resolver last in the chain.
- Download pipeline (`LosslessUrlDownloader`) + stream pipeline (plain-URL progressive play) — unchanged; the source just returns a plain FLAC `SourceResult` / `StreamUrl`.

### 3.2 New components
- **`QbdlxCredentialStore`** (`data:download`, package `lossless.qbdlx`): holds the constant `app_id`+`secret` (from `BuildConfig`) and the **token pool**. Manages rotation: returns the current active token, advances to the next on failure, and reads a user-pasted token from `LosslessSourcePreferences` (DataStore) that takes priority over the bundled pool. Bundled pool ships in `BuildConfig` (build-time input — see §7).
- **`QbdlxQobuzApiClient`** (`data:download`): a **direct-Qobuz** client (distinct from the proxy-based `QobuzApiClient`). Signs requests (MD5), attaches `X-App-Id` + `X-User-Auth-Token`, calls `catalog/search` and `track/getFileUrl` against `www.qobuz.com`. Uses a derived OkHttp client off the shared pool (no shared-client mutation). Throws a `QbdlxApiException(status, message)` on non-2xx; 401/`UserUnauthenticated`/preview → "token dead, rotate + skip".
- **`QbdlxQobuzSource : LosslessSource`** (`data:download`, id `"qbdlx_qobuz"`): `resolve(query)` → search (ISRC-first) → match → signed `getFileUrl` → `SourceResult(downloadUrl = plain FLAC url, format = flac, confidence)`. Rate-limited; reports success/failure/rate-limited to the limiter; rotates token on auth failure.
- **`QbdlxStreamResolver`** (`core:media`): mirrors `QobuzStreamResolver` — search → match → `getFileUrl` → `StreamUrl(url, origin = "qbdlx", expires = etsp)`. Plays via the default media-source factory (plain https FLAC, no special routing).
- **Settings**: a "qbdlx (Qobuz)" toggle + a "Paste token" field (refresh path), alongside the existing lossless-source rows.

### 3.3 Credential / token model (approved)
- **Constant** `app_id` + `secret` bundled in `BuildConfig` (like arcod's keys / amz's BuildConfig endpoint).
- **Token pool**: bundle the current 5–6 tokens in `BuildConfig`. `QbdlxCredentialStore` rotates through them, auto-skipping any that return auth-failure/preview (detected via the inspector). A **user-pasted token** in Settings takes priority and is the refresh path when the bundled pool ages out (tokens rotate ~monthly). This gives seamless out-of-box behavior + self-service when tokens rotate, with no fragile runtime scrape of qbdlx's server-rendered token page.

## 4. Data flow

```
resolve(TrackQuery)
  └─ rateLimiter.acquire("qbdlx_qobuz")  (bail if circuit-broken)
  └─ token = credentialStore.activeToken()        (pasted > bundled pool)
  └─ search: GET catalog/search?query=<isrc | artist title>&type=tracks   [X-User-Auth-Token]
  └─ match candidates (QobuzMatcher; ISRC → 0.95, else fuzzy; MIN_CONFIDENCE gate)
  └─ resolve: GET track/getFileUrl?track_id=&format_id=27&...&request_sig=  (signed)  [X-App-Id + token]
       ├─ 200 + plain url  → inspector check (not preview/range) → SourceResult / StreamUrl
       ├─ UserUnauthenticated / preview / 401 → credentialStore.rotate(); retry once; else null
       └─ FormatRestrictedByFormatAvailability → accept downgraded format (still lossless if fmt 6)
  └─ report success/failure to rateLimiter
```

Download path: `SourceResult.downloadUrl` → existing `LosslessUrlDownloader` (plain fetch, no decrypt key). Stream path: `StreamUrl` → existing progressive-play factory.

## 5. Chain placement
- **Download** (`LosslessSourceRegistry`): last, after `squid_qobuz`, `kennyy_qobuz`, `arcod`, `amz` — qbdlx is redundancy, tried when the others miss/degrade.
- **Stream** (`StreamSourceRegistry`): after kennyy/squid/arcod/amz, before YouTube, in the normal (non-force) branch.
- Both gated by `LosslessSourceHealthGate` so a degraded/dead-token qbdlx auto-skips without user action.

## 6. Error handling
- **Dead/expired token** → `UserUnauthenticated` or 30s preview (`fmt=5`,`range=20-30`). Inspector flags it; credential store rotates to the next pool token and retries once; if the whole pool is dead, source returns null → chain falls through. Health gate cools it down.
- **Region lock** → try the token pool (different countries) before giving up; `Token-Country`-style selection optional v2.
- **Rate limit / ban risk** → conservative `AggregatorRateLimiter` config; a 429 applies backoff. Shared real accounts: bias toward low request rates.
- **Format restriction** → silent hi-res→CD downgrade is accepted (CD FLAC is still lossless); only `fmt=5` MP3/preview is rejected.

## 7. Open items / build inputs
- **Full token pool**: only one token (`jM-6F2Qc…`, FR) was captured. The other 4–5 pool tokens (with their countries) are needed to bundle in `BuildConfig` at build time. User to supply from the qbdlx login page.
- **Token expiry/refresh cadence**: tokens show ~monthly rotation; the paste-to-refresh path covers it, but a future enhancement could detect "whole pool dead" and surface a Settings nudge.
- **Search signature**: verified that `catalog/search` needs no sig from this app_id; if Qobuz tightens this, fall back to the signed `catalog/search` variant.

## 8. Testing
- **Unit (TDD):** signing reproduces known HAR vectors (lock the 4 verified signatures as a regression test); `QbdlxQobuzApiClient` search/getFileUrl parsing via MockWebServer; `QbdlxCredentialStore` rotation (skip dead token → next; pasted token priority); matcher reuse (ISRC boost, fuzzy gate); `QbdlxQobuzSource.resolve` (match → SourceResult; preview → rotate+null).
- **On-device E2E:** download a track → byte-verify `fLaC`; stream a track → audible playback; force whole-pool-dead → graceful skip to next source; verify a region-locked track resolves via a different-country token.

## 9. Out of scope (v1)
- The secure segmented `file/url` instant-stream path (HKDF/AES decrypt + session) — not needed; plain progressive streaming suffices.
- `session/start` / favorites / Qobuz account features.
- Auto-scraping qbdlx's server-rendered token page.
