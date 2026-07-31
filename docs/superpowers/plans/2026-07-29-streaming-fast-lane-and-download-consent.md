# Streaming Fast Lane + Download Consent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop algorithmic mixes from downloading without consent, and stop every YouTube-fallback playback from paying the slow yt-dlp lane.

**Architecture:** Part A is pure predicate work — wire one guard that already exists and unify three SQL predicates around a single definition of "download-eligible". Part B re-opens the existing InnerTube/yt-dlp race for playback, fixing the ANDROID_VR client's transport config and adding a tail-range probe so a PO-token-gated URL can never reach ExoPlayer.

**Tech Stack:** Kotlin, Room, Hilt, WorkManager, OkHttp, Robolectric + in-memory Room for DAO tests, MockWebServer for HTTP tests.

**Spec:** `docs/superpowers/specs/2026-07-29-streaming-fast-lane-and-download-consent-design.md`

**Branch:** `fix/stream-fast-lane-and-mix-downloads` (already created; spec committed at 98484f18)

**Do Part A first.** It is pure logic with no network dependency, it fixes the user-facing data-loss-adjacent bug (#368), and it cannot be destabilised by Part B's on-device findings.

---

## File Structure

**Part A — download consent**

| File | Responsibility | Change |
|---|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt` | Sync diff + enqueue decision | Modify `:43-44` (`defaultSyncEnabled`), `:565` (enqueue gate) |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt` | Queue predicates | Modify `:553`, `:601`, `:412-424` |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DefaultSyncEnabledTest.kt` | Existing test, asserts current auto-enable | Update expectations |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DiffWorkerMixNoDownloadTest.kt` | Enqueue-gate regression | Create |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoMixExclusionTest.kt` | All three predicates vs mix parents | Create |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoMixVisibilityTest.kt` | Pins the load-bearing visibility assumption | Create |

**Part B — streaming fast lane**

| File | Responsibility | Change |
|---|---|---|
| `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt` | Client variants + player lookup | Modify `ANDROID_VR` config (`:54-67`), `AUDIO_VARIANT_ORDER` (`:151-160`) |
| `data/download/src/main/kotlin/com/stash/data/download/preview/AudioUrlTailProbe.kt` | Proves a URL serves its whole body | Create |
| `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` | Extraction + race | Modify `selectBestAudioUrl` (`:143`), `extractViaInnerTube` (`:428`) |
| `core/media/src/main/kotlin/com/stash/core/media/streaming/YouTubeStreamResolver.kt` | Playback resolve | Modify `:100-104` |
| `data/download/src/test/kotlin/com/stash/data/download/preview/AudioUrlTailProbeTest.kt` | Probe behaviour over MockWebServer | Create |

---

## Definition of "download-eligible"

Both parts of Part A converge on one rule. Write it once, apply it three times:

> A track is download-eligible when it is a member (`removed_at IS NULL`) of a
> playlist with `is_active = 1 AND sync_enabled = 1` whose `type` is neither
> `STASH_MIX` nor `DAILY_MIX`.

Three SQL sites enforce it today, inconsistently: `getUnqueuedTrackIds` and
`deleteOrphanedQueueEntries` exclude only `STASH_MIX`;
`cancelDownloadsWithNoEnabledPlaylist` excludes no types at all.

---

# Part A — Download consent

### Task A1: Wire the enqueue guard that already exists

`shouldEnqueueForDownload` (`DiffWorker.kt:53-54`) excludes `DAILY_MIX`, is already unit-tested, and is called by nothing but its test. The real enqueue site tests raw `!streamingMode`.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt:565`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DiffWorkerMixNoDownloadTest.kt` (create)

- [ ] **Step 1: Write the failing test — at the WORKER level, not the helper**

**This distinction is the whole point of the task.** A test that calls
`shouldEnqueueForDownload` directly passes today and would keep passing if the
wiring were reverted — the helper was always correct; nothing called it. The
test must fail when the enqueue site tests raw `!streamingMode`, so it has to
run the worker and observe what reaches the DAO.

`DiffWorkerTest.kt` already has the exact harness: Robolectric + real in-memory
Room, `downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)` (`:60`),
`streamingPreference` stubbed to Offline by default (`:74`), and a `buildWorker()`
helper (`:806-830`). Model the new file on it and reuse that construction
verbatim.

```kotlin
package com.stash.core.data.sync.workers

// Fixture setup (db, mockk DAOs, buildWorker) copied from DiffWorkerTest.kt —
// see :52-90 and :806-830. Only the scenario below differs.

/**
 * #368: an auto-discovered algorithmic mix must never enqueue downloads, even in
 * Offline mode. The guard `shouldEnqueueForDownload` excluded DAILY_MIX from the
 * day it was written and was unit-tested — but DiffWorker's enqueue site tested
 * raw `!streamingMode`, so every track of every rotating Spotify/YT mix was
 * queued. This asserts the WIRING, which a helper-level test cannot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiffWorkerMixNoDownloadTest {

    @Test fun `daily mix enqueues nothing in offline mode`() = runBlocking {
        // Offline mode: the mode in which a mix used to pull its whole contents.
        coEvery { streamingPreference.current() } returns false

        // A DAILY_MIX that is already sync_enabled — the state defaultSyncEnabled
        // used to produce automatically, and the state existing installs carry.
        seedPlaylist(type = PlaylistType.DAILY_MIX, syncEnabled = true)
        seedRemoteSnapshotWithNewTracks(count = 3)

        buildWorker().doWork()

        coVerify(exactly = 0) { downloadQueueDao.insertAll(any()) }
    }

    @Test fun `custom playlist still enqueues in offline mode`() = runBlocking {
        coEvery { streamingPreference.current() } returns false
        seedPlaylist(type = PlaylistType.CUSTOM, syncEnabled = true)
        seedRemoteSnapshotWithNewTracks(count = 3)

        buildWorker().doWork()

        coVerify(atLeast = 1) { downloadQueueDao.insertAll(any()) }
    }
}
```

The second test is not padding: it is what stops the fix from being "disable all
downloads", which would pass the first test and break the product.

Separately, `shouldEnqueueForDownload(STASH_MIX, false)` currently returns
**true** and `ShouldEnqueueForDownloadTest:25` asserts that. Step 3 excludes both
mix types, so update that existing assertion in the same commit.

- [ ] **Step 2: Run it and confirm the mix case fails**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.DiffWorkerMixNoDownloadTest"
```

Expected: FAIL on `daily mix enqueues nothing in offline mode` — `insertAll` was
called once. The `custom playlist` test must already PASS. If the mix test passes
before the fix, the fixture isn't producing new tracks — fix the fixture before
touching production code, or the task proves nothing.

- [ ] **Step 3: Exclude both mix types in the helper, and wire it at the enqueue site**

In `DiffWorker.kt:53-54`, replace the body:

```kotlin
internal fun shouldEnqueueForDownload(type: PlaylistType, streamingMode: Boolean): Boolean =
    !streamingMode && type != PlaylistType.DAILY_MIX && type != PlaylistType.STASH_MIX
```

In `DiffWorker.kt:565`, replace `if (!streamingMode) {` with:

```kotlin
        // Mixes are surface-only: they stream on tap and must never pull bytes.
        // This guard existed (and was unit-tested) since it was written but was
        // never wired here — the site tested raw `!streamingMode`, so every
        // track of every rotating mix was queued in Offline mode (#368).
        if (shouldEnqueueForDownload(localPlaylist.type, streamingMode)) {
```

Update `ShouldEnqueueForDownloadTest.kt:25` to expect `isFalse()` for `STASH_MIX`, with a comment naming #368.

- [ ] **Step 4: Run both test classes**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.DiffWorkerMixNoDownloadTest" --tests "com.stash.core.data.sync.workers.ShouldEnqueueForDownloadTest"
```

Expected: PASS, both classes.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt core/data/src/test/kotlin/com/stash/core/data/sync/workers/DiffWorkerMixNoDownloadTest.kt core/data/src/test/kotlin/com/stash/core/data/sync/workers/ShouldEnqueueForDownloadTest.kt
git commit -m "fix(sync): wire the mix download guard DiffWorker never called (#368)"
```

---

### Task A2: Stop auto-enabling discovered mixes

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt:36-44`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DefaultSyncEnabledTest.kt` (update)

- [ ] **Step 1: Update the existing test to the new contract**

Replace the DAILY_MIX/online expectation with:

```kotlin
    /**
     * Auto-enabling a discovered mix is what made it download-eligible and what
     * made the sweep spare it. Surfacing does NOT depend on it: getAllVisible's
     * streamable escape hatch already shows a sync_enabled = 0 mix in Online
     * mode, so opt-in costs nothing (see PlaylistDaoMixVisibilityTest).
     */
    @Test fun `discovered playlists are opt-in in both modes`() {
        assertThat(defaultSyncEnabled(PlaylistType.DAILY_MIX, online = true)).isFalse()
        assertThat(defaultSyncEnabled(PlaylistType.DAILY_MIX, online = false)).isFalse()
        assertThat(defaultSyncEnabled(PlaylistType.CUSTOM, online = true)).isFalse()
    }
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.DefaultSyncEnabledTest"
```

Expected: FAIL — the DAILY_MIX/online case currently returns true.

- [ ] **Step 3: Make it always opt-in**

In `DiffWorker.kt:35-44`, replace the KDoc and body:

```kotlin
/**
 * A newly-discovered playlist's initial [PlaylistEntity.syncEnabled]: always
 * opt-in. The first Sync Now is a discovery pass that downloads nothing unasked.
 *
 * DAILY_MIX used to auto-enable in Online mode "so mixes surface immediately
 * with no download". That was redundant and load-bearing only for harm:
 * [com.stash.core.data.db.dao.PlaylistDao.getAllVisible] already surfaces a
 * `sync_enabled = 0` playlist whose tracks are streamable when
 * `includeStreamable = true`, so mixes still appear on Home in Online mode.
 * The flag's only other effects were making the mix download-eligible and
 * making the orphan sweep spare its tracks (#368).
 *
 * Parameters are retained to document what was considered and to keep the
 * decision testable.
 */
@Suppress("UNUSED_PARAMETER")
internal fun defaultSyncEnabled(type: PlaylistType, online: Boolean): Boolean = false
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.DefaultSyncEnabledTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt core/data/src/test/kotlin/com/stash/core/data/sync/workers/DefaultSyncEnabledTest.kt
git commit -m "fix(sync): discovered mixes are opt-in, not auto-enabled (#368)"
```

---

### Task A3: Pin the visibility assumption A2 rests on

If this test is ever red, A2 silently hides mixes instead of merely un-downloading them. Write it even though it should pass today — it is the guard rail on the design's central claim.

**Files:**
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoMixVisibilityTest.kt` (create)

- [ ] **Step 1: Write the test**

Use the setup pattern from `DownloadQueueDaoPartitionTest.kt:25-52`. Seed one `DAILY_MIX` playlist with `syncEnabled = false`, one track with `isDownloaded = false, isStreamable = true`, and a cross-ref with `removedAt = null`.

```kotlin
    @Test fun `sync-disabled daily mix with streamable tracks is visible online`() = runTest {
        assertThat(playlistDao.getAllVisible(includeStreamable = true).first().map { it.id })
            .contains(mixId)
    }

    @Test fun `sync-disabled daily mix with nothing downloaded is hidden offline`() = runTest {
        assertThat(playlistDao.getAllVisible(includeStreamable = false).first().map { it.id })
            .doesNotContain(mixId)
    }
```

- [ ] **Step 2: Run it — expect PASS immediately (it documents existing behaviour)**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.PlaylistDaoMixVisibilityTest"
```

Expected: PASS. If it FAILS, **stop and reassess A2** — the design's premise is wrong.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/test/kotlin/com/stash/core/data/db/dao/PlaylistDaoMixVisibilityTest.kt
git commit -m "test(db): pin that a sync-disabled mix still surfaces online"
```

---

### Task A4: Unify the three queue predicates

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt:553`, `:601`, `:412-424`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoMixExclusionTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Seed three tracks, each with a PENDING queue row: one whose only parent is a `DAILY_MIX` (`syncEnabled = true`, mimicking an already-auto-enabled mix), one whose only parent is a `STASH_MIX` (`syncEnabled = true`), one in a `CUSTOM` playlist (`syncEnabled = true`). Then:

```kotlin
    @Test fun `daily-mix-only track is not requeue-eligible`() = runTest {
        assertThat(dao.getUnqueuedTrackIds(listOf("SPOTIFY"))).doesNotContain(dailyMixTrackId)
    }

    @Test fun `orphan sweep evicts a daily-mix-only queue row`() = runTest {
        dao.deleteOrphanedQueueEntries()
        assertThat(dao.getByTrackId(dailyMixTrackId)).isNull()
        assertThat(dao.getByTrackId(customTrackId)).isNotNull()
    }

    @Test fun `enabled-playlist sweep evicts a mix-only queue row`() = runTest {
        dao.cancelDownloadsWithNoEnabledPlaylist()
        assertThat(dao.getByTrackId(dailyMixTrackId)).isNull()
        assertThat(dao.getByTrackId(stashMixTrackId)).isNull()
        assertThat(dao.getByTrackId(customTrackId)).isNotNull()
    }
```

For `getUnqueuedTrackIds` the seeded track must have **no** queue row (that query excludes tracks with PENDING/IN_PROGRESS/FAILED rows) — seed a fourth, queue-row-free `DAILY_MIX`-only track for that assertion.

- [ ] **Step 2: Run it and confirm failures**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.DownloadQueueDaoMixExclusionTest"
```

Expected: FAIL on all three — `DAILY_MIX` is excluded nowhere, and `cancelDownloadsWithNoEnabledPlaylist` has no type filter at all.

- [ ] **Step 3: Apply the same exclusion to all three queries**

`DownloadQueueDao.kt:553` and `:601` — replace `AND p.type != 'STASH_MIX'` with:

```sql
                -- Mixes (generated STASH_MIX and algorithmic DAILY_MIX) are
                -- stream-only: a mix-only membership never makes a track
                -- download-eligible and never spares its queue row. DAILY_MIX
                -- was missing here, so auto-enabled Spotify/YT mixes queued
                -- their whole contents in Offline mode (#368).
                AND p.type NOT IN ('STASH_MIX', 'DAILY_MIX')
```

`:412-424` (`cancelDownloadsWithNoEnabledPlaylist`) — add the same clause to its spared subquery, after `AND pt.removed_at IS NULL`.

- [ ] **Step 4: Run the new test plus the whole DAO suite for regressions**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.*"
```

Expected: PASS. Existing sweep/partition tests must stay green — if one flips, read it: it may be asserting the old mix-spares-row behaviour and need updating with a comment.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt core/data/src/test/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoMixExclusionTest.kt
git commit -m "fix(db): mix membership never makes a track download-eligible (#368)"
```

---

### Task A5: Verify Part A whole-module, then on device

- [ ] **Step 1: Full module suite**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: PASS (baseline is 578 tests green per `infra_ci_now_green_baseline`). A red test here is this change.

- [ ] **Step 2: Install and observe the sweep**

```bash
./gradlew :app:installDebug
```

Then trigger a sync and confirm from logcat that mix tracks are neither queued nor requeued:

```powershell
adb logcat -c
# trigger Sync Now in the app
adb logcat -d | Select-String "QueueTrace|Re-queuing|sync disabled, skipping"
```

Expected: no `Re-queuing` line naming mix tracks. `QueueTrace` counts should reflect real playlists only.

- [ ] **Step 3: Commit any fixes, then stop for review before Part B**

---

# Part B — Streaming fast lane

### Task B1: Measure the baseline first

Do not skip this. The "~3.6 s" figure is a 2026-06-26 measurement; the reported symptom is "super slow", which suggests Chaquopy cold-start or the per-call `--remote-components ejs:github` fetch dominates. The number decides whether B is sufficient on its own.

**Files:** none (measurement only)

- [ ] **Step 1: Capture timings on device**

```powershell
adb logcat -c
# in the app: play a track that has no lossless match (forces the YT fallback)
Start-Sleep -Seconds 30
adb logcat -d | Select-String "yt-dlp: invoking|yt-dlp: exit|StreamSourceRegistry: (youtube served|chain for)"
```

- [ ] **Step 2: Record the delta**

Subtract the `yt-dlp: invoking` timestamp from `yt-dlp: exit`. Repeat on three different tracks, including one cold (right after force-stop) to separate cold-start cost. Write the numbers into the plan file under this task before proceeding.

---

### Task B2: Fix the ANDROID_VR transport config and restore it to the order

**Context that changes the decision:** ANDROID_VR was in `AUDIO_VARIANT_ORDER` originally (195cd603) and removed in 5475aa05 with the comment "ANDROID_VR / WEB_REMIX no longer serve the unciphered shape". But that same commit is what *fixed* IOS by moving it to the `www.youtube.com` host with a numeric client-name header and no API key — and ANDROID_VR was never given that treatment. It still defaults to `apiBase = music.youtube.com` and `clientNameId = ""` (`InnerTubeClient.kt:54-67`). yt-dlp's `player_client=android_vr` — which posts to `www.youtube.com` as client 28 — works in this app today and is the source of every URL we currently stream. So ANDROID_VR was very likely judged on a misconfiguration, not on merit.

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt:54-67`, `:151-160`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeVariantTest.kt` (update)

- [ ] **Step 1: Update the variant-order test**

```kotlin
    /**
     * ANDROID_VR first: it is the client yt-dlp pins (`player_client=android_vr`)
     * to get a direct itag-251 URL with no PO token and no signature solve, and
     * it must be tried on the www host as client 28 — the same transport fix
     * that made IOS work in 5475aa05. IOS stays as the second attempt.
     */
    @Test fun `audio variant order tries ANDROID_VR before IOS`() {
        assertThat(InnerTubeClient.AUDIO_VARIANT_ORDER)
            .containsExactly(InnerTubeVariant.ANDROID_VR, InnerTubeVariant.IOS)
            .inOrder()
    }

    @Test fun `ANDROID_VR posts to the www host as client 28 without an api key`() {
        assertThat(InnerTubeVariant.ANDROID_VR.apiBase).isEqualTo("https://www.youtube.com/youtubei/v1")
        assertThat(InnerTubeVariant.ANDROID_VR.clientNameId).isEqualTo("28")
        assertThat(InnerTubeVariant.ANDROID_VR.sendsApiKey).isFalse()
    }
```

- [ ] **Step 2: Run and confirm both fail**

```bash
./gradlew :data:ytmusic:testDebugUnitTest --tests "com.stash.data.ytmusic.InnerTubeVariantTest"
```

Expected: FAIL — order is `[IOS]`, and ANDROID_VR carries the defaults.

- [ ] **Step 3: Fix the config and the order**

In the `ANDROID_VR` declaration (`:54-67`) add, alongside the existing fields:

```kotlin
        apiBase = "https://www.youtube.com/youtubei/v1",
        clientNameId = "28",
        sendsApiKey = false,
```

Replace `AUDIO_VARIANT_ORDER` (`:151-160`) — and its KDoc, which currently states the stale conclusion:

```kotlin
        /**
         * Ordered attempt list for audio URL extraction.
         *
         * ANDROID_VR first: it is the client yt-dlp pins to get a direct
         * itag-251 URL with no PO token, no m3u8 manifest and no QuickJS
         * signature solve — the URLs this app streams today are android_vr
         * URLs, minted the slow way through a Python process. It was dropped
         * from this list in 5475aa05 as "no longer unciphered", but it had
         * never been moved to the www host with a numeric client-name header,
         * which is exactly the transport fix that same commit applied to IOS.
         *
         * IOS second: proven fast lane, kept as the backstop.
         *
         * `internal` so variant tests can assert the order.
         */
        internal val AUDIO_VARIANT_ORDER = listOf(
            InnerTubeVariant.ANDROID_VR,
            InnerTubeVariant.IOS,
        )
```

- [ ] **Step 4: Run the module suite**

```bash
./gradlew :data:ytmusic:testDebugUnitTest
```

Expected: PASS. (Per `infra_preexisting_matcher_test_failures` this module once had 22 known-red tests; the baseline has since flipped green — a red test here is this change.)

- [ ] **Step 5: Prove it on device before building anything on top**

```bash
./gradlew :app:installDebug
```

```powershell
adb logcat -c
# in the app: search for a track and tap it to trigger a preview extract
Start-Sleep -Seconds 15
adb logcat -d | Select-String "playerForAudio|InnerTube: SUCCESS|InnerTube: no audio formats"
```

Expected: `playerForAudio videoId=… won with variant=ANDROID_VR`.

**If ANDROID_VR loses here, stop.** The rest of Part B has no value without it: revert this task, record the finding in the spec, and leave playback on yt-dlp.

- [ ] **Step 6: Commit**

```bash
git add data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/InnerTubeClient.kt data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/InnerTubeVariantTest.kt
git commit -m "fix(yt): ANDROID_VR on the www host as client 28, restored to the audio order"
```

---

### Task B3: Build the tail-range probe

A PO-token-gated URL serves its first ~1 MB and then 403s, so "does it play" is not answerable by fetching the head. Probe the last byte.

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/preview/AudioUrlTailProbe.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/preview/AudioUrlTailProbeTest.kt`

- [ ] **Step 1: Write the failing test**

`data/download` already has `mockwebserver` on `testImplementation` (`build.gradle.kts:169`).

```kotlin
package com.stash.data.download.preview

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AudioUrlTailProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var probe: AudioUrlTailProbe

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        probe = AudioUrlTailProbe(OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `206 on the tail byte passes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = 5_000_000L)).isTrue()
    }

    @Test fun `403 on the tail byte fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = 5_000_000L)).isFalse()
    }

    /** Absence of contentLength is not evidence of gating — don't reject. */
    @Test fun `missing contentLength passes without a request`() = runTest {
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = null)).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `the probe requests only the final byte`() = runTest {
        server.enqueue(MockResponse().setResponseCode(206).setBody("x"))
        probe.servesFullFile(server.url("/a").toString(), contentLength = 1000L)
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=999-")
    }

    /** A network failure must not veto a URL we have no evidence against. */
    @Test fun `transport failure passes`() = runTest {
        server.shutdown()
        assertThat(probe.servesFullFile(server.url("/a").toString(), contentLength = 1000L)).isTrue()
    }
}
```

- [ ] **Step 2: Run and confirm it fails to compile (class does not exist)**

```bash
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.AudioUrlTailProbeTest"
```

Expected: FAIL — unresolved reference `AudioUrlTailProbe`.

- [ ] **Step 3: Implement the probe**

```kotlin
package com.stash.data.download.preview

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proves an audio URL can serve its WHOLE body, not just its opening megabyte.
 *
 * InnerTube can hand back PO-token-gated URLs that stream ~1 MB and then 403.
 * Those played fine in a preview and died mid-track in playback — the failure
 * that got the InnerTube fast lane removed from the playback path on
 * 2026-06-08. A HEAD or head-range request cannot tell the two apart; the last
 * byte can.
 *
 * Fails OPEN: no `contentLength`, a timeout, or a transport error all return
 * true. We only reject on an explicit refusal from the CDN, because a false
 * rejection costs a needless 3.6 s yt-dlp fallback while a false acceptance is
 * caught downstream by RefreshingDataSourceFactory's 403 re-resolve.
 */
