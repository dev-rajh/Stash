# v0.9.11 — Lossless Quality Controls + Per-Track Quality Info

**Date:** 2026-05-04
**Status:** Design
**Branch:** `feat/v0.9.11-quality-controls` (worktree path: `.worktrees/v0.9.11-quality-controls`)

## Problem

Stash v0.9.10 ships with a working two-source lossless pipeline (qobuz.squid.wtf + qobuz.kennyy.com.br) that always requests the maximum-quality tier from each operator (`QobuzQuality.FLAC_HIRES_192`, i.e. 24-bit/192 kHz FLAC). For an audiophile user this is the right default — but a typical 4-minute track at 24/192 is ~140 MB on disk, vs ~28 MB at CD quality (16-bit/44.1 kHz). For users with smaller phones or storage budgets, this is a 5× cost they can't currently dial down.

Two related gaps surface alongside the same theme:

1. **No way to fine-tune lossless quality.** The Settings UI has a binary lossless toggle plus a five-tier `QualityTier` enum that only governs yt-dlp downloads. The lossless source path is hardcoded to Max regardless. Users who'd prefer 24/96 or 16/44.1 have no control.
2. **No per-track visibility into delivered quality.** A user who selects "Hi-Res" can't verify on a per-track basis whether they actually got 24/96 (Qobuz had a Hi-Res master) or fell back to CD (no Hi-Res available). The Library list and Now Playing screen show only a generic `FLAC` badge.

This spec addresses both: a user-controlled lossless quality tier plus per-track quality persistence and display surfaces. It deliberately scopes out source-management UI (per-source rows, priority reorder, source health dashboard) — those belong to a v0.9.12 release dedicated to source administration.

## Goals

- The user can choose between three lossless quality tiers in Settings (`CD` / `Hi-Res` / `Max`) and the choice flows through to both `QobuzSource` and `KennyySource` via the API's `quality` parameter.
- Fresh installs default to `Hi-Res` (24-bit/96 kHz) — a sensible-storage middle ground. v0.9.8+ users with `losslessEnabled = true` already saved keep `Max` via DataStore preservation.
- Every newly-downloaded lossless track records its delivered bit-depth + sample-rate; existing tracks get backfilled via a one-shot WorkManager sweep + a manual Settings button.
- The Now Playing screen shows the actual delivered quality (`FLAC · 24-bit/96 kHz · 4233 kbps`).
- The Library list FLAC badge upgrades to `FLAC 24/96` or `FLAC 24/192` for Hi-Res tracks; stays plain `FLAC` for CD-quality (visual distinction without crowding rows).
- No source-management UI changes. No filter chip changes (deferred). No transcoding or codec re-encoding.

## Non-goals

- **No filter chip split** for Hi-Res vs CD FLAC (deferred — was originally Item 3 of the v0.9.11 brainstorm; cut after critique because the badge upgrade alone gives at-a-glance visibility).
- **No re-download triggered by quality-tier changes.** Setting changes are forward-looking — existing tracks stay at whatever quality they came down at. A user who switches from Max to CD and wants to reclaim storage can manually delete + re-download (out of scope).
- **No changes to the yt-dlp `QualityTier` enum.** That governs the lossy YouTube path and is independent of the new lossless quality tier.
- **No transcoding.** v0.9.11 does not modify downloaded files. Codec re-encoding (FLAC level 8 compression, Opus transcoding) is deferred to a future release if storage becomes a real complaint.
- **No per-source UI** in Settings. Source-management UX (per-source rows, priority reorder, status display) is v0.9.12+.
- **No "Track details" screen.** Long-press → bottom sheet with full metadata is a v0.9.12 polish item; v0.9.11 surfaces only the headline `codec · bit-depth/sample-rate · bitrate` line on Now Playing.
- **No lossy-format quality probe parity.** Lossy formats (Opus, MP3, AAC) get their existing codec + bitrate display; no bit-depth columns populated for them.

## Design

### 1. Data model

#### 1.1 New enum: `LosslessQualityTier`

