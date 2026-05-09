# v0.9.19 — First Listen Tag-Fallback

**Date:** 2026-05-08
**Status:** Design
**Branch:** `feat/first-listen-tag-fallback` (worktree path: `.worktrees/first-listen-tag-fallback`)

## Problem

In v0.9.16 the Stash Mix surface expanded from a single "Stash Discover" flagship to three recipes:

| Recipe | Seed strategy | `discoveryRatio` | Library fallback chain |
|---|---|---|---|
| Daily Discover | `ARTIST_SIMILAR` | 0.4 (40% discovery) | persona → listening events → top artists by track count |
| Deep Cuts | `NONE` | 0.0 (pure library) | n/a — uses library directly |
| **First Listen** | **`TAG_GRAPH`** | **1.0 (100% discovery)** | **none** |

Real user observation on a v0.9.18 release build with a vast library: Daily Discover and Deep Cuts populate normally; **First Listen is empty**.

Trace of `StashMixRefreshWorker.queueDiscoveryForRecipe` for First Listen:

```
topTags = mixGenerator.computeUserTopTags(limit = 10)
  └─ buildUserTagAffinityVector()
        ├─ listening_events from last 180d  ← user has plays, OK
        ├─ for each row: trackTagDao.getByTrack(trackId).filter { it.tag != "__untaggable__" }
        │  └─ returns empty for tracks not yet enriched, or tracks Last.fm has no real tags for
        └─ if all per-play tag maps end up empty → user vector is empty
      → returns []
candidates = seedGenerator.generate(TAG_GRAPH, topTags = [], …)
  └─ generateTagGraph([]).also { for (tag in [].take(10)) { … } } → []
if (candidates.isEmpty()) return  ← early exit, no discovery queued
discoveryRatio = 1.0 means 0% library fill → mix stays empty
```

Concretely: when the user has listening events but their listened-to tracks aren't yet enriched by `TagEnrichmentWorker` (which runs daily, batches of 200, so a multi-thousand-track library takes many days to fully enrich), the listening-affinity vector is empty. The user's library *itself* contains tagged tracks (visible via `TrackTagDao.getTagHistogram()`) — but no path consumes that signal for the TAG_GRAPH seed.

This is a contained design gap, not a regression. v0.9.16 shipped with no fallback for empty `topTags`.

## Goals

- A v0.9.18 user with a vast library and any subset of enriched tracks has a non-empty First Listen mix after the next `StashMixRefreshWorker` run.
- The fallback uses on-device data already produced by the enrichment pipeline — no extra Last.fm round-trips at refresh time, no UI changes, no preference flips.
- The semantic of First Listen ("Tracks you've never heard. Wider net.") is preserved — the fallback's input is still a "what kind of music does this user like" signal, just sourced from library breadth instead of recent listening.
- The fix is contained to one function in `MixGenerator`; tests live in the same file's existing test class.

## Non-goals

- **No tweak to `TagEnrichmentWorker`'s throughput.** Its 200-tracks-per-day cadence is a separate optimization concern. The fallback handles the gap until enrichment catches up; widening enrichment's bandwidth is a future YAGNI revisit.
- **No new prefs.** The fallback fires automatically whenever `buildUserTagAffinityVector()` returns empty; no opt-in toggle.
- **No `MixGenerator.computeUserTopTags()` signature change.** Same suspending fn, same return type, same caller invocations.
- **No change to other recipes.** Daily Discover and Deep Cuts already have working fallback paths; touching them risks regressing what works.
- **No spec for "the user's listened tracks aren't getting enriched fast enough."** That's a different bug class. This spec only fixes the dead-end when the listening-affinity vector is empty.
- **No tag deduplication / canonicalization.** Library histogram tags ship as-is; whatever Last.fm returned (lowercased and stored) is what we use. Future cleanup is out of scope.
- **No `__untaggable__` sentinel cleanup.** The filter at the consumer is sufficient.

## Design

### 1. Code change

**One function modified.** `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt`:

```kotlin
/**
 * v0.9.16: Top-N user tags ordered by tag-affinity weight. Used by
 * [com.stash.core.data.sync.workers.StashMixRefreshWorker] to drive
 * the TAG_GRAPH seed strategy.
 *
 * v0.9.19: when the listening-affinity vector is empty (fresh install,
 * recently played tracks not yet enriched, etc.) falls back to the
 * library-wide tag histogram. The histogram represents "what kind of
 * music this user collects" — the right anchor for First Listen's
 * "wider net" semantics when there's no per-play signal yet.
 *
 * Returns an empty list ONLY when the user has zero tags anywhere in
 * `track_tags` (truly fresh install, enrichment hasn't run a single
 * batch yet) — at which point TAG_GRAPH-driven recipes correctly
 * stay empty until the user's library has any tag data.
 */
suspend fun computeUserTopTags(limit: Int = 10): List<String> {
    val vector = buildUserTagAffinityVector()
    if (vector.isNotEmpty()) {
        return vector.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
    return trackTagDao.getTagHistogram()
        .asSequence()
        .filter { it.tag != "__untaggable__" }
        .take(limit)
        .map { it.tag }
        .toList()
}
```

The fallback's filter mirrors the same `__untaggable__` exclusion `buildUserTagAffinityVector` applies to per-play tags, so neither path can leak the sentinel into seed input.

### 2. Test surface (TDD)

