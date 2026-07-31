# Streaming fast lane + download consent — design

Date: 2026-07-29
Status: approved, ready for implementation planning

Two independent defects, grouped because both are "the guard exists but nothing
calls it" and both are diagnosed from the same reading of the streaming/download
pipeline.

---

## Problem 1 — every playback pays the slow yt-dlp lane

### Evidence

`PreviewUrlExtractor.extractStreamUrl` (`data/download/.../preview/PreviewUrlExtractor.kt:237`)
races two extractors and returns the first win:

- InnerTube player API, ~200 ms–2 s, cap 8 concurrent (`:428`)
- yt-dlp + QuickJS, ~3.6–15 s, cap 1–2 concurrent (`:477`)

`YouTubeStreamResolver.resolve` (`core/media/.../streaming/YouTubeStreamResolver.kt:100-104`)
calls `extractStreamUrlViaYtDlp` (`PreviewUrlExtractor.kt:390`) — **yt-dlp only,
race skipped** — whenever `allowYtDlp = true`. The race is reachable only via
`allowYtDlp = false`, and no live call site passes that: every remaining
occurrence of `allowYtDlp = false` in the repo is a comment. The fast lane is
dead code **for playback** only — the race is still live for search previews
(`SearchPreviewMediaSource.kt:54`, `PreviewPrefetcher.kt:71`), the track-actions
preview (`TrackActionsDelegate.kt:241`) and failed-match auditioning
(`FailedMatchesViewModel.kt:342,587`). Those callers are unaffected by this
change and are useful corroboration that the race works in production.

The bypass was justified (documented 2026-06-08): InnerTube URLs were
PO-token-gated to ~1 MB and returned 403 on full-file requests, so they could
not stream a whole track.

**That justification is stale.** On 2026-06-26 `extractViaYtDlp` learned to pin
`player_client=android_vr` (`PreviewUrlExtractor.kt:490-497`), which returns "a
working itag-251 URL directly — no PO token, no m3u8 manifest, no QuickJS
signature/n-challenge solve". Meanwhile
`InnerTubeClient.AUDIO_VARIANT_ORDER` is still `[IOS]` alone
(`data/ytmusic/.../InnerTubeClient.kt:158-160`), even though the KDoc at
`PreviewUrlExtractor.kt:432-435` claims ANDROID_VR is attempted.

Net: the app spawns a Python interpreter to perform the single HTTPS call
`InnerTubeClient` already knows how to make.

### Key risk reframe

The URLs the app streams today **are** `android_vr` URLs, minted through yt-dlp.
This design does not bet on an unproven URL class; it mints the same URL with a
direct request. The residual risk is only whether `InnerTubeClient` impersonates
`android_vr` correctly (client version, headers) — mechanical, and provable in
one on-device run. On failure the race simply loses and behaviour is identical
to today.

### Design

1. **Add `ANDROID_VR` to `AUDIO_VARIANT_ORDER`**, ordered first, keeping `IOS`
   as the second attempt. `playerForAudio` (`InnerTubeClient.kt:405`) already
   walks the list and accepts the first variant whose response satisfies
   `hasDirectAudioUrl` (`:429`), so no control-flow change is needed.

2. **Validate before use — tail-range probe.** `adaptiveFormats` entries carry
   `contentLength`. After `selectBestAudioUrl` picks a URL, issue one
   `Range: bytes=(contentLength-1)-` request and require 206. A PO-gated URL
   serves its first ~1 MB and then 403s, so a tail probe is the only cheap check
   that distinguishes "playable" from "playable for 15 seconds". ~100 ms against
   ~3.5 s saved. Probe failure → return null → the race falls through to yt-dlp,
   exactly as today.

   This is deliberate insurance, not ceremony: the unguarded version of this
   change is what shipped the June regression, and its failure mode (audio dies
   mid-track) is worse than a slow start.

3. **Point playback at the race.** `YouTubeStreamResolver.resolve` calls
   `extractStreamUrl(videoId, allowYtDlp = true)` instead of
   `extractStreamUrlViaYtDlp(videoId)`. The `allowYtDlp = false` branch stays for
   the fast-only contract (`NoFastStreamException`).

4. **Measure, before and after.** `runYtDlp` already logs
   `yt-dlp: invoking …` and `exit=…` (`PreviewUrlExtractor.kt:539-547`). Capture
   real numbers on device. If the current cost is far above the 3.6 s measured on
   2026-06-26, the dominant term is Chaquopy cold-start or the per-call
   `--remote-components ejs:github` fetch, and cold-start work is a worthwhile
   follow-up rather than something this change subsumes.

