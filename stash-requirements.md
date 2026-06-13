# Stash — Requirements & Bug Tracker
**Branch:** master | **Device:** Android 16 (real device) | **IDE:** Android Studio Panda 4
**Download folder:** Custom path on internal storage | **YTM:** Not used (Spotify only)

> Kotlin · Jetpack Compose · Material 3 · Room · ExoPlayer/Media3 · WorkManager · AES-256-GCM

---

## Architecture Overview

```
Stash/
├── app/           # Entry point, DI, MainActivity, NavGraph
├── core/          # Models, DB entities, Room DAOs, shared utilities
├── data/          # Repositories + API clients (Spotify / YouTube / Qobuz)
├── feature/       # UI modules: home, library, player, sync, settings, queue
├── build-logic/   # Gradle convention plugins
└── docs/          # Screenshots
```

| Layer | Key Responsibility |
|---|---|
| `data/spotify` | Fetch liked songs + playlists via sp_dc cookie (paginated) |
| `data/download` | WorkManager pipeline: match → fetch YouTube → write file |
| `data/matching` | Album-first pipeline: Spotify metadata → YouTube search → score → resolve |
| `core/db` | Room: TrackEntity, PlaylistEntity, PlaylistTrackEntity |
| `feature/player` | ExoPlayer/Media3 session, queue, EQ, AudioEffect |
| `feature/sync` | SyncWorker, DataStore preferences, progress events |
| `feature/library` | Library browse, sort, filter, playlist views |

---

## Download + Matching Pipeline (How It Works)

```
Spotify track metadata
    │
    ▼
[1] Try Qobuz/FLAC source first
    │  success → download FLAC
    │  fail ↓
[2] YouTube search: "{artist} {title} {album}"
    │
    ▼
[3] Pick top YouTube result  ← BUG: no scoring/filtering here
    │
    ▼
[4] Download Opus audio
    │
    ▼
[5] Tag file with Spotify metadata (ffmpeg)
    │
    ▼
[6] Write to folder → update Room DB
```

**The problem is step 3.** There is no filter between "got YouTube results" and "pick top result."
YouTube's top result for a song is frequently the music video, a live version, or a clean edit
— especially for popular artists where the MV has more views than the audio track.

---

## All Issues — Master List

### BUGS — Critical

| ID | Title | Source | Key Info |
|---|---|---|---|
| B-01 | Wrong song version downloaded (MV, live, clean, intro) | GitHub #12, #20, Raj | **New detail below** |
| B-02 | Random unrelated YouTube track downloaded | Raj / #107 | No scoring at all |
| B-03 | 0 tracks downloaded — silent failure | Raj / #111, #75 | SAF permission likely |
| B-04 | Playlist ordering wrong across all playlists | Raj | Missing ORDER BY |
| B-05 | Public playlists + folder playlists not visible | Raj | Owner filter too strict |
| B-06 | Existing downloads not detected after reinstall | Raj | No folder rescan |
| B-07 | Liked Songs not syncing | GitHub #21 | Pagination / 401 |
| B-08 | Playlists not updating after Spotify changes | Raj / #57 | Additive-only sync |

### BUGS — Medium

| ID | Title | Source |
|---|---|---|
| B-09 | Mini player visible when Now Playing is open | Raj |
| B-10 | Now Playing shows no elapsed / remaining time | Raj |
| B-11 | Equalizer toggle resets | GitHub #22 |
| B-12 | Multiple playback features broken | GitHub #23 |
| B-13 | Sync preferences don't match what actually syncs | GitHub #10 |
| B-14 | Library sort options broken (Recently Added = Most Played) | Raj |
| B-15 | Generic audio issue | GitHub #6 |

### FEATURE REQUESTS

| ID | Title | Priority | Source |
|---|---|---|---|
| F-01 | **Manual song search + pick for unmatched/wrong tracks** | 🔴 Critical | GitHub #19, Raj |
| F-02 | **Sleep timer** | 🟡 Medium | Raj |
| F-03 | **Re-check + replace wrong versions on sync (toggle)** | 🟡 Medium | Raj |
| F-04 | Download playlists individually (per-playlist) | 🟡 Medium | Raj |
| F-05 | Manual Spotify playlist refresh button | 🟡 Medium | Raj |
| F-06 | Library UI/UX overhaul | 🟡 Medium | Raj |
| F-07 | Replace "Shuffle" text with icon in Library | 🟢 Low | Raj |
| F-08 | Clear sync history from Settings | 🟢 Low | Raj |
| F-09 | Android Auto support | 🟢 Low | GitHub #18 |
| F-10 | File format choice (MP3/AAC/FLAC beyond Opus) | 🟢 Low | GitHub #11 |

