# Upstream sync policy (fork `dev-rajh/Stash` ← upstream `rawnaldclark/Stash`)

This document is the contract for bringing the fork up to a new upstream
release. It is written for whoever (or whatever agent) performs the merge —
follow it exactly, and record deviations in the merge commit message.

## Branch layout

- `upstream` remote → https://github.com/rawnaldclark/Stash (the original app)
- `origin` remote  → https://github.com/dev-rajh/Stash (this fork)
- `master`         → mirror of upstream/master. **Never commit fork work here.**
- `dev+NNN`        → the integration branch (`dev+121` = upstream v0.9.121 + fork features).
  Each new sync continues the same branch (merge upstream into it); bump the
  name only when upstream's versionName majorly moves and you want a clean label.

## The one hard rule

**Database objects are upstream's, always.** Entities, schemas
(`core/data/schemas/**`), `StashDatabase.kt`, `Converters.kt`,
`DatabaseModule.kt`, and every `@Entity`/`@ColumnInfo`/migration take
upstream's side in any conflict, byte for byte. The fork's DB-layer additions
are limited to *query methods* on DAOs (`@Query`/`@Update` functions that
touch existing columns) — those survive a merge; anything else must be
re-derived against upstream's schema. After resolving, verify:

```bash
git diff <upstream-sha> -- core/data/schemas/ \
  core/data/src/main/kotlin/com/stash/core/data/db/entity/ \
  core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
  core/data/src/main/kotlin/com/stash/core/data/db/converter/   # must be empty
```

## Fork features to port onto upstream's current architecture

The fork's own features (what upstream doesn't have). On conflict, take
upstream's file as the base, then re-apply only these:

1. **Library sort orders** — `SortOrder.OLDEST, ALPHABETICAL_DESC, ARTIST,
   LEAST_PLAYED, SHORTEST, RECENTLY_PLAYED` in `LibraryUiState`; their
   branches in `LibraryViewModel`'s four sort `when` blocks and
   `sortLikedTracks`; their entries in `LibrarySortFilterSheet`'s `sorts` list.
   `DURATION` keeps its original name (stored-preference compatibility) — do
   not rename it to LONGEST.
2. **Library Play button** — `playLibrary()` in `LibraryViewModel`; a filled
   Play `FilledIconButton` in `LibraryScreen`'s header row (hidden on Liked
   and Device tabs) wired via the `onPlayLibrary` param.
3. **Song page (Now Playing)** — file size in the quality line
   (`trackQualityText(track, effectiveSize)` + `humanReadableSize`; DB value
   with disk/SAF fallback via `NowPlayingViewModel.observeCurrentFileSize`);
   file path + "Appears in" chips (`TrackInfoFooter`, backed by
   `observeContainingPlaylists` → `PlaylistDao.observePlaylistsContainingTrack`
   → `MusicRepository.getPlaylistsContainingTrack`), rendered at the very END
   of the Now Playing stack, under the Up next line — never between the
   quality line and the progress bar (owner: the main stack keeps the
   upstream rhythm; the info is a quiet footer). Navigation via
   `onNavigateToPlaylist` threaded through `NowPlayingScreen` →
   `PlayerSheetOverlay` → `StashScaffold` → `PlaylistDetailRoute`.
4. **Seek-bar remaining toggle** — tap the elapsed label to flip to
   `-m:ss` (`showRemaining` in `GlowingProgressBar`).
5. **Library Health relink scan** — `RelinkSection` in `LibraryHealthScreen`;
   `runRelinkScan`, `RelinkStatus`, `fileNameOf`, `AUDIO_EXTENSIONS` in
   `LibraryHealthViewModel`; `LocalFileOps.findReplacementSibling` (+ its SAF
   helpers); `TrackDao.relinkReplacedFile` + `getDownloadedFileRefs`.

## Known supersessions (do NOT re-port these)

Upstream built its own versions; upstream wins, the fork's variant is dropped:

- Sleep timer: upstream's `SleepTimerController` + `SleepTimerButton`/`Readout`.
  The fork's `core/media/sleep/SleepTimerManager.kt` is deleted.
- Now Playing three-dot menu: upstream's configurable `NowPlayingActionsUi`
  (`NowPlayingAction` pins + editor). The fork's `ui/NowPlayingOptionsSheet.kt`
  is deleted.
- Bulk FLAC upgrade: upstream's selection-based `upgradeSelectedToFlac` via
  `FlacUpgradeEnqueuer`. The fork's `FlacUpgradeUiState`/`BulkFlacUpgradeStrip`
  is dropped.
- `LocalFileOps.exists()` — upstream has an identical one; never add a second.
- Test-file changes in the fork that reference removed fork APIs (e.g.
  `sleepTimerManager = mockk(...)` in NowPlaying tests) — restore the upstream
  version of the test file.
- Library "Recently downloaded" rail (`RecentlyDownloadedRail` +
  its `TracksTab` header hook): removed at owner request (2026-10-29).
  If upstream's merge re-adds it, remove it again — this is a standing fork
  deviation.
- `gradle.properties` must never carry `org.gradle.java.home` (CI runners
  have no Homebrew); the local JDK path lives in `~/.gradle/gradle.properties`.

## Resolution shortcuts by file

| File | Policy |
|---|---|
| `core/data/**` DB layer | upstream, except the DAO query methods above |
| `StashNavHost.kt`, `StashScaffold.kt` | upstream (player-sheet architecture) |
| `MiniPlayer.kt`, `TrackListItem.kt` | upstream (supersets of fork changes) |
| Library/NowPlaying feature files | upstream base + re-port list above |
| `.gitignore` | upstream + keep `.claude/` and `CLAUDE.md` entries |
| `gradle.properties`, wrapper, `gradlew*` | upstream, unless the fork's AGP/JDK bump is still needed to build locally |
| `data/download/build.gradle.kts` `qbdlxProp` | either form works; upstream's if untouched |

## Procedure

1. `git fetch upstream --prune && git fetch origin --prune`
2. Work on a throwaway branch off `dev+NNN` (external resets have wiped
   in-progress merges before — never merge directly on the integration branch).
3. `git merge upstream/master --no-commit --no-ff`, resolve per this document.
4. Verify the DB-layer diff is empty (command above).
5. `./gradlew compileDebugKotlin` — fix exhaustiveness/imports from the new
   enum values and API moves.
6. `./gradlew testDebugUnitTest --continue` — every module must pass except
   the known-environmental `CastMediaServerTest` LAN test (verify against a
   clean upstream worktree if it fails).
7. Commit the merge, push to `origin`, delete the throwaway branch.
8. Tag `v<upstream versionName>+fork` if a release APK is wanted — the
   `release.yml` workflow builds and publishes it (keys live in the `release`
   GitHub Environment).