| Test | What it asserts |
|---|---|
| `computeUserTopTags returns affinity-vector tags when vector is non-empty` | When `buildUserTagAffinityVector` would produce a non-empty vector (i.e., listening events exist for tagged tracks), the existing path runs and the histogram is NOT consulted. |
| `computeUserTopTags falls back to histogram when affinity vector is empty` | When the affinity vector is empty (no plays, OR plays only on untagged tracks), `getTagHistogram` is called and its top N tags returned. |
| `computeUserTopTags fallback filters out __untaggable__ sentinel` | When the histogram includes the sentinel row (which it always does once `TagEnrichmentWorker` has hit any unmatched track), the fallback filters it out — the seed loop must never iterate `__untaggable__` as a Last.fm tag. |
| `computeUserTopTags returns empty when histogram is also empty` | Truly-fresh-install case (tag enrichment hasn't run any batch yet). Fallback gracefully returns empty rather than throwing. The `TAG_GRAPH` consumer's existing `if (candidates.isEmpty()) return` guard handles this case correctly. |
| `computeUserTopTags respects the limit on the fallback path` | When the histogram has more than `limit` rows, only the top `limit` are returned. |
| `computeUserTopTags fallback yields `limit` real tags even when `__untaggable__` appears in the histogram top` | Combined-case regression guard. Histogram `[__untaggable__(top), tag1, tag2, …]` with `limit = 10` must yield 10 real tags (the implementation must filter BEFORE take, not AFTER, otherwise the sentinel consumes a slot). The existing implementation chains `.filter { … }.take(limit)`; this test pins that order. |

The first test guards against a regression where the histogram fallback "always runs" — if the listening-affinity signal exists, it should be the source of truth.

Test file: existing `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorTest.kt` if it exists, otherwise `MixGeneratorComputeUserTopTagsTest.kt` as a sibling.

### 3. No schema change

The fallback consumes existing tables (`track_tags`) via an existing DAO method (`TrackTagDao.getTagHistogram()`). No migration. Schema stays at v22.

### 4. Edge cases

#### 4.1 Empty histogram (truly-fresh install)

If both the affinity vector AND the histogram are empty (the user just installed v0.9.19 and `TagEnrichmentWorker` hasn't run a single batch yet), `computeUserTopTags` returns `emptyList()`. `MixSeedGenerator.generateTagGraph(emptyList())` returns `emptyList()`, the worker's `if (candidates.isEmpty()) return` early-exits, and First Listen stays empty. After enrichment runs once, the next refresh produces tags via the fallback. No additional code change needed.

#### 4.2 Histogram polluted with `__untaggable__` only

If every track in the user's library returns `__untaggable__` from Last.fm (vanishingly unlikely — typical Last.fm coverage is high for mainstream tracks), the filter strips them all and the fallback returns `emptyList()`. Same downstream behavior as 4.1.

#### 4.3 Listening-affinity vector becomes non-empty after enrichment catches up

The fallback's `if (vector.isNotEmpty()) return …` short-circuit ensures the affinity-vector path takes over the moment the user has any plays on enriched tracks. No persistent state to flip; the choice is computed per-refresh.

#### 4.4 Daily Discover unaffected

Daily Discover uses `ARTIST_SIMILAR`, which doesn't call `computeUserTopTags`. Untouched.

#### 4.5 Deep Cuts unaffected

Deep Cuts uses `NONE` strategy — short-circuits before the seed-generator dispatch. Untouched.

### 5. Versioning + ship

- `versionCode`: 56 → 57
- `versionName`: 0.9.18 → 0.9.19
- Schema: 22 (unchanged)
- New branch: `feat/first-listen-tag-fallback`, worktree at `.worktrees/first-listen-tag-fallback`. Per project memory: copy `local.properties` AND `keystore.properties` AND `stash-release.jks` into the worktree (`local.properties` for Last.fm credentials at build time; signing files for release-APK install over existing v0.9.18).

## v0.9.19 follow-up: discovery survivor cap

### Why this is in the same spec

Phase 1 debugging on the v0.9.19 install surfaced a second, latent bug: `materializeMix` re-links **every** DONE row in `discovery_queue` for a recipe with no upper bound. Over many refreshes, the playlist grows past `targetLength`. Concrete observed effect: Daily Discover went from 50 tracks pre-v0.9.19 to 100 tracks immediately after the v0.9.19 install's app-startup all-recipes refresh, because ~50 DONE survivors had accumulated between v0.9.18 install and v0.9.19 install. The first-section histogram fallback above will produce the same drift on First Listen once `StashDiscoveryWorker` chews through the 300 PENDING candidates the fallback queues.

This isn't a regression introduced by v0.9.19 — it's a pre-existing structural cap miss. But the same install that exposed it (the histogram fallback driving more candidates into the discovery queue) makes shipping the fallback alone awkward: First Listen would steadily creep past 50 tracks the way Daily Discover did. Keeping both fixes in the v0.9.19 ship avoids that intermediate broken state.

### Goals

- Discovery survivor re-link in `materializeMix` is bounded at `targetLength * discoveryRatio` (rounded). Enforces the recipe's stated discovery slot count.
- Newest-DONE survivors win when the cap forces us to cut. Older survivors rotate out naturally on subsequent refreshes — matches each recipe's "freshness window" framing.
- Library shortfall-fill (`MixGenerator.kt` line 192) is **untouched** — the cap applies only to discovery survivors, not to library tracks. Daily Discover keeps its current "fill library to 50 if pool is large enough" behavior.

### Non-goals

- **No change to library shortfall-fill.** Trimming library tracks to `(1 - discoveryRatio) * targetLength` when discovery survivors are present would make Daily Discover exactly 30+20=50, but that's a behavior change in `MixGenerator` that v0.9.16 has shipped with for months. Out of scope here. The cap fixes the unbounded-growth bug without touching working code.
- **No DONE-row cleanup in the discovery queue itself.** The DAO query just LIMITs what's returned; old DONE rows past the cap stay in `discovery_queue` (still useful for diagnostics, future cap-bumps, etc.). A separate sweep that deletes long-stale DONE rows is a future YAGNI revisit.
- **No new schema column or index.** `discovery_queue.completed_at` already exists and is set when the worker transitions to DONE; that's the ordering signal.

### Design

**File 1: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt`**

Modify `getDoneTrackIdsForRecipe` to take a `limit` parameter and add `ORDER BY completed_at DESC LIMIT :limit`:

```kotlin
@Query("""
    SELECT track_id FROM discovery_queue
    WHERE recipe_id = :recipeId
      AND status = 'DONE'
      AND track_id IS NOT NULL
    ORDER BY completed_at DESC
    LIMIT :limit
""")
suspend fun getDoneTrackIdsForRecipe(recipeId: Long, limit: Int): List<Long>
```

`completed_at` is non-null for any row where `status = 'DONE'` (`DiscoveryQueueEntity.completedAt: Long?` is set inside the worker on the DONE transition; the schema doesn't enforce non-null but the data invariant holds in practice).

**File 2: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`**

In `materializeMix`, compute the cap at the call site and pass it. The existing in-Kotlin `librarySet` filter and blocklist loop operate on the already-capped list — no change to that logic:

```kotlin
val discoveryCap = (recipe.targetLength * recipe.discoveryRatio)
    .roundToInt()
    .coerceAtLeast(0)
val candidateIds = discoveryQueueDao
    .getDoneTrackIdsForRecipe(recipe.id, limit = discoveryCap)
    .filter { it !in librarySet }
val discoveryTrackIds = buildList {
    for (trackId in candidateIds) {
        if (!blocklistGuard.isBlockedByTrackId(trackId)) add(trackId)
    }
}
// ... existing forEachIndexed insertion and totalCount = tracks.size + discoveryTrackIds.size
```

### Behavior matrix

| Recipe | targetLength | discoveryRatio | New cap | Total bound (library + cap) |
|---|---|---|---|---|
| Daily Discover | 50 | 0.4 | 20 | up to 70 (50 library w/ shortfall fill + ≤20 discovery) |
| Deep Cuts | 50 | 0.0 | 0 | 50 (library only; LIMIT 0 returns empty) |
| First Listen | 50 | 1.0 | 50 | up to 50 (0 library + ≤50 discovery — pure discovery) |

Daily Discover settling at 70 instead of 50 is intentional and matches the spec's intent above (no library trim). The user's observation of "Daily Discover at 100" becomes "≤70" after the cap — acceptable; the unbounded growth is the real bug.

### Test surface (TDD)

| Test | What it asserts |
|---|---|
| `getDoneTrackIdsForRecipe respects limit` | Seed N+M DONE rows for one recipe, request `limit = N`, verify N rows returned. |
| `getDoneTrackIdsForRecipe orders by completed_at DESC` | Seed two DONE rows with `completedAt = (older, newer)`, request `limit = 2`, verify newer-first. |
| `getDoneTrackIdsForRecipe filters status=DONE` | Seed PENDING + DONE rows for the same recipe, request `limit = 99`, verify only DONE returned. |
| `getDoneTrackIdsForRecipe filters out null track_id` | Seed a DONE row where the worker hasn't yet linked a `track_id` (transient state — possible if a row updates status before linking the final track id), verify it's excluded. |
| `getDoneTrackIdsForRecipe with limit=0 returns empty` | Edge case: Deep Cuts hits this on every refresh. Result must be empty list, no exception. |

The first four tests live in an existing `DiscoveryQueueDao` test class if one exists, otherwise a new `DiscoveryQueueDaoCapTest.kt` sibling. The `materializeMix` integration is verified manually on-device after install (no Compose/UI test added — the cap is a count check that's already exercised by the on-device smoke test).

### Edge cases

#### `discoveryCap = 0`

Daily Discover with the user's `discoveryRatio = 0.4` rounds to 20 — non-zero. Deep Cuts with `discoveryRatio = 0.0` rounds to 0 — `LIMIT 0` returns empty list. Sqlite tolerates `LIMIT 0` natively.

#### Blocklist removes some of the top `cap` rows

Cap = 20, but 5 of the top 20 are blocked. Result: 15 discovery survivors re-linked. The cap is a max, not a target — we don't re-query to backfill. Matches the existing semantic (the blocklist filter has always been post-fetch; the new code just runs it on a smaller fetch).

#### `completed_at` is null on a row marked DONE

Possible if a future bug or an incomplete migration leaves a DONE row without a timestamp. SQLite's `ORDER BY` puts NULLs last by default — those rows sink to the bottom and get cut first under the LIMIT. Acceptable degradation; flagged here so a future audit knows where to look.

### Versioning + ship

Same v0.9.19 ship — no separate version bump for this fix. The version bump commit (currently `ba21749`) gets rebased to the tip after the cap-fix commit lands, so the tagged commit at release time has both fixes plus the version bump in its merge base.

User explicitly defers the ship decision until manual on-device verification — the worktree-build-install steps land in this iteration, but `git push` and `git tag` wait for user sign-off after testing.

## Open questions

None blocking implementation. Possible follow-ups deferred to later releases:

- **Tag-enrichment throughput.** 200 tracks/day on a vast library is slow. Doubling the batch (or adding a "catch-up" mode that runs hourly until the untagged backlog is < N) would reduce the window during which the fallback is the only source of truth. Worth doing if users with >5000 tracks repeatedly ask why their listening history doesn't bias First Listen.
- **Hybrid weighting.** When the affinity vector exists but is *thin* (few plays on enriched tracks), blending with the histogram could produce better signal than either alone. Worth exploring if v0.9.19's binary "vector if present, histogram otherwise" feels sharp at the boundary.
- **DONE-row cleanup in `discovery_queue`.** With the cap, old DONE rows past the survivor window stay in the queue indefinitely — useful for diagnostics, but accumulates over time. A periodic sweep to prune rows older than (say) 90 days could keep the table size in check.
- **Library shortfall-fill rebalance.** Trimming library tracks to `(1 - discoveryRatio) * targetLength` when discovery survivors are present would tighten the total to exactly `targetLength`. Worth doing if `targetLength + discoveryCap` (e.g., 70 for Daily Discover) feels too long in practice.
- **Listening events trigger immediate tag-enrichment.** Currently `TagEnrichmentWorker` walks `findUntaggedDownloadedTrackIds` from the top — there's no "prioritize tracks the user is actively playing" signal. A small tweak would re-order the queue to enrich played-but-untagged tracks first. Different bug class; out of scope here.