---

## Deep-Dive: B-01 + B-02 — Wrong Version / Wrong Track (Updated)

### What Raj reported specifically:
- Music video versions instead of standard audio
- Clean versions instead of explicit versions
- Alternate edits with cinematic/spoken intros from MV
- Songs starting with long non-music intro (MV cold open playing instead of album track)

### Why this happens — YouTube search ranking:
YouTube's search is optimised for views/engagement, not audio purity.
For a song like "Blinding Lights" by The Weeknd:
- Result #1: Official Music Video (600M views) ← Stash picks this
- Result #2: Official Audio (100M views)
- Result #3: Live at Super Bowl
- Result #4: Clean version
- Result #5: Topic channel (auto-generated, pure audio) ← ideal

The current pipeline has no filter between "got results" and "use result[0]".

### Signals available to filter correctly:

| Signal | How to use |
|---|---|
| Duration delta | Reject if `abs(ytDuration - spotifyDurationMs/1000) > 8s` |
| Title keywords | Penalise/reject if title contains: "music video", "mv", "official video", "live", "live at", "acoustic", "clean", "radio edit", "intro", "extended" |
| Channel type | Prefer "Topic" channels (format: "Artist - Topic") — these are YouTube Music auto-generated, pure audio |
| View count (inverse) | Topic channels always have fewer views than MVs; don't rank by views |
| ISRC match | Spotify provides ISRC on most tracks; YouTube Music API can match by ISRC — strongest signal |

### Scoring approach (fix for B-01 + B-02):

```kotlin
data class MatchCandidate(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val viewCount: Long,
)

fun score(candidate: MatchCandidate, spotifyTrack: SpotifyTrack): Int {
    var score = 100

    // Duration check — hard reject if too far off
    val durationDiff = abs(candidate.durationSeconds - spotifyTrack.durationMs / 1000)
    if (durationDiff > 8) return -999 // reject

    // Penalise bad title keywords
    val badKeywords = listOf(
        "music video", " mv", "official video", "official mv",
        "live", "live at", "live from", "acoustic", "session",
        "clean", "clean version", "radio edit", "extended",
        "intro version", "video version", "full video"
    )
    val titleLower = candidate.title.lowercase()
    for (kw in badKeywords) {
        if (kw in titleLower) score -= 40
    }

    // Strongly prefer Topic channels
    if (candidate.channelName.endsWith("- Topic")) score += 50

    // Penalise if channel name doesn't contain artist name at all
    val artistLower = spotifyTrack.artist.lowercase()
    if (artistLower !in candidate.channelName.lowercase()) score -= 20

    return score
}
```

Pick the highest-scoring candidate above a minimum threshold.
If nothing passes the threshold → mark as UNMATCHED → surface in F-01 flow.

---

## Deep-Dive: F-01 — Manual Search + Pick for Unmatched/Wrong Tracks

### User flow:
```
Sync Tab / Library
    │
    ├── "X tracks couldn't be matched" card  (already exists for unmatched)
    │        │
    │        └── Tap track row
    │                │
    │                ▼
    │         ManualSearchSheet (BottomSheet)
    │              ├── Search bar pre-filled with "Artist Title"
    │              ├── User edits query (e.g. removes "Official Video")
    │              ├── Results list: title / channel / duration / thumbnail
    │              ├── Tap result → confirm dialog
    │              └── Confirm → enqueue download with pinned videoId
    │
    └── Also reachable from: long-press any downloaded track → "Fix match"
```

### What "pinned" means in the DB:
```kotlin
// Add to TrackEntity:
val pinnedYoutubeVideoId: String? = null
// When non-null, the matching pipeline skips search entirely
// and uses this videoId directly — user's choice is preserved on re-sync
```

### Re-sync behaviour with pinned tracks:
- On sync, if `pinnedYoutubeVideoId != null` → skip matching, go straight to download
- Only cleared if user explicitly taps "Reset match" on that track

---

## Deep-Dive: F-02 — Sleep Timer

### How it works:
- User sets a duration (15 / 30 / 45 / 60 / 90 min, or custom)
- CountDownTimer runs in PlayerService (survives screen off)
- When timer fires: fade out audio over 10s → pause → optionally show notification "Sleep timer ended"
- Cancel button visible in Now Playing screen and notification while timer is active