@Singleton
class AudioUrlTailProbe @Inject constructor(private val client: OkHttpClient) {

    suspend fun servesFullFile(url: String, contentLength: Long?): Boolean {
        if (contentLength == null || contentLength <= 0L) return true
        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=${contentLength - 1}-")
                        .build()
                    client.newBuilder()
                        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .build()
                        .newCall(request)
                        .execute()
                        .use { it.code }
                }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    Log.d(TAG, "tail probe transport failure for ${url.take(60)}: ${t.message}")
                    null
                }
            }
        }
        if (result == null) return true
        val ok = result == 206 || result == 200
        if (!ok) Log.i(TAG, "tail probe rejected a gated URL: code=$result")
        return ok
    }

    private companion object {
        private const val TAG = "AudioUrlTailProbe"
        private const val PROBE_TIMEOUT_MS = 2_000L
    }
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.AudioUrlTailProbeTest"
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/AudioUrlTailProbe.kt data/download/src/test/kotlin/com/stash/data/download/preview/AudioUrlTailProbeTest.kt
git commit -m "feat(preview): tail-range probe proving an audio URL serves its whole body"
```

---

### Task B4: Gate InnerTube results behind the probe

`selectBestAudioUrl` returns only a `String`, so the chosen format's `contentLength` is lost. Split the selection from the URL extraction.

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt:143-156`, `:428-472`, constructor `:76-81`