Location: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessQualityTier.kt`

```kotlin
enum class LosslessQualityTier(val qobuzCode: Int, val displayLabel: String, val sizeHint: String) {
    CD(qobuzCode = 6,  displayLabel = "CD (16-bit/44.1 kHz)",  sizeHint = "~28 MB / 4 min"),
    HI_RES(qobuzCode = 7,  displayLabel = "Hi-Res (24-bit/96 kHz)", sizeHint = "~70 MB / 4 min"),
    MAX(qobuzCode = 27, displayLabel = "Max (24-bit/192 kHz)",  sizeHint = "~140 MB / 4 min");
}
```

Maps directly to existing `QobuzQuality` integer codes. The `displayLabel` and `sizeHint` are surfaced in the Settings radio rows. `MP3_320 (5)` is intentionally excluded — this is the *lossless* quality picker; lossy is governed by the existing `QualityTier` enum.

#### 1.2 New DataStore field on `LosslessSourcePreferences`

```kotlin
private val qualityTierKey = stringPreferencesKey("lossless_quality_tier")

val qualityTier: Flow<LosslessQualityTier> = context.losslessDataStore.data.map { prefs ->
    prefs[qualityTierKey]
        ?.let { runCatching { LosslessQualityTier.valueOf(it) }.getOrNull() }
        ?: defaultQualityTier(prefs)
}

suspend fun qualityTierNow(): LosslessQualityTier = qualityTier.first()

suspend fun setQualityTier(tier: LosslessQualityTier) {
    context.losslessDataStore.edit { prefs -> prefs[qualityTierKey] = tier.name }
}

/**
 * Default selection when no value is saved:
 *   - HI_RES for fresh installs (no `enabled` key set yet)
 *   - MAX for users who explicitly enabled lossless in v0.9.8+
 *     (their `enabled` key is true; they get the historical Max behaviour)
 *   - HI_RES for v0.9.8+ users who explicitly disabled lossless
 *     (less storage-aware default; they're not downloading lossless anyway)
 */
private fun defaultQualityTier(prefs: Preferences): LosslessQualityTier {
    val enabledExplicitlyTrue = prefs[enabledKey] == true
    return if (enabledExplicitlyTrue) LosslessQualityTier.MAX else LosslessQualityTier.HI_RES
}
```

`setQualityTier` is idempotent and only writes when the user explicitly picks a tier in Settings.

#### 1.3 Schema migration v16 → v17

Two new nullable columns on `tracks`:

```sql
ALTER TABLE tracks ADD COLUMN bits_per_sample INTEGER;
ALTER TABLE tracks ADD COLUMN sample_rate_hz INTEGER;
```

Both nullable — `NULL` means "unknown" (existing tracks pre-backfill, lossy tracks where bit-depth is meaningless, files with corrupt or unparseable headers).

`TrackEntity` field additions:

```kotlin
@ColumnInfo(name = "bits_per_sample")
val bitsPerSample: Int? = null,