### Measured on device 2026-07-29 — the premise was wrong, usefully

Everything above assumed the win was "call InnerTube's ANDROID_VR instead of
spawning Python for the same URL". Measurement said otherwise. Recorded here
because the wrong hypothesis is what led to the instrument that found the real
cause.

| path | outcome | time |
|---|---|---|
| InnerTube, any variant (our own request) | returns a URL, but PO-token-gated: 403 past the head. `AudioUrlTailProbe` rejects it | ~800 ms, wasted |
| yt-dlp `ios` | no usable URLs — YouTube's SABR-only experiment (yt-dlp #12482) | ~2.8 s, wasted |
| yt-dlp `android_vr` | **works**, after two fixes below | **~2.7 s** |
| yt-dlp default client | works, itag 251 opus audio-only | ~12-13 s |

**The actual cause of the slowness had nothing to do with InnerTube.** The June
`android_vr` fast path was dead, from one unread line of yt-dlp stderr:
`Skipping client "android_vr" since it does not support cookies`. yt-dlp answers
`--cookies` + a cookie-incompatible client by discarding the *client*. Every
extraction paid ~3.5 s being refused, then ~11.6 s on the default path — ~15 s.

Fixing that revealed a second layer: `android_vr` no longer advertises itag
251/250, so the June selector failed the attempt outright. With audio-only itags
preferred and `best` as last resort it succeeds in ~2.7 s (three consecutive
tracks: 2.74, 2.71, 2.75), serving itag 18 — combined 360p, ~96k AAC — versus
160k opus from the slow path. Playback verified healthy 85 s in
(`position=84760, error=null`), proving the URL is not range-gated.

**Conclusions for the record:**

- The 2026-06-08 finding still holds. InnerTube URLs remain PO-token-gated, and
  the probe caught it live (`tail probe rejected a gated URL: code=403`, plus a
  `0-byte body` 403 from googlevideo with `c=IOS`). So the probe was not
  belt-and-braces — it is the only reason racing InnerTube is safe at all.
- The race itself is retained: it costs ~800 ms when InnerTube loses and would
  pay off immediately if the gating ever lifts, which the probe now detects
  automatically instead of requiring another on-device investigation.
- Unrelated but surfaced: `qbdlx api call failed status=403 USER_BLOCKED` — a
  pool token is blocked, not merely expired.

### Existing safety nets preserved

`RefreshingDataSourceFactory` re-resolves through the registry on a mid-stream
403, and `StreamUrlCache` never caches a provisional lossy fallback
(`PlayerRepositoryImpl.kt:1324-1328`). Both continue to apply unchanged.

---

## Problem 2 — algorithmic mixes download without consent

### Evidence

The pipeline's intended contract is stated in `DiffWorker.kt:46-54`: mixes are
surface-only, "so an auto-enabled mix never pulls bytes even after the user
switches to Offline and re-syncs". Three defects break it.

1. **Auto-enable.** `defaultSyncEnabled` (`DiffWorker.kt:43-44`) returns `true`
   for `DAILY_MIX` in Online mode, applied at `findOrCreatePlaylist`
   (`:313`). Discovered mixes become sync-enabled without being asked.

2. **The enqueue guard is dead code.** `shouldEnqueueForDownload(type,
   streamingMode)` (`DiffWorker.kt:53-54`) excludes `DAILY_MIX`, is unit-tested
   in `ShouldEnqueueForDownloadTest`, and **is called by nothing but that test**.
   The real enqueue site (`DiffWorker.kt:565`) tests raw `!streamingMode` and
   queues every new track.

3. **The requeue predicate misses `DAILY_MIX`.** `getUnqueuedTrackIds`
   (`DownloadQueueDao.kt:535-560`), which drives the blanket requeue at
   `TrackDownloadWorker.kt:196-208`, requires a sync-enabled active parent and
   excludes `p.type != 'STASH_MIX'` (`:553`) — but not `DAILY_MIX`.

The v0.9.85 sweep cannot rescue any of it: `cancelDownloadsWithNoEnabledPlaylist`
(`:412-424`) spares any track in a playlist with `sync_enabled = 1`, which these
mixes are. And because mixes rotate, each new one pulls a fresh batch — the
"random downloads" users describe.

`DownloadQueueDao.kt:540-547` records the same failure mode occurring before (a
Replay Mix queueing 90 tracks) and being patched narrowly with an `is_active`
guard rather than at the type level. This design fixes it at the type level.

### Why removing the auto-enable costs nothing