- [ ] **Step 1: Add `selectBestAudioFormat`, keeping `selectBestAudioUrl` as a delegating wrapper**

Existing tests call `selectBestAudioUrl`; do not break them.

```kotlin
        /** Format-returning form of [selectBestAudioUrl] — keeps `contentLength`
         *  reachable so the caller can tail-probe the URL. */
        internal fun selectBestAudioFormat(formats: List<JsonObject>): JsonObject? {
            val audio = formats.filter { f ->
                (f["mimeType"]?.jsonPrimitive?.content ?: "").startsWith("audio/") && f["url"] != null
            }
            fun bitrate(f: JsonObject) = f["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            fun isOpus(f: JsonObject) = (f["mimeType"]?.jsonPrimitive?.content ?: "").contains("opus")
            val opusBest = audio.filter(::isOpus).maxByOrNull(::bitrate)
            val best = opusBest ?: audio.maxByOrNull(::bitrate)
            if (best != null) {
                val mime = best["mimeType"]?.jsonPrimitive?.content
                Log.d(TAG, "InnerTube selected mime=$mime bitrate=${bitrate(best)} opusPreferred=${opusBest != null}")
            }
            return best
        }

        internal fun selectBestAudioUrl(formats: List<JsonObject>): String? =
            selectBestAudioFormat(formats)?.get("url")?.jsonPrimitive?.content
```