@ColumnInfo(name = "sample_rate_hz")
val sampleRateHz: Int? = null,
```

Auto-generated Room migration (Room reads the new entity declarations and emits the `ALTER TABLE` SQL). Schema file `core/data/schemas/com.stash.core.data.db.StashDatabase/17.json` will be committed by Room's annotation processor.

### 2. API plumbing

`QobuzSource.resolve` and `KennyySource.resolve` both currently hardcode the quality:

```kotlin
val requestedQuality = QobuzQuality.FLAC_HIRES_192
val download = callLimited { apiClient.getFileUrl(best.first.id, requestedQuality) }
```

Both sources gain a `LosslessSourcePreferences` constructor parameter (already present on QobuzSource via the v0.9.10 work; KennyySource will gain it). Replace the hardcoded value with:

```kotlin
val tier = losslessPrefs.qualityTierNow()
val download = callLimited { apiClient.getFileUrl(best.first.id, tier.qobuzCode) }
```

The actual delivered quality may be lower than requested (a track with no Hi-Res master returns CD even when the API was asked for `quality=27`). The `match.format.bitsPerSample` / `sampleRateHz` returned by the source reflects the actual delivery — that's what we persist (Section 3), not the requested tier.

### 3. Persistence on new downloads

#### 3.1 `TrackDao.markAsDownloaded` parameter additions

Add two optional params:

```kotlin
@Query("""
    UPDATE tracks SET
        is_downloaded = 1,
        file_path = :filePath,
        file_size_bytes = :fileSizeBytes,
        sample_rate_hz = COALESCE(:sampleRateHz, sample_rate_hz),
        bits_per_sample = COALESCE(:bitsPerSample, bits_per_sample)
    WHERE id = :trackId
""")
suspend fun markAsDownloaded(
    trackId: Long,
    filePath: String,
    fileSizeBytes: Long,
    sampleRateHz: Int? = null,
    bitsPerSample: Int? = null,
)
```

`COALESCE` preserves any previously-set value when the new arg is `null` — important so the yt-dlp path (which doesn't supply bit-depth) doesn't wipe out values populated by an earlier lossless download.

#### 3.2 `DownloadManager.tryLosslessDownload` plumbing

At the existing `markAsDownloaded` call site, pass through:

```kotlin
trackDao.markAsDownloaded(
    trackId = track.id,
    filePath = committed.filePath,
    fileSizeBytes = committed.sizeBytes,
    sampleRateHz = match.format.sampleRateHz.takeIf { it > 0 },
    bitsPerSample = match.format.bitsPerSample.takeIf { it > 0 },
)
```

The `.takeIf { it > 0 }` converts the existing `Int = 0` "unknown" sentinel in `AudioFormat` to `null` for DB storage.

#### 3.3 `AudioDurationExtractor.extract` — sample-rate + bit-depth for lossy paths

Extend `AudioMetadata` with two new nullable fields:

```kotlin
data class AudioMetadata(
    val durationMs: Long,
    val bitrateKbps: Int,
    val format: String,
    val sampleRateHz: Int? = null,
    val bitsPerSample: Int? = null,
)
```

In `extract(filePath: String): AudioMetadata?`, after the existing `MediaMetadataRetriever` block, also probe via `MediaExtractor`:

- For all formats: query `MediaFormat.KEY_SAMPLE_RATE` (works on all API levels). Use the result for `sampleRateHz`.
- For API 30+: query `MediaFormat.KEY_BITS_PER_SAMPLE`. Use the result for `bitsPerSample`.
- For FLAC files on API <30 only: parse FLAC STREAMINFO header (bytes 18–21 of the file) to extract bit-depth. ~15 lines of binary parsing; FLAC spec is stable.

Existing `format = "opus"|"mp3"|...` detection is unchanged.

### 4. Backfill for existing tracks

#### 4.1 New worker: `QualityInfoBackfillWorker`

Location: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/QualityInfoBackfillWorker.kt`

```kotlin
@HiltWorker
class QualityInfoBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val audioExtractor: AudioDurationExtractor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val candidates = trackDao.getMissingQualityInfo()
        if (candidates.isEmpty()) return Result.success()
        Log.i(TAG, "QualityInfoBackfill: ${candidates.size} tracks to probe")
        var updated = 0
        for (track in candidates) {
            val filePath = track.filePath ?: continue
            val meta = audioExtractor.extract(filePath) ?: continue
            if (meta.sampleRateHz != null || meta.bitsPerSample != null) {
                trackDao.updateQualityInfo(
                    trackId = track.id,
                    sampleRateHz = meta.sampleRateHz,
                    bitsPerSample = meta.bitsPerSample,
                )
                updated++
            }
        }
        Log.i(TAG, "QualityInfoBackfill complete: updated $updated/${candidates.size}")
        // Re-enqueue if there are more candidates than we processed in this batch
        // (predicate filters them out next time around so we eventually drain to zero).
        if (candidates.size >= BATCH_LIMIT) {
            WorkManager.getInstance(applicationContext).enqueue(
                OneTimeWorkRequestBuilder<QualityInfoBackfillWorker>().build()
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "QualityInfoBackfill"
        const val BATCH_LIMIT = 500
    }
}
```

#### 4.2 New DAO methods