`getAllVisible` (`PlaylistDao.kt:279-294`) renders a playlist when
`is_active = 1 AND (sync_enabled = 1 OR has a downloaded track OR
(includeStreamable AND has a streamable track))`.

In Online mode `includeStreamable = true`, so a discovered mix with
`sync_enabled = 0` **still surfaces** — its tracks are streamable. The
auto-enable's stated purpose (surface immediately, download nothing) is already
delivered by the streamable escape hatch, making the auto-enable redundant for
surfacing and load-bearing only for the harm. In Offline mode the mix correctly
disappears: a mix with nothing downloaded is not playable there.

Therefore `discoverAutoMixes` stays **ON**. Discovery is a working feature whose
surfacing survives this change; flipping its default would remove a feature to
fix a bug that isn't the feature's fault, and would confound the signal on
whether #335/#344 are resolved.

### Design

One definition of "wanted", used by all three sites:

> A track is download-eligible when it is a member (`removed_at IS NULL`) of a
> playlist with `is_active = 1 AND sync_enabled = 1` whose `type` is not an
> algorithmic or generated mix (`STASH_MIX`, `DAILY_MIX`).

Changes:

1. `defaultSyncEnabled` → always `false`. New discoveries are opt-in in both
   modes.
2. `DiffWorker.kt:565` → gate on
   `shouldEnqueueForDownload(localPlaylist.type, streamingMode)` instead of
   `!streamingMode`. The helper and its test already exist; this is the wiring.
3. `getUnqueuedTrackIds` → `p.type NOT IN ('STASH_MIX', 'DAILY_MIX')`.
4. `cancelDownloadsWithNoEnabledPlaylist` → apply the same type exclusion to its
   "spared" subquery. Without this, a track whose only sync-enabled parent is a
   mix keeps a PENDING row the drain will happily service. This also closes the
   `StashMixRefreshWorker.kt:691` path, where generated mixes are created with
   `syncEnabled = true` hardcoded.

### No migration required

Existing auto-enabled mixes keep `sync_enabled = 1`; that only affects
visibility, which is harmless. Because changes 2–4 exclude mixes **by type**,
no new rows are created regardless of the stale flag, and the corrected sweep
evicts the accumulated phantom rows on the next `TrackDownloadWorker` run.

**Nothing deletes a downloaded file.** The cleanup evicts queue rows only. Users
who deliberately enabled a mix keep their downloaded audio.

---

## Testing

Red-green required on every behavioural fix: assert the test fails against
current `master` before the fix, passes after.

- `DiffWorker`-level test: a `DAILY_MIX` playlist in Offline mode produces zero
  `download_queue` rows; a `CUSTOM` playlist still produces rows. Fails today.
- DAO tests (in-memory Room): `getUnqueuedTrackIds` and
  `cancelDownloadsWithNoEnabledPlaylist` for a track whose only enabled parent is
  a `DAILY_MIX`, a `STASH_MIX`, and a real playlist.
- `PlaylistDao.getAllVisible` test pinning the claim this design rests on: a
  `sync_enabled = 0` `DAILY_MIX` with streamable tracks is visible when
  `includeStreamable = true`, hidden when `false`.
- Tail-range probe: unit test over a fake HTTP layer (206 → accept, 403 →
  reject, missing `contentLength` → accept, since absence is not evidence of
  gating).
- On-device: streaming resolve latency before/after from the existing
  `yt-dlp: invoking` / `exit=` log timestamps, plus confirmation that a
  `qbdlx`-miss track still plays through the YT fallback.

## Out of scope

- `YtLibraryBackfillWorker.kt:329-336` deletes a downloaded file and re-queues it
  when the YT source is non-canonical. Nothing currently schedules it (no
  `enqueueUniqueWork` call exists) — it is dormant. It stays dormant and behind
  explicit user action; re-arming it is a separate decision.
- yt-dlp cold-start / `--remote-components` work, pending the measurement in
  Problem 1 step 4.
- #264 (slow offline loads) and #334 (streamed tracks skipping) are plausibly
  downstream of the slow lane but unproven; re-check after this ships rather
  than assuming.

## Issues addressed

- **#368** — unwanted downloads of user playlists and Spotify mixes. Direct fix.
- **#335 / #344** — unwanted playlists surfacing. Same root (auto-enable);
  surfacing behaviour is deliberately unchanged, so these are re-evaluated after
  the download harm stops.
- **#210** — "Process ID already present" was already fixed by the `null`
  processId at `PreviewUrlExtractor.kt:540-543`; no further work here.