- [ ] **Step 2: Inject the probe and apply it in `extractViaInnerTube`**

Add `private val tailProbe: AudioUrlTailProbe,` to the constructor (`:76-81`). Replace the `selectBestAudioUrl` block at `:463-467` with:

```kotlin
            val bestFormat = selectBestAudioFormat(adaptiveFormats.filterIsInstance<JsonObject>()) ?: run {
                Log.d(TAG, "InnerTube: no audio formats with direct URL for $videoId " +
                    "(${adaptiveFormats.size} total formats, all may be ciphered)")
                return@withTimeout null
            }
            val streamUrl = bestFormat["url"]?.jsonPrimitive?.content ?: return@withTimeout null
            val contentLength = bestFormat["contentLength"]?.jsonPrimitive?.content?.toLongOrNull()

            // A PO-token-gated URL serves ~1 MB then 403s. Returning one here is
            // what killed playback on 2026-06-08; the probe is what makes the
            // fast lane safe to use for full tracks rather than previews only.
            if (!tailProbe.servesFullFile(streamUrl, contentLength)) {
                Log.i(TAG, "InnerTube: URL failed the tail probe for $videoId — deferring to yt-dlp")
                return@withTimeout null
            }
```

- [ ] **Step 3: Run the module suite**

```bash
./gradlew :data:download:testDebugUnitTest
```