```kotlin
@Query("""
    SELECT * FROM tracks
    WHERE is_downloaded = 1
      AND file_path IS NOT NULL
      AND file_format IN ('flac', 'alac', 'wav', 'ape', 'tta', 'wv', 'aiff')
      AND (bits_per_sample IS NULL OR sample_rate_hz IS NULL)
    LIMIT 500
""")
suspend fun getMissingQualityInfo(): List<TrackEntity>

@Query("""
    UPDATE tracks SET
        sample_rate_hz = :sampleRateHz,
        bits_per_sample = :bitsPerSample
    WHERE id = :trackId
""")
suspend fun updateQualityInfo(trackId: Long, sampleRateHz: Int?, bitsPerSample: Int?)
```

#### 4.3 Triggers

**Auto-trigger on first launch of v0.9.11.** In `StashApplication.onCreate`:

```kotlin
val prefs = applicationContext.getSharedPreferences("stash_one_time_flags", Context.MODE_PRIVATE)
if (!prefs.getBoolean("quality_backfill_done_v17", false)) {
    WorkManager.getInstance(applicationContext).enqueue(
        OneTimeWorkRequestBuilder<QualityInfoBackfillWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
    )
    // Flag is set when the worker reports Result.success() once;
    // the next subsequent launch sees the flag and skips enqueueing.
    prefs.edit().putBoolean("quality_backfill_done_v17", true).apply()
}
```

The flag is set immediately on enqueue (not after worker completion) — if the worker is interrupted, the next app launch won't re-enqueue, but the manual Settings button covers that gap. The worker's own predicate (`bits_per_sample IS NULL OR sample_rate_hz IS NULL`) is idempotent — re-running drops to no-op once everything is populated.

**Manual button in Settings → Library Health.** A new row mirroring the existing "Run backfill" pattern. Tap → enqueue the same worker (no constraints — user is opting in). Snackbar: `"Refreshing quality info — running in background."`

### 5. UI surfaces

#### 5.1 Settings → Audio Quality → Lossless quality picker

Slots into the existing v0.9.8 lossless block. Visible only when `losslessEnabled = true`. Placement: below the lossless toggle subtitle, above the captcha "Connect to squid.wtf" block.

Three-tier `RadioButton` group:

```
Lossless quality
○ Max (24-bit/192 kHz)        ~140 MB / 4 min
● Hi-Res (24-bit/96 kHz)      ~70 MB / 4 min
○ CD (16-bit/44.1 kHz)        ~28 MB / 4 min
```

Standard Material3 `selectableGroup()` + `RadioButton` row pattern (matches the existing Download quality radio above the divider). On selection, calls `viewModel.onLosslessQualityTierChanged(tier)` → `losslessPrefs.setQualityTier(tier)`. The setting takes effect on next download — no immediate user-visible change beyond the radio state itself.

#### 5.2 Now Playing → quality line

Single `Text` line below the artist name in the Now Playing track-info column. Style `bodySmall`, color `onSurfaceVariant`.

Format determined by available data:

```kotlin
@Composable
fun trackQualityText(track: Track): String? {
    val codec = track.fileFormat.takeIf { it.isNotBlank() }?.uppercase() ?: return null
    val bitDepth = track.bitsPerSample
    val sampleRateKHz = track.sampleRateHz?.let { it / 1000.0 }
    val bitrate = track.qualityKbps.takeIf { it > 0 }
    return buildList {
        add(codec)
        if (bitDepth != null && sampleRateKHz != null) {
            add("${bitDepth}-bit/${"%.1f".format(sampleRateKHz)} kHz")
        }
        if (bitrate != null) add("$bitrate kbps")
    }.joinToString(" · ")
}
```

Examples:
- All four fields known: `FLAC · 24-bit/96.0 kHz · 4233 kbps`
- Codec + bitrate only (yt-dlp track on API <30 device with no FLAC fallback): `OPUS · 160 kbps`
- Codec only: `FLAC` (data not yet backfilled)

#### 5.3 Library list FLAC badge upgrade

Existing `FlacBadge` composable in `core/ui/src/main/kotlin/com/stash/core/ui/components/FlacBadge.kt`. Currently displays a static "FLAC" pill.

Change to a parameterised version:

```kotlin
@Composable
fun FlacBadge(
    bitsPerSample: Int? = null,
    sampleRateHz: Int? = null,
    modifier: Modifier = Modifier,
) {
    val text = flacBadgeText(bitsPerSample, sampleRateHz)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

internal fun flacBadgeText(bitsPerSample: Int?, sampleRateHz: Int?): String = when {
    bitsPerSample == null || sampleRateHz == null -> "FLAC"
    bitsPerSample <= 16 && sampleRateHz <= 44_100 -> "FLAC"
    else -> "FLAC ${bitsPerSample}/${sampleRateHz / 1000}"
}
```

Examples:
- CD-quality FLAC (16/44.1) → `FLAC` (no qualifier — distinguishes from Hi-Res at a glance without crowding)
- Hi-Res 24/96 → `FLAC 24/96`
- Max Hi-Res 24/192 → `FLAC 24/192`
- Pre-backfill or unknown → `FLAC`

Existing call sites (`FlacBadge()` with no args) work unchanged because both new params have defaults; they get the plain "FLAC" rendering. Library list ViewModel updates the call sites that have `Track` in scope to pass `track.bitsPerSample` and `track.sampleRateHz`.

#### 5.4 Settings → Library Health → "Refresh quality info"

New row in the existing `LibraryHealthScreen.kt`. Mirrors the existing "Run backfill (file sizes)" pattern row exactly — same `Column { Text(title), Text(subtitle), Button("Refresh") }` shape.

```
┌─────────────────────────────────────────────────┐
│ Refresh quality info                            │
│ Re-extract bit-depth + sample-rate for FLAC     │
│ tracks where it's missing.                      │
│                              [Refresh]          │
└─────────────────────────────────────────────────┘
```

`onClick` enqueues the same `QualityInfoBackfillWorker` (no constraints). Snackbar confirmation; no progress UI.

## Risks