### Implementation:
```kotlin
// In PlayerService:
private var sleepTimerJob: Job? = null

fun startSleepTimer(durationMs: Long) {
    sleepTimerJob?.cancel()
    sleepTimerJob = serviceScope.launch {
        delay(durationMs - 10_000) // fade starts 10s before end
        // Fade volume from 1.0 to 0.0 over 10s
        for (i in 100 downTo 0) {
            player.volume = i / 100f
            delay(100)
        }
        player.pause()
        player.volume = 1.0f // reset for next session
        _sleepTimerActive.value = false
    }
    _sleepTimerActive.value = true
    _sleepTimerEndsAt.value = System.currentTimeMillis() + durationMs
}

fun cancelSleepTimer() {
    sleepTimerJob?.cancel()
    player.volume = 1.0f
    _sleepTimerActive.value = false
}
```

### UI entry point:
- Now Playing screen → three-dot menu → "Sleep timer"
- BottomSheet: preset chips (15m / 30m / 45m / 60m / Custom)
- When active: show countdown label in Now Playing (e.g. "Sleep in 28:42")

---

## Deep-Dive: F-03 — Re-check + Replace Wrong Versions on Sync

### What Raj wants:
- "When hitting Sync, re-check already downloaded tracks and replace wrong ones"
- Toggle to enable/disable this (expensive operation)
- Only correct mismatched ones — don't re-download everything

### How to detect "already wrong" tracks:
A track already in the DB can be re-scored against the same YouTube search results:
```kotlin
suspend fun recheckTrack(track: TrackEntity): RecheckResult {
    if (track.pinnedYoutubeVideoId != null) return RecheckResult.Pinned // skip

    val candidates = youtubeSearch.search("${track.artist} ${track.title}")
    val bestCandidate = candidates.maxByOrNull { score(it, track) } ?: return RecheckResult.NoResults

    if (bestCandidate.score < MINIMUM_THRESHOLD) return RecheckResult.NoGoodMatch

    if (bestCandidate.videoId == track.youtubeVideoId) return RecheckResult.AlreadyCorrect

    // New best match differs from what we have
    return RecheckResult.BetterMatchFound(bestCandidate)
}
```

### Sync flow with toggle:
```
SyncWorker.doWork()
    │
    ├── [always] Fetch new tracks from Spotify → match → download
    │
    └── [if "Re-check existing" toggle is ON]
            │
            ├── For each downloaded track: recheckTrack()
            ├── Collect BetterMatchFound results
            ├── Show summary: "12 tracks have better matches available"
            │        └── "Replace all" / "Review each" / "Skip"
            └── On confirm → re-download only affected tracks
```

### Toggle location: Sync tab → Sync Preferences card (already exists)

---

## Fix Execution Plan (Updated)

| Phase | IDs | What | Complexity |
|---|---|---|---|
| **1** | B-03 | Fix silent download failure + SAF permissions | Medium |
| **2** | B-01, B-02 | YouTube result scoring (duration + keywords + Topic channel) | Medium |
| **3** | F-01 | Manual search BottomSheet + pinned videoId in DB | High (UI) |
| **4** | B-04 | Playlist ORDER BY position fix | Low |
| **5** | B-05, B-07, B-08 | Public playlists, liked songs pagination, diff sync | Medium |
| **6** | B-06 | Reinstall folder rescan | Medium |
| **7** | F-02 | Sleep timer | Medium |
| **8** | F-03 | Re-check + replace wrong versions toggle | Medium |
| **9** | B-09, B-10, B-11, B-12 | Player UI fixes | Low-Medium |
| **10** | B-13, B-14 | Sync prefs fix, library sort fix | Low |
| **11** | F-04, F-05, F-06, F-07 | Feature additions | Varies |

---

## Files to Locate in Android Studio

Search these class/file names with Cmd+Shift+O (or Ctrl+Shift+N):

| Search for | What to check |
|---|---|
| `DownloadWorker` | `doWork()` catch blocks — returning success on failure? |
| `TrackMatcher` or `YoutubeSearch` | Where result[0] is selected — add scoring here |
| `PlaylistTrackEntity` | Does `position: Int` field exist? |
| Playlist DAO query | `ORDER BY position ASC` present? |
| `SpotifyRepository` | Owner filter on playlists? Pagination loop? |
| `PlayerService` | Where to add sleep timer |
| `NowPlayingScreen` | Where to hide mini player + add elapsed time |
| `SyncWorker` | Where sync preferences are checked |

---

## Next Step

**Start with B-03 + B-01/B-02 together — they're both in the download pipeline.**

In Android Studio:
1. Press **Cmd+Shift+O** → type `DownloadWorker` → open it
2. Press **Cmd+Shift+O** → type `TrackMatcher` (or similar) → open it
3. Paste both `doWork()` and the matching/scoring function here

These two files will let us fix the silent failure AND the wrong version problem in one pass.