Expected: PASS. Hilt supplies `OkHttpClient` in this module already (see `AmzApiClient`); if the injection fails to resolve, add the probe to the same Hilt module that provides the client rather than creating a new one.

- [ ] **Step 4: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt
git commit -m "fix(preview): tail-probe InnerTube URLs before returning them"
```

---

### Task B5: Point playback at the race

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/YouTubeStreamResolver.kt:90-115`, class KDoc `:28-44`

- [ ] **Step 1: Replace the yt-dlp-direct call with the race**

At `:99-105`, replace the `if (allowYtDlp)` branch:

```kotlin
            runCatching {
                // Both lanes: InnerTube (ANDROID_VR, ~200-500ms) raced against
                // yt-dlp, first success wins. Playback used to call
                // extractStreamUrlViaYtDlp directly, which made the race dead
                // code and put a Python process spawn on every tap. Safe to
                // race again because AudioUrlTailProbe now rejects the
                // PO-token-gated URLs that forced the 2026-06-08 bypass.
                urlExtractor.extractStreamUrl(videoId, allowYtDlp = allowYtDlp)
            }
```

Update the class KDoc at `:28-44` to describe the current behaviour: both paths race; `allowYtDlp = false` is InnerTube-only for the fast-lane contract.

- [ ] **Step 2: Run the module suite**