| Risk | Mitigation |
|---|---|
| **Schema migration v17 fails on user's device** (Room migration write fails mid-way, e.g., disk full). | Room handles ALTER TABLE atomically; failure rolls back the schema bump. The new columns simply don't exist on rolled-back installs and existing code paths (which don't reference them) keep working. v0.9.11 install retries on next launch. |
| **`MediaExtractor` throws on a corrupt or unsupported file.** | `AudioDurationExtractor.extract` already catches all exceptions and returns null. New code follows the same pattern. Track row keeps its previous (or NULL) quality info. |
| **FLAC STREAMINFO parser misreads byte alignment.** | Use a unit-tested helper. The FLAC spec is stable (frozen in 2008); byte layout doesn't change. Worst case, a misread returns implausible values (e.g., bit_depth = 99) — add a sanity guard `if (bitsPerSample !in 8..32) return null`. |
| **Default-tier logic surprises some upgraders.** A user who had `losslessEnabled = false` saved in v0.9.8 (explicit opt-out) gets `HI_RES` as their default tier when they later enable it. Differs from the `MAX` they'd have gotten in v0.9.10. | This is the intended design. They explicitly disabled lossless, so they don't have a strong "I want Max" signal. Showing them a sensible-storage default (Hi-Res) when they later re-enable matches new-user behaviour. They can pick Max in the radio if they want. |
| **`MediaFormat.KEY_BITS_PER_SAMPLE` returns -1 on API 30+ for FLAC.** Some Android implementations don't report bit-depth via this key even at API 30. | Code path falls back to FLAC STREAMINFO parser when KEY_BITS_PER_SAMPLE returns -1 or throws. Same parser, just always-on for FLAC. |
| **Now Playing line wraps to two lines on narrow phones.** Long codec names + spec + bitrate could overflow ~360dp width screens. | `Text(maxLines = 1, overflow = TextOverflow.Ellipsis)`. The ellipsis loses the bitrate first since it's the rightmost field. Acceptable degradation. |
| **Backfill worker on a 5,000-track library makes 10 batch runs.** WorkManager re-enqueues automatically per the worker's own logic. | Acceptable — runs in background, low priority. User experience is "badges fill in over the next few hours." Manual button covers any track that slipped through. |
| **Existing FlacBadge call sites pass no track context.** Some usages of `FlacBadge()` may be in Composables without a `Track` in scope. | New params default to null → falls back to plain "FLAC" rendering. Backwards compatible. Audit call sites during implementation; update those that have `Track` in scope to pass quality info. |

## Testing

### Unit tests

None added. Pattern follows project precedent (`HomeViewModel`, `MusicRepositoryImpl`, `QobuzSource`, `KennyySource` are all untested at the unit level). The discipline is on-device acceptance.

One exception: `flacBadgeText(bitsPerSample, sampleRateHz)` and `trackQualityText(track)` are pure functions with no Android dependencies — they CAN be unit-tested cheaply if a test infrastructure is set up. Optional, not required for ship.

### Manual acceptance (signed release sideload)

1. **Settings → Audio Quality → Lossless toggle ON.** Confirm three-tier radio appears between subtitle and Connect button. Default selection is Hi-Res for fresh installs OR Max for v0.9.8+ users with `losslessEnabled = true` already saved.
2. **Pick CD tier.** Sync a track. Confirm log line `kennyy_qobuz: requested quality=6` (or `quality=7` for Hi-Res, `quality=27` for Max). File on disk is plain FLAC 16/44.1.
3. **Pick Max tier.** Sync a track that has a Hi-Res master on Qobuz. Confirm `match.format` returns `bitsPerSample=24, sampleRateHz=192000`. DB row has `bits_per_sample=24, sample_rate_hz=192000`. Library badge shows `FLAC 24/192`.
4. **Now Playing.** Tap a downloaded Hi-Res track. Confirm quality line shows `FLAC · 24-bit/96.0 kHz · 4233 kbps` (numbers vary by track).
5. **Library list.** Scroll the Tracks tab. Confirm CD-quality FLAC tracks show `FLAC` badge; Hi-Res tracks show `FLAC 24/96` or `FLAC 24/192`.
6. **First-launch auto-backfill.** Cold-install v0.9.11 over v0.9.10. Wait ~5 minutes (or trigger manually via Library Health). Confirm logcat shows `QualityInfoBackfill: N tracks to probe` and `QualityInfoBackfill complete: updated M/N`. Library badges update for previously-FLAC-without-detail tracks.
7. **Manual button.** Settings → Library Health → "Refresh quality info" → Refresh. Snackbar appears; logcat shows worker enqueue. If everything is already populated, worker exits immediately with `0 tracks to probe`.
8. **Setting change is forward-only.** Pick CD tier. Existing Max-quality tracks on disk are unchanged. Only new downloads use CD tier.

### Regression check

- yt-dlp (lossy) downloads still work — `markAsDownloaded` accepts null bit-depth and doesn't blow up.
- Existing `FlacBadge()` call sites without quality args render as plain "FLAC" pill (unchanged).
- The Now Playing screen shows codec + bitrate for lossy tracks even without bit-depth/sample-rate (the function returns a partial line, not nothing).
- v0.9.10's KennyyQobuzSource + sticky-disable + rate-tuning all still work.

## Out of scope

- Per-source UI in Settings (squid + kennyy as sibling rows with status). v0.9.12.
- Source priority drag-reorder. v0.9.12+.
- "Track details" long-press sheet with full file metadata. v0.9.12 polish.
- Filter chip split (Hi-Res FLAC vs CD FLAC). Deferred unless a real user request surfaces.
- File-size estimates that adapt to actual track length. Settings shows "~X MB / 4 min" approximations.
- Re-download triggered by quality-tier setting change. Forward-only.
- Codec re-encoding (FLAC level 8, Opus transcoding). Future release if storage becomes a complaint.
- Lossy bit-depth display. Opus / MP3 / AAC don't have meaningful bit-depth; UI shows codec + bitrate only.
- Settings row that visualises currently-stored library quality breakdown (e.g., "120 Hi-Res tracks, 250 CD tracks, 1500 Opus tracks"). Nice-to-have, defer.

## Ship as

v0.9.11. Single coherent release: "fine-grained lossless quality controls + per-track quality visibility." Bumps `versionCode 47 → 48` and `versionName "0.9.10" → "0.9.11"` in `app/build.gradle.kts`.