```bash
./gradlew :core:media:testDebugUnitTest
```

Expected: PASS (345 tests). Per `infra_core_media_test_flaky_hang` this suite has hung historically — if it hangs, that regression was fixed by using `UnconfinedTestDispatcher` in `LoudnessGainProcessorTest`; a fresh hang is worth a jstack rather than a retry.

- [ ] **Step 3: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/YouTubeStreamResolver.kt
git commit -m "perf(stream): race InnerTube against yt-dlp on the playback path"
```

---

### Task B6: Verify end to end on device

- [ ] **Step 1: Install**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Confirm the fast lane wins and the track plays to completion**

```powershell
adb logcat -c
# play a YT-fallback track; let it run past the 1MB mark (60+ seconds)
Start-Sleep -Seconds 75
adb logcat -d | Select-String "chain for|youtube served|won with variant|tail probe|yt-dlp: invoking"
```

Expected: `won with variant=ANDROID_VR`, `youtube served`, **no** `yt-dlp: invoking`, and no 403/re-resolve after the first minute. Playing past 1 MB is the point — a gated URL survives a short listen.

- [ ] **Step 3: Re-measure and compare against B1's numbers**

Record the before/after in the spec document.

- [ ] **Step 4: Confirm no lossless regression**

Play a track that qbdlx can serve and confirm `qbdlx served` still appears — this change must not alter source priority, only the YT fallback's cost.

- [ ] **Step 5: Whole-repo test run before proposing a release**

```bash
./gradlew testDebugUnitTest --continue
```

Expected: green across all 15 modules (1892 baseline).

- [ ] **Step 6: Commit and stop for review**

---

## Follow-ups (not in this plan)

- yt-dlp cold-start and the per-call `--remote-components ejs:github` fetch, if B1's numbers show they dominate.
- #264 (slow offline loads) and #334 (streamed tracks skipping) — re-check after B ships; both are plausibly downstream but unproven.
- `deleteOrphanedQueueEntries` and `cancelDownloadsWithNoEnabledPlaylist` are near-duplicate sweeps. Consolidating them would remove a class of "fixed one, missed the other" bug, but it is refactoring beyond this fix.
- `YtLibraryBackfillWorker` stays dormant. Nothing enqueues it; re-arming it is a separate decision.
