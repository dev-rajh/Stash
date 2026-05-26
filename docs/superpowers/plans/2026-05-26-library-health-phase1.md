# Library Health Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every subagent dispatch in this project must use `model: "opus"` per project policy.

**Goal:** Ship v0.9.38 with a Failed Downloads viewer in the Sync tab, an auth-expiry probe + amber re-auth banner, and the Opus `METADATA_BLOCK_PICTURE` cover-art fix from #95.

**Architecture:** Two PRs in one release. PR 1 (Tasks 1–21) adds the failure taxonomy, classifier, atomic-retry DAO surface, auth probes at the head of `PlaylistFetchWorker`, and the Compose screen + banner. PR 2 (Tasks 22–25) fixes Opus cover art by building a FLAC Picture metadata block and passing it via `-metadata METADATA_BLOCK_PICTURE`. The two PRs are independent — PR 2 can land first to de-risk the release.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room (schema v28 → v29), WorkManager, OkHttp, ffmpeg (vendored).

**Spec:** `docs/superpowers/specs/2026-05-26-library-health-phase1-design.md` (commit `6f1723e`).

---

## Pre-flight (run once before starting Task 1)

- [ ] **PF-1: Confirm clean working tree on `master`**

```bash
git status        # expect: clean
git log -1 --oneline    # confirm at d80c5de or later
```

- [ ] **PF-2: Create feature worktree**

Per the project's worktree pattern (memory: `feedback_worktree_local_properties.md`), copy `local.properties` after `git worktree add` so Last.fm and other secrets are visible.

```bash
git worktree add ../MP3APK-v0938 -b feat/library-health-phase1
cp local.properties ../MP3APK-v0938/local.properties
cd ../MP3APK-v0938
```

- [ ] **PF-3: Sanity build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. If not, stop and fix before continuing.

---

## PR 1 — Failed Downloads viewer + Auth probe

### Task 1: Expand `DownloadFailureType` enum

**Files:**
- Modify: `core/model/src/main/kotlin/com/stash/core/model/DownloadFailureType.kt`

- [ ] **Step 1: Open the existing enum**

Current file:
```kotlin
enum class DownloadFailureType { NONE, NO_MATCH, DOWNLOAD_ERROR }
```

- [ ] **Step 2: Rewrite the enum**

```kotlin
package com.stash.core.model

/**
 * Why a download failed. NONE for not-yet-failed rows; NO_MATCH owned by the
 * matching layer and surfaced in FailedMatchesScreen. The remaining buckets
 * are produced by [DownloadFailureClassifier] in the data/download module and
 * surfaced in FailedDownloadsScreen.
 */
enum class DownloadFailureType {
    NONE,
    NO_MATCH,
    AUTH_EXPIRED,
    NETWORK,
    PROVIDER_UNAVAILABLE,
    FFMPEG_ERROR,
    STORAGE_ERROR,
    UNKNOWN,
}
```

- [ ] **Step 3: Compile-check**

```bash
./gradlew :core:model:compileKotlin
```

Expected: BUILD SUCCESSFUL. (Any other module that references `DOWNLOAD_ERROR` will fail to compile in later tasks and we'll fix as we go.)

- [ ] **Step 4: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/DownloadFailureType.kt
git commit -m "feat(model): expand DownloadFailureType taxonomy (v0.9.38)

Adds AUTH_EXPIRED, NETWORK, PROVIDER_UNAVAILABLE, FFMPEG_ERROR,
STORAGE_ERROR, UNKNOWN. Renames legacy DOWNLOAD_ERROR -> UNKNOWN.

Migration to follow in Task 4."
```

---

### Task 2: Update Room TypeConverter and any direct `DOWNLOAD_ERROR` references

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/Converters.kt` (verify exists or locate the existing converter)
- Grep + fix all production references to `DOWNLOAD_ERROR`

- [ ] **Step 1: Find every reference to `DOWNLOAD_ERROR`**

```bash
grep -rn "DOWNLOAD_ERROR" --include="*.kt" .
```

Expected: a small number of hits — Converters, callers writing `markFailed(... DownloadFailureType.DOWNLOAD_ERROR)`, possibly tests.

- [ ] **Step 2: Replace each production reference with `UNKNOWN`**

The classifier replaces these in a later task; for now we just need the codebase to compile against the new enum. Use Edit per file. Do NOT change test files yet — they may be migration-test seed fixtures we keep as the literal string `"DOWNLOAD_ERROR"`.

- [ ] **Step 3: Compile-check**

```bash
./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(data): update DOWNLOAD_ERROR call sites to UNKNOWN"
```

---

### Task 3: Add `FailureContext` and `DownloadPhase` types

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/classifier/FailureContext.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.stash.data.download.classifier

/**
 * Phase of the download lifecycle a failure surfaced in. The classifier
 * uses phase to disambiguate identical HTTP statuses that mean different
 * things at different points (e.g. a 401 during MATCHING is a Spotify
 * cookie issue; during DOWNLOADING it's a YouTube cookie issue).
 */
enum class DownloadPhase { MATCHING, DOWNLOADING, PROCESSING, TAGGING, STORAGE }

/**
 * Input to [DownloadFailureClassifier.classify]. Pure data — no side
 * effects. Workers populate this from the raw failure they observed
 * (error text, optional HTTP status, optional cause chain).
 */
data class FailureContext(
    val phase: DownloadPhase,
    val errorText: String?,
    val httpStatus: Int? = null,
    val causeChain: List<String> = emptyList(),
)
```

- [ ] **Step 2: Compile-check**

```bash
./gradlew :data:download:compileKotlin
```

- [ ] **Step 3: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/classifier/FailureContext.kt
git commit -m "feat(download): add FailureContext + DownloadPhase types"
```

---

### Task 4: Create `DownloadFailureClassifier` (TDD)

**Files:**
- Create: `data/download/src/test/kotlin/com/stash/data/download/classifier/DownloadFailureClassifierTest.kt`
- Create: `data/download/src/main/kotlin/com/stash/data/download/classifier/DownloadFailureClassifier.kt`

- [ ] **Step 1: Write the failing test file**

```kotlin
package com.stash.data.download.classifier

import com.stash.core.model.DownloadFailureType
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFailureClassifierTest {

    private val classifier = DownloadFailureClassifier()

    @Test fun `401 maps to AUTH_EXPIRED regardless of phase`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "HTTP Error 401: Unauthorized",
            httpStatus = 401,
        ))
        assertEquals(DownloadFailureType.AUTH_EXPIRED, result)
    }

    @Test fun `login-required text maps to AUTH_EXPIRED`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "Sign in to confirm you're not a bot. Use --cookies",
        ))
        assertEquals(DownloadFailureType.AUTH_EXPIRED, result)
    }

    @Test fun `video unavailable maps to PROVIDER_UNAVAILABLE`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "ERROR: Video unavailable. This video is no longer available.",
        ))
        assertEquals(DownloadFailureType.PROVIDER_UNAVAILABLE, result)
    }

    @Test fun `socket timeout cause maps to NETWORK`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = null,
            causeChain = listOf("SocketTimeoutException"),
        ))
        assertEquals(DownloadFailureType.NETWORK, result)
    }

    @Test fun `unknown host text maps to NETWORK`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.MATCHING,
            errorText = "Unable to resolve host \"i.ytimg.com\": No address associated with hostname",
        ))
        assertEquals(DownloadFailureType.NETWORK, result)
    }

    @Test fun `ffmpeg exit code at PROCESSING maps to FFMPEG_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.PROCESSING,
            errorText = "ffmpeg exited with code 234: muxer not found",
        ))
        assertEquals(DownloadFailureType.FFMPEG_ERROR, result)
    }

    @Test fun `ENOSPC at STORAGE phase maps to STORAGE_ERROR`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.STORAGE,
            errorText = "ENOSPC: no space left on device",
        ))
        assertEquals(DownloadFailureType.STORAGE_ERROR, result)
    }

    @Test fun `unrecognised text maps to UNKNOWN`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = "some weird error nobody has ever seen",
        ))
        assertEquals(DownloadFailureType.UNKNOWN, result)
    }

    @Test fun `null errorText with no cause and no status maps to UNKNOWN`() {
        val result = classifier.classify(FailureContext(
            phase = DownloadPhase.DOWNLOADING,
            errorText = null,
        ))
        assertEquals(DownloadFailureType.UNKNOWN, result)
    }
}
```

- [ ] **Step 2: Run test — expect compile failure**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*DownloadFailureClassifierTest*"
```

Expected: compile error, "DownloadFailureClassifier not found."

- [ ] **Step 3: Implement the classifier**

```kotlin
package com.stash.data.download.classifier

import android.util.Log
import com.stash.core.model.DownloadFailureType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure classifier that maps a [FailureContext] to a [DownloadFailureType]
 * bucket. Patterns are ordered — first match wins. Add new patterns to the
 * top of the chain unless the new pattern is strictly more specific than
 * an existing one.
 *
 * Every UNKNOWN bucket assignment logs the raw context to logcat so we can
 * iterate the pattern table release-over-release.
 */
@Singleton
class DownloadFailureClassifier @Inject constructor() {

    fun classify(ctx: FailureContext): DownloadFailureType {
        val text = ctx.errorText?.lowercase().orEmpty()
        val causes = ctx.causeChain

        // 1. Auth (status takes priority over text)
        if (ctx.httpStatus in 401..403) return DownloadFailureType.AUTH_EXPIRED
        if (AUTH_TEXT.any { it in text }) return DownloadFailureType.AUTH_EXPIRED

        // 2. Provider unavailability
        if (PROVIDER_TEXT.any { it in text }) return DownloadFailureType.PROVIDER_UNAVAILABLE

        // 3. Network
        if (NETWORK_TEXT.any { it in text }) return DownloadFailureType.NETWORK
        if (causes.any { it in NETWORK_CAUSES }) return DownloadFailureType.NETWORK

        // 4. ffmpeg (only meaningful at processing phase)
        if (ctx.phase == DownloadPhase.PROCESSING && FFMPEG_TEXT.any { it in text })
            return DownloadFailureType.FFMPEG_ERROR

        // 5. Storage
        if (ctx.phase == DownloadPhase.STORAGE) return DownloadFailureType.STORAGE_ERROR
        if (STORAGE_TEXT.any { it in text }) return DownloadFailureType.STORAGE_ERROR

        // 6. Catchall
        Log.w(TAG, "UNKNOWN failure: phase=${ctx.phase} status=${ctx.httpStatus} text=${ctx.errorText}")
        return DownloadFailureType.UNKNOWN
    }

    private companion object {
        const val TAG = "FailureClassifier"

        val AUTH_TEXT = listOf(
            "login required", "sign in", "captcha", "403 forbidden",
            "unauthorized", "401",
        )
        val PROVIDER_TEXT = listOf(
            "video unavailable", "unable to extract", "private video",
            "this video has been removed", "not available in your country",
            "copyright", "region",
        )
        val NETWORK_TEXT = listOf(
            "timed out", "timeout", "unable to resolve host", "no address",
            "connection reset", "connection refused", "unreachable",
        )
        val NETWORK_CAUSES = setOf(
            "SocketTimeoutException", "UnknownHostException",
            "ConnectException", "SSLException",
        )
        val FFMPEG_TEXT = listOf(
            "ffmpeg", "exit code", "muxer", "codec",
        )
        val STORAGE_TEXT = listOf(
            "enospc", "no space left", "permission denied",
            "saf", "no such file", "eacces",
        )
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*DownloadFailureClassifierTest*"
```

Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/classifier/DownloadFailureClassifier.kt \
        data/download/src/test/kotlin/com/stash/data/download/classifier/DownloadFailureClassifierTest.kt
git commit -m "feat(download): add DownloadFailureClassifier with 6-rule pattern table"
```

---

### Task 5: Database migration MIGRATION_28_29 (TDD)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`
- Locate or create: `core/data/src/androidTest/kotlin/com/stash/core/data/db/StashDatabaseMigrationTest.kt`

- [ ] **Step 1: Find the existing migration test class**

```bash
find core/data/src/androidTest -name "StashDatabaseMigrationTest*" 2>/dev/null
```

If it exists, append the test below. If not, create the file with the standard `MigrationTestHelper` boilerplate from prior migration tests in the project (read the most recent migration test as a template).

- [ ] **Step 2: Write the failing migration test**

```kotlin
@Test
fun migration_28_29_rewrites_DOWNLOAD_ERROR_to_UNKNOWN() {
    helper.createDatabase(TEST_DB, 28).apply {
        execSQL("""
            INSERT INTO download_queue
                (id, track_id, status, failure_type, retry_count, created_at)
            VALUES
                (1, 1, 'FAILED', 'DOWNLOAD_ERROR', 0, ${System.currentTimeMillis()})
        """.trimIndent())
        close()
    }

    val db = helper.runMigrationsAndValidate(TEST_DB, 29, true, StashDatabase.MIGRATION_28_29)
    db.query("SELECT failure_type FROM download_queue WHERE id = 1").use { c ->
        assertTrue(c.moveToFirst())
        assertEquals("UNKNOWN", c.getString(0))
    }
}
```

- [ ] **Step 3: Run test — expect FAIL**

```bash
./gradlew :core:data:connectedDebugAndroidTest --tests "*StashDatabaseMigrationTest.migration_28_29*"
```

Expected: FAIL (migration not defined).

- [ ] **Step 4: Add the migration to `StashDatabase.kt`**

Bump `version = 28` to `version = 29`. Add the migration object in the `companion object`:

```kotlin
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE download_queue SET failure_type = 'UNKNOWN' WHERE failure_type = 'DOWNLOAD_ERROR'"
        )
    }
}
```

Then register `MIGRATION_28_29` in the `addMigrations(...)` call in `DatabaseModule` (or wherever the existing migrations are wired — grep for `MIGRATION_27_28` to find it).

- [ ] **Step 5: Run test — expect PASS**

```bash
./gradlew :core:data:connectedDebugAndroidTest --tests "*StashDatabaseMigrationTest.migration_28_29*"
```

- [ ] **Step 6: Generate the new schema JSON**

```bash
./gradlew :core:data:compileDebugKotlin
```

This should produce `core/data/schemas/com.stash.core.data.db.StashDatabase/29.json`. Verify it exists.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/androidTest/kotlin/com/stash/core/data/db/StashDatabaseMigrationTest.kt \
        core/data/schemas/com.stash.core.data.db.StashDatabase/29.json \
        core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt
git commit -m "feat(db): MIGRATION_28_29 — DOWNLOAD_ERROR -> UNKNOWN"
```

---

### Task 6: Extend `DownloadQueueDao` with classified `markFailed` + atomic claim helpers

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt`
- Create: `core/data/src/androidTest/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoFailedDownloadsTest.kt`

- [ ] **Step 1: Write the failing DAO test**

```kotlin
@RunWith(AndroidJUnit4::class)
class DownloadQueueDaoFailedDownloadsTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.downloadQueueDao()
    }
    @After fun tearDown() { db.close() }

    @Test fun atomicallyClaimForRetry_returns_1_when_row_is_FAILED() = runTest {
        seedFailed(queueId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        val affected = dao.atomicallyClaimForRetry(1)
        assertEquals(1, affected)
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.PENDING, row.status)
        assertNull(row.errorMessage)
        assertEquals(DownloadFailureType.NONE, row.failureType)
    }

    @Test fun atomicallyClaimForRetry_returns_0_when_row_is_PENDING() = runTest {
        seedFailed(queueId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        dao.atomicallyClaimForRetry(1)              // first claim succeeds
        val affected = dao.atomicallyClaimForRetry(1) // second claim no-ops
        assertEquals(0, affected)
    }

    @Test fun markFailed_writes_status_and_failureType_and_completedAt() = runTest {
        seedPending(queueId = 1)
        dao.markFailed(queueId = 1, errorMessage = "boom", failureType = DownloadFailureType.NETWORK)
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals("boom", row.errorMessage)
        assertEquals(DownloadFailureType.NETWORK, row.failureType)
        assertNotNull(row.completedAt)
    }

    @Test fun getFailedDownloads_excludes_NONE_and_NO_MATCH() = runTest {
        seedFailed(queueId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        seedFailed(queueId = 2, type = DownloadFailureType.NO_MATCH)
        seedPending(queueId = 3)  // NONE
        val rows = dao.getFailedDownloads().first()
        assertEquals(1, rows.size)
        assertEquals(1L, rows[0].queueId)
    }

    // Helpers omitted — see existing DAO tests for seeding patterns.
}
```

- [ ] **Step 2: Run test — expect compile failure (new methods missing)**

```bash
./gradlew :core:data:connectedDebugAndroidTest --tests "*DownloadQueueDaoFailedDownloadsTest*"
```

- [ ] **Step 3: Add the new query methods to `DownloadQueueDao`**

Insert the following inside the `interface DownloadQueueDao { ... }` block. The exact location: after the existing `updateStatus` method.

```kotlin
data class FailedDownloadRow(
    val queueId: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val playlistName: String?,
    val failureType: DownloadFailureType,
    val errorMessage: String?,
    val retryCount: Int,
    val completedAt: Long?,
)

@Query("""
    SELECT dq.id AS queueId, t.id AS trackId, t.title, t.artist,
           t.album_art_url AS albumArtUrl,
           (SELECT p.name FROM playlists p
             INNER JOIN playlist_tracks pt ON pt.playlist_id = p.id
             WHERE pt.track_id = t.id AND pt.removed_at IS NULL
             ORDER BY p.id ASC LIMIT 1) AS playlistName,
           dq.failure_type AS failureType,
           dq.error_message AS errorMessage,
           dq.retry_count AS retryCount,
           dq.completed_at AS completedAt
      FROM download_queue dq
      INNER JOIN tracks t ON t.id = dq.track_id
     WHERE dq.status = 'FAILED'
       AND dq.failure_type != 'NONE'
       AND dq.failure_type != 'NO_MATCH'
     ORDER BY COALESCE(dq.completed_at, dq.created_at) DESC
""")
fun getFailedDownloads(): Flow<List<FailedDownloadRow>>

@Query("""
    UPDATE download_queue
       SET status = 'PENDING', error_message = NULL, failure_type = 'NONE'
     WHERE id = :queueId AND status = 'FAILED'
""")
suspend fun atomicallyClaimForRetry(queueId: Long): Int

@Query("SELECT id FROM download_queue WHERE status = 'FAILED' AND failure_type = :type")
suspend fun selectFailedIdsByType(type: DownloadFailureType): List<Long>

@Query("SELECT id FROM download_queue WHERE status = 'FAILED' AND failure_type NOT IN ('NONE', 'NO_MATCH')")
suspend fun selectAllNonMatchFailedIds(): List<Long>

@Query("UPDATE download_queue SET status='PENDING', error_message=NULL, failure_type='NONE' WHERE id IN (:ids)")
suspend fun resetToPending(ids: List<Long>)

@Transaction
suspend fun atomicallyClaimGroupForRetry(type: DownloadFailureType): List<Long> {
    val ids = selectFailedIdsByType(type)
    if (ids.isNotEmpty()) resetToPending(ids)
    return ids
}

@Transaction
suspend fun atomicallyClaimAllForRetry(): List<Long> {
    val ids = selectAllNonMatchFailedIds()
    if (ids.isNotEmpty()) resetToPending(ids)
    return ids
}

@Query("""
    UPDATE download_queue
       SET status = 'FAILED',
           error_message = :errorMessage,
           failure_type = :failureType,
           completed_at = :completedAt
     WHERE id = :queueId
""")
suspend fun markFailed(
    queueId: Long,
    errorMessage: String?,
    failureType: DownloadFailureType,
    completedAt: Long = System.currentTimeMillis(),
)

@Query("SELECT * FROM download_queue WHERE id = :id LIMIT 1")
suspend fun getById(id: Long): DownloadQueueEntity?
```

If `getById` already exists in the interface (likely from an earlier task), don't duplicate it. If `@Transaction` import is missing at the top of the file, add `import androidx.room.Transaction`.

- [ ] **Step 4: Run tests — expect PASS**

```bash
./gradlew :core:data:connectedDebugAndroidTest --tests "*DownloadQueueDaoFailedDownloadsTest*"
```

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt \
        core/data/src/androidTest/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoFailedDownloadsTest.kt
git commit -m "feat(db): add FailedDownloadRow query + atomic claim/markFailed helpers"
```

---

### Task 7: Wire `DownloadFailureClassifier` into `TrackDownloadWorker`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackDownloadWorker.kt`

- [ ] **Step 1: Read the file**

Open `TrackDownloadWorker.kt` and find every call site that writes `errorMessage = ...` (per the earlier audit: lines ~238, 368, 381, 401, 451, 477 — line numbers may have drifted).

- [ ] **Step 2: Add the classifier dependency**

The worker is constructor-injected via Hilt. Add a `DownloadFailureClassifier` parameter to the `@AssistedInject` constructor (the same way other dependencies are passed today — read the file to confirm the exact pattern).

- [ ] **Step 3: Replace each failure call site**

For each existing `downloadQueueDao.updateStatus(... errorMessage = X ...)` call that sets status to FAILED, replace with `downloadQueueDao.markFailed(...)`:

```kotlin
val type = classifier.classify(FailureContext(
    phase = DownloadPhase.DOWNLOADING,   // adjust per call site
    errorText = err,                     // existing variable name; rename to fit
    httpStatus = null,                   // populate if the call site has it
    causeChain = listOf(),               // populate if call site has a Throwable
))
downloadQueueDao.markFailed(
    queueId = queueEntry.id,
    errorMessage = err?.take(1000),
    failureType = type,
)
```

Determine `phase` from the call-site context: matching/search failures → MATCHING; network/yt-dlp errors → DOWNLOADING; mux/ffmpeg → PROCESSING; SAF/file-IO → STORAGE.

- [ ] **Step 4: Compile**

```bash
./gradlew :core:data:compileDebugKotlin
```

- [ ] **Step 5: Manual unit-test smoke** (no new test file — wiring is covered by integration smoke later)

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackDownloadWorker.kt
git commit -m "feat(sync): route TrackDownloadWorker failures through DownloadFailureClassifier"
```

---

### Task 8: Add `BlockSource.FAILED_DOWNLOADS`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistGuard.kt`

- [ ] **Step 1: Edit the enum**

Replace:
```kotlin
enum class BlockSource {
    NOW_PLAYING, CONTEXT_MENU, PLAYLIST_DELETE, MIGRATION_V19, INTEGRITY_WORKER, OTHER
}
```
with:
```kotlin
enum class BlockSource {
    NOW_PLAYING, CONTEXT_MENU, PLAYLIST_DELETE,
    MIGRATION_V19, INTEGRITY_WORKER, FAILED_DOWNLOADS, OTHER
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :core:data:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/blocklist/BlocklistGuard.kt
git commit -m "feat(blocklist): add FAILED_DOWNLOADS BlockSource value"
```

---

### Task 9: Create `SingleTrackDownloadEnqueuer`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/single/SingleTrackDownloadEnqueuer.kt`

- [ ] **Step 1: Inspect how `TrackDownloadWorker` is normally enqueued**

```bash
grep -rn "TrackDownloadWorker" --include="*.kt" core/data/src/main/kotlin/com/stash/core/data/sync/ | head -10
```

Find the existing call that constructs a `OneTimeWorkRequest` with a `queueId` input data key.

- [ ] **Step 2: Write the enqueuer**

```kotlin
package com.stash.data.download.single

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.stash.core.data.sync.workers.TrackDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues a one-shot TrackDownloadWorker run for a single download_queue
 * row. Used by the Failed Downloads viewer to immediately retry a row the
 * user tapped, without waiting for the next scheduled sync.
 *
 * Uses a unique work name keyed by queueId so a second tap on the same
 * row coalesces with the in-flight retry instead of queueing two.
 */
@Singleton
class SingleTrackDownloadEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(queueId: Long) {
        val request = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .setInputData(Data.Builder().putLong(TrackDownloadWorker.KEY_QUEUE_ID, queueId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "single_track_$queueId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
```

If `TrackDownloadWorker.KEY_QUEUE_ID` doesn't exist, add it as a const inside the worker's companion object — read the worker to see what key it currently uses for the queueId input (it may already exist under a different name; use that).

- [ ] **Step 3: Compile**

```bash
./gradlew :data:download:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/single/SingleTrackDownloadEnqueuer.kt
git commit -m "feat(download): add SingleTrackDownloadEnqueuer for one-shot retries"
```

---

### Task 10: Create `AuthSource` + `AuthHealthProbe` interface

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/auth/AuthHealthProbe.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.stash.data.download.auth

enum class AuthSource { SPOTIFY, YOUTUBE }

/**
 * One per connected source. The sync chain runs these in parallel at the
 * start of every sync to detect silent cookie/token expiry before any
 * downloads attempt to use the bad credentials.
 *
 * isExpired() must be conservative: network failures and ambiguous
 * responses should return false (treat as healthy) rather than risk
 * false-positive banner storms when the user's internet is just flaky.
 */
interface AuthHealthProbe {
    val source: AuthSource
    suspend fun isExpired(): Boolean
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :data:download:compileDebugKotlin
git add data/download/src/main/kotlin/com/stash/data/download/auth/AuthHealthProbe.kt
git commit -m "feat(download): add AuthHealthProbe interface + AuthSource enum"
```

---

### Task 11: Implement `SpotifyAuthHealthProbe` (TDD)

**Files:**
- Create: `data/download/src/test/kotlin/com/stash/data/download/auth/SpotifyAuthHealthProbeTest.kt`
- Create: `data/download/src/main/kotlin/com/stash/data/download/auth/SpotifyAuthHealthProbe.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.stash.data.download.auth

import com.stash.data.spotify.SpotifyApiClient
import com.stash.data.spotify.model.UserInfo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthHealthProbeTest {

    private val api: SpotifyApiClient = mockk()
    private val probe = SpotifyAuthHealthProbe(api)

    @Test fun `returns true when getCurrentUserProfile returns null`() = runTest {
        coEvery { api.getCurrentUserProfile() } returns null
        assertTrue(probe.isExpired())
    }

    @Test fun `returns false when getCurrentUserProfile returns a profile`() = runTest {
        coEvery { api.getCurrentUserProfile() } returns UserInfo(id = "abc", displayName = "x")
        assertFalse(probe.isExpired())
    }

    @Test fun `returns false when network throws`() = runTest {
        coEvery { api.getCurrentUserProfile() } throws RuntimeException("network down")
        assertFalse(probe.isExpired())  // conservative
    }
}
```

If `UserInfo` has different parameter names, adjust per the actual model.

- [ ] **Step 2: Run — expect FAIL (class missing)**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*SpotifyAuthHealthProbeTest*"
```

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.auth

import android.util.Log
import com.stash.data.spotify.SpotifyApiClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAuthHealthProbe @Inject constructor(
    private val api: SpotifyApiClient,
) : AuthHealthProbe {
    override val source: AuthSource = AuthSource.SPOTIFY

    override suspend fun isExpired(): Boolean = try {
        api.getCurrentUserProfile() == null
    } catch (e: Throwable) {
        Log.w("SpotifyAuthProbe", "probe failed conservatively", e)
        false
    }
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*SpotifyAuthHealthProbeTest*"
```

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/auth/SpotifyAuthHealthProbe.kt \
        data/download/src/test/kotlin/com/stash/data/download/auth/SpotifyAuthHealthProbeTest.kt
git commit -m "feat(download): SpotifyAuthHealthProbe via getCurrentUserProfile"
```

---

### Task 12: Implement `YoutubeAuthHealthProbe` (TDD)

**Files:**
- Create: `data/download/src/test/kotlin/com/stash/data/download/auth/YoutubeAuthHealthProbeTest.kt`
- Create: `data/download/src/main/kotlin/com/stash/data/download/auth/YoutubeAuthHealthProbe.kt`

The probe uses a single non-paginated `getUserPlaylists()` call. `YTMusicApiClient.getUserPlaylists()` returns `SyncResult.Empty` for an unauthenticated browse (per the existing kdoc on that method) and `SyncResult.Success` when authenticated.

- [ ] **Step 1: Write failing test**

```kotlin
package com.stash.data.download.auth

import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.PagedPlaylists
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAuthHealthProbeTest {

    private val api: YTMusicApiClient = mockk()
    private val probe = YoutubeAuthHealthProbe(api)

    @Test fun `returns true when getUserPlaylists returns Empty`() = runTest {
        coEvery { api.getUserPlaylists() } returns SyncResult.Empty("Library returned no playlists")
        assertTrue(probe.isExpired())
    }

    @Test fun `returns false when getUserPlaylists returns Success`() = runTest {
        coEvery { api.getUserPlaylists() } returns SyncResult.Success(
            PagedPlaylists(playlists = emptyList(), partial = false, partialReason = null)
        )
        assertFalse(probe.isExpired())
    }

    @Test fun `returns false when getUserPlaylists returns Error (conservative)`() = runTest {
        coEvery { api.getUserPlaylists() } returns SyncResult.Error("network down")
        assertFalse(probe.isExpired())
    }
}
```

If `PagedPlaylists` or `SyncResult.Empty` ctor differ, adjust per actual model.

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*YoutubeAuthHealthProbeTest*"
```

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.auth

import android.util.Log
import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.YTMusicApiClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YoutubeAuthHealthProbe @Inject constructor(
    private val api: YTMusicApiClient,
) : AuthHealthProbe {
    override val source: AuthSource = AuthSource.YOUTUBE

    override suspend fun isExpired(): Boolean = try {
        // YT InnerTube browse against the user's library — an unauthenticated
        // request returns Empty per YTMusicApiClient.getUserPlaylists kdoc.
        when (api.getUserPlaylists()) {
            is SyncResult.Empty -> true
            is SyncResult.Success -> false
            is SyncResult.Error -> false  // conservative — could be network
        }
    } catch (e: Throwable) {
        Log.w("YtAuthProbe", "probe failed conservatively", e)
        false
    }
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*YoutubeAuthHealthProbeTest*"
```

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/auth/YoutubeAuthHealthProbe.kt \
        data/download/src/test/kotlin/com/stash/data/download/auth/YoutubeAuthHealthProbeTest.kt
git commit -m "feat(download): YoutubeAuthHealthProbe via getUserPlaylists"
```

---

### Task 13: Add `authExpiry` flow + `onAuthExpiryProbed` to `SyncStateManager`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/SyncStateManager.kt`

- [ ] **Step 1: Add to the class**

Inside `SyncStateManager`, after the existing `_phase` field:

```kotlin
private val _authExpiry = MutableStateFlow(AuthExpiryState(false, false))
val authExpiry: StateFlow<AuthExpiryState> = _authExpiry.asStateFlow()

fun onAuthExpiryProbed(state: AuthExpiryState) {
    _authExpiry.value = state
}
```

And define the data class at file scope (above the class):

```kotlin
data class AuthExpiryState(
    val spotifyExpired: Boolean,
    val youtubeExpired: Boolean,
) {
    val anyExpired: Boolean get() = spotifyExpired || youtubeExpired
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :core:data:compileDebugKotlin
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/SyncStateManager.kt
git commit -m "feat(sync): expose authExpiry StateFlow on SyncStateManager"
```

---

### Task 14: Wire probes at head of `PlaylistFetchWorker` (TDD)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorkerAuthProbeTest.kt`

- [ ] **Step 1: Write failing test**

The exact test shape depends on whether `PlaylistFetchWorker` is constructor-injected via Hilt's `@AssistedInject` and whether existing tests already mock its dependencies — read the worker and any existing test to see the pattern. The test should verify:
- Both probes are called in parallel before `onFetchingPlaylists()`.
- If `spotifyProbe.isExpired() == true`, the worker calls `onAuthExpiryProbed(AuthExpiryState(true, false))` and returns `Result.failure()`.
- If both return false, the worker proceeds normally.

Write the test with mocked `SpotifyAuthHealthProbe` and `YoutubeAuthHealthProbe`.

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :core:data:testDebugUnitTest --tests "*PlaylistFetchWorkerAuthProbeTest*"
```

- [ ] **Step 3: Wire probes into `PlaylistFetchWorker`**

Add `SpotifyAuthHealthProbe` and `YoutubeAuthHealthProbe` to the worker's constructor (mirror the existing dependency injection pattern). At the top of `doWork()` (or whatever the existing entrypoint is, before any playlist IO):

```kotlin
syncStateManager.onAuthenticating()

val (spotifyExpired, youtubeExpired) = coroutineScope {
    val s = async { spotifyProbe.isExpired() }
    val y = async { youtubeProbe.isExpired() }
    s.await() to y.await()
}
val authState = AuthExpiryState(spotifyExpired, youtubeExpired)
syncStateManager.onAuthExpiryProbed(authState)

if (authState.anyExpired) {
    Log.i(TAG, "Auth expired (spotify=$spotifyExpired, youtube=$youtubeExpired); aborting sync chain")
    return Result.failure()
}
```

Only probe sources the user has actually connected — wrap each probe in a `if (sourceConnected) ... else false` check using the existing TokenManager or connector state. The user shouldn't see a "YouTube expired" banner if they never connected YouTube.

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew :core:data:testDebugUnitTest --tests "*PlaylistFetchWorkerAuthProbeTest*"
```

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorkerAuthProbeTest.kt
git commit -m "feat(sync): probe Spotify+YouTube auth at PlaylistFetchWorker head"
```

---

### Task 15: Create `FailureReasonDisplay`

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailureReasonDisplay.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.stash.feature.sync

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.stash.core.model.DownloadFailureType

data class FailureReasonDisplay(
    val icon: ImageVector,
    val tint: Color,
    val groupTitle: String,
    val shortLabel: String,
)

/**
 * Display ordering: high-leverage groups (one fix repairs many rows) first.
 * Groups with zero count are filtered out of the UI.
 */
val FailureReasonDisplayOrder: List<DownloadFailureType> = listOf(
    DownloadFailureType.AUTH_EXPIRED,
    DownloadFailureType.STORAGE_ERROR,
    DownloadFailureType.NETWORK,
    DownloadFailureType.PROVIDER_UNAVAILABLE,
    DownloadFailureType.FFMPEG_ERROR,
    DownloadFailureType.UNKNOWN,
)

fun DownloadFailureType.display(): FailureReasonDisplay = when (this) {
    DownloadFailureType.AUTH_EXPIRED -> FailureReasonDisplay(
        icon = Icons.Default.Key,
        tint = Color(0xFFFFAA00),
        groupTitle = "Sign-in expired",
        shortLabel = "Sign-in expired",
    )
    DownloadFailureType.STORAGE_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Folder,
        tint = Color(0xFF888888),
        groupTitle = "Storage unreachable",
        shortLabel = "Storage error",
    )
    DownloadFailureType.NETWORK -> FailureReasonDisplay(
        icon = Icons.Default.WifiOff,
        tint = Color(0xFF508CFF),
        groupTitle = "Network errors",
        shortLabel = "Network error",
    )
    DownloadFailureType.PROVIDER_UNAVAILABLE -> FailureReasonDisplay(
        icon = Icons.Default.CloudOff,
        tint = Color(0xFFAA66CC),
        groupTitle = "Source unavailable",
        shortLabel = "Source unavailable",
    )
    DownloadFailureType.FFMPEG_ERROR -> FailureReasonDisplay(
        icon = Icons.Default.Settings,
        tint = Color(0xFFFF5A5A),
        groupTitle = "Encoding errors",
        shortLabel = "Encoding error",
    )
    DownloadFailureType.UNKNOWN -> FailureReasonDisplay(
        icon = Icons.Default.Help,
        tint = Color(0xFFAAAAAA),
        groupTitle = "Other errors",
        shortLabel = "Unknown error",
    )
    DownloadFailureType.NONE,
    DownloadFailureType.NO_MATCH -> error("Not surfaced in FailedDownloadsScreen")
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :feature:sync:compileDebugKotlin
git add feature/sync/src/main/kotlin/com/stash/feature/sync/FailureReasonDisplay.kt
git commit -m "feat(ui): add FailureReasonDisplay mapping + ordering"
```

---

### Task 16: Create `FailedDownloadsViewModel` (TDD)

**Files:**
- Create: `feature/sync/src/test/kotlin/com/stash/feature/sync/FailedDownloadsViewModelTest.kt`
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedDownloadsViewModel.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.stash.feature.sync

import com.stash.core.data.blocklist.BlockSource
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadFailureType
import com.stash.data.download.single.SingleTrackDownloadEnqueuer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FailedDownloadsViewModelTest {

    private val dao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val enqueuer: SingleTrackDownloadEnqueuer = mockk(relaxed = true)
    private val guard: BlocklistGuard = mockk(relaxed = true)
    private val vm by lazy { FailedDownloadsViewModel(dao, trackDao, enqueuer, guard) }

    @Test fun `retry only enqueues when atomic claim succeeds`() = runTest {
        coEvery { dao.atomicallyClaimForRetry(42) } returns 1
        vm.retry(42)
        coVerify(exactly = 1) { enqueuer.enqueue(42) }
    }

    @Test fun `retry does not enqueue when row is no longer FAILED`() = runTest {
        coEvery { dao.atomicallyClaimForRetry(42) } returns 0
        vm.retry(42)
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test fun `retryGroup enqueues every claimed id`() = runTest {
        coEvery { dao.atomicallyClaimGroupForRetry(DownloadFailureType.AUTH_EXPIRED) } returns listOf(1L, 2L, 3L)
        vm.retryGroup(DownloadFailureType.AUTH_EXPIRED)
        coVerify(exactly = 1) { enqueuer.enqueue(1) }
        coVerify(exactly = 1) { enqueuer.enqueue(2) }
        coVerify(exactly = 1) { enqueuer.enqueue(3) }
    }

    @Test fun `block resolves track then calls guard with FAILED_DOWNLOADS source`() = runTest {
        val track = mockk<TrackEntity>(relaxed = true)
        coEvery { trackDao.getById(7) } returns track
        vm.block(7)
        coVerify(exactly = 1) { guard.block(track, BlockSource.FAILED_DOWNLOADS) }
    }

    @Test fun `block is no-op when track not found`() = runTest {
        coEvery { trackDao.getById(99) } returns null
        vm.block(99)
        coVerify(exactly = 0) { guard.block(any(), any()) }
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :feature:sync:testDebugUnitTest --tests "*FailedDownloadsViewModelTest*"
```

- [ ] **Step 3: Implement the ViewModel**

```kotlin
package com.stash.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.blocklist.BlockSource
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.FailedDownloadRow
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.DownloadFailureType
import com.stash.data.download.single.SingleTrackDownloadEnqueuer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FailedDownloadsGroup(
    val type: DownloadFailureType,
    val rows: List<FailedDownloadRow>,
)

data class FailedDownloadsUiState(
    val isLoading: Boolean = true,
    val groups: List<FailedDownloadsGroup> = emptyList(),
) {
    val totalCount: Int get() = groups.sumOf { it.rows.size }
}

@HiltViewModel
class FailedDownloadsViewModel @Inject constructor(
    private val downloadQueueDao: DownloadQueueDao,
    private val trackDao: TrackDao,
    private val downloadEnqueuer: SingleTrackDownloadEnqueuer,
    private val blocklistGuard: BlocklistGuard,
) : ViewModel() {

    val uiState: StateFlow<FailedDownloadsUiState> =
        downloadQueueDao.getFailedDownloads()
            .map { rows ->
                FailedDownloadsUiState(
                    isLoading = false,
                    groups = FailureReasonDisplayOrder.mapNotNull { type ->
                        val groupRows = rows.filter { it.failureType == type }
                        if (groupRows.isEmpty()) null
                        else FailedDownloadsGroup(type, groupRows)
                    },
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FailedDownloadsUiState(),
            )

    fun retry(queueId: Long) = viewModelScope.launch {
        val claimed = downloadQueueDao.atomicallyClaimForRetry(queueId)
        if (claimed > 0) downloadEnqueuer.enqueue(queueId)
    }

    fun retryGroup(type: DownloadFailureType) = viewModelScope.launch {
        downloadQueueDao.atomicallyClaimGroupForRetry(type)
            .forEach { downloadEnqueuer.enqueue(it) }
    }

    fun retryAll() = viewModelScope.launch {
        downloadQueueDao.atomicallyClaimAllForRetry()
            .forEach { downloadEnqueuer.enqueue(it) }
    }

    fun block(trackId: Long) = viewModelScope.launch {
        val track = trackDao.getById(trackId) ?: return@launch
        blocklistGuard.block(track, BlockSource.FAILED_DOWNLOADS)
    }
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew :feature:sync:testDebugUnitTest --tests "*FailedDownloadsViewModelTest*"
```

- [ ] **Step 5: Commit**

```bash
git add feature/sync/src/main/kotlin/com/stash/feature/sync/FailedDownloadsViewModel.kt \
        feature/sync/src/test/kotlin/com/stash/feature/sync/FailedDownloadsViewModelTest.kt
git commit -m "feat(ui): FailedDownloadsViewModel with retry+block+group actions"
```

---

### Task 17: Create `FailedDownloadsGroupCard` composable

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/FailedDownloadsGroupCard.kt`

- [ ] **Step 1: Read the existing `FailedMatchesScreen` row composable**

Read `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesScreen.kt` lines ~510-650 (`UnmatchedTrackRow`) to match the visual rhythm of the existing screen.

- [ ] **Step 2: Write the composable**

Card structure:
- Header row (always visible): icon + group title + count + chevron + "Retry group" button.
- Body (visible when expanded): each `FailedDownloadRow` rendered as a track row with album art / title / artist / "playlist · short reason" subtitle / Retry + Block buttons.
- Tap header to toggle expand/collapse. Default: expanded.

Use existing project design tokens (`StashTheme.extendedColors.glassBackground`, `glassBorder`, etc.) — read `core/ui/.../theme/StashTheme.kt` for the available extended colors.

The composable signature:

```kotlin
@Composable
fun FailedDownloadsGroupCard(
    group: FailedDownloadsGroup,
    onRetryRow: (queueId: Long) -> Unit,
    onBlockRow: (trackId: Long) -> Unit,
    onRetryGroup: (DownloadFailureType) -> Unit,
)
```

Use `Icons.Default.ExpandLess` / `ExpandMore` for the chevron and remember a local expansion state.

- [ ] **Step 3: Build and commit**

```bash
./gradlew :feature:sync:compileDebugKotlin
git add feature/sync/src/main/kotlin/com/stash/feature/sync/components/FailedDownloadsGroupCard.kt
git commit -m "feat(ui): FailedDownloadsGroupCard collapsible group composable"
```

---

### Task 18: Create `FailedDownloadsScreen` composable

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedDownloadsScreen.kt`

- [ ] **Step 1: Write the screen**

Modelled after `FailedMatchesScreen.kt` (same file in the same package — read it first for matching style):

- Back button (statusBarsPadding + glass background) at top-left.
- Header column: title `Failed Downloads` (headlineMedium, bold) + subtitle + count line + `Retry all` button.
- LazyColumn of `FailedDownloadsGroupCard`s, one per group in `FailureReasonDisplayOrder`.
- Empty state: same "All caught up!" + check-circle icon pattern as the existing screen.

Wire actions:
- `onRetryRow = viewModel::retry`
- `onBlockRow = viewModel::block`
- `onRetryGroup = viewModel::retryGroup`
- `onRetryAll = viewModel::retryAll`

```kotlin
@Composable
fun FailedDownloadsScreen(
    onBack: () -> Unit,
    viewModel: FailedDownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // ... implementation per the structure above
}
```

- [ ] **Step 2: Build and commit**

```bash
./gradlew :feature:sync:compileDebugKotlin
git add feature/sync/src/main/kotlin/com/stash/feature/sync/FailedDownloadsScreen.kt
git commit -m "feat(ui): FailedDownloadsScreen composable"
```

---

### Task 19: Create `AuthExpiredBanner` composable

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/components/AuthExpiredBanner.kt`

- [ ] **Step 1: Write the composable**

```kotlin
package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.AuthExpiryState

@Composable
fun AuthExpiredBanner(
    state: AuthExpiryState,
    onReauthSpotify: () -> Unit,
    onReauthYoutube: () -> Unit,
    onReauthBoth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.anyExpired) return

    val (headline, body, action) = when {
        state.spotifyExpired && state.youtubeExpired -> Triple(
            "Sign-ins expired",
            "Both Spotify and YouTube need fresh sign-ins to resume sync.",
            "Re-authenticate" to onReauthBoth,
        )
        state.spotifyExpired -> Triple(
            "Spotify session expired",
            "Sync paused — re-authenticate to keep your downloads flowing.",
            "Re-authenticate Spotify" to onReauthSpotify,
        )
        else -> Triple(
            "YouTube session expired",
            "Sync paused — re-authenticate to keep your downloads flowing.",
            "Re-authenticate YouTube" to onReauthYoutube,
        )
    }

    val amber = Color(0xFFFFAA00)
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(amber.copy(alpha = 0.18f))
            .border(1.dp, amber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = action.second,
            colors = ButtonDefaults.buttonColors(containerColor = amber, contentColor = Color.Black),
            shape = RoundedCornerShape(8.dp),
        ) { Text(action.first, fontWeight = FontWeight.SemiBold) }
    }
}
```

- [ ] **Step 2: Build and commit**

```bash
./gradlew :feature:sync:compileDebugKotlin
git add feature/sync/src/main/kotlin/com/stash/feature/sync/components/AuthExpiredBanner.kt
git commit -m "feat(ui): AuthExpiredBanner with single-CTA copy"
```

---

### Task 20: Wire navigation + add card + mount banner in `SyncScreen`

**Files:**
- Modify: the project's navigation graph (likely `app/src/main/kotlin/.../navigation/StashNavGraph.kt` — locate by grepping for `FailedMatches` route)
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt` (add failed-count flow + authExpiry flow)

- [ ] **Step 1: Locate the existing FailedMatches navigation entry**

```bash
grep -rn "FailedMatchesScreen\|onNavigateToFailedMatches" --include="*.kt" .
```

Add a sibling `FailedDownloadsScreen` route following the exact same wiring pattern.

- [ ] **Step 2: Add failed-count + authExpiry flows to `SyncViewModel`**

In `SyncViewModel.kt`:

```kotlin
val failedDownloadsCount: StateFlow<Int> =
    downloadQueueDao.getFailedDownloads()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

val authExpiry: StateFlow<AuthExpiryState> =
    syncStateManager.authExpiry
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthExpiryState(false, false))
```

(`downloadQueueDao` is already a constructor dependency in the existing VM; if not, add it.)

- [ ] **Step 3: Mount banner + card in `SyncScreen`**

Above `SyncStatusCard`:

```kotlin
item {
    AuthExpiredBanner(
        state = authExpiry,
        onReauthSpotify = { /* TODO route to Spotify connector */ },
        onReauthYoutube = { /* TODO route to YouTube connector */ },
        onReauthBoth = { /* TODO route to Connections settings */ },
    )
}
```

Sibling card next to "Unmatched Songs" and "Blocked Songs":

```kotlin
item {
    LibraryHealthEntryCard(
        title = "Failed Downloads",
        count = failedCount,
        onClick = onNavigateToFailedDownloads,
    )
}
```

Wire the connector deep-links to whatever route the existing settings → reconnect path uses. Read the connector code to confirm — usually it's `nav.navigate("settings/connections")` or similar.

- [ ] **Step 4: Build and commit**

```bash
./gradlew :app:assembleDebug
git add -A
git commit -m "feat(ui): mount FailedDownloads card + AuthExpiredBanner in Sync tab"
```

---

### Task 21: Manual on-device smoke test

Per `feedback_install_after_fix.md` — compile pass is not enough.

- [ ] **Step 1: Install on real device**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Run the smoke matrix from spec §7.4**

- Failed Downloads card visible in Sync tab with correct count.
- Tap → screen renders, groups expand/collapse.
- Force an AUTH_EXPIRED (revoke Spotify token in Spotify's developer dashboard, OR clear cookies for YouTube) → banner appears at next sync, single CTA deep-links to Connections.
- Tap Retry on a row → spinner → row removed (or returns with refreshed error on real failure).
- Tap Block → row removed AND appears in Blocked Songs.
- Re-authenticate → banner disappears.

If any step fails, halt PR 1 and triage before tagging the release.

- [ ] **Step 3: PR 1 commit + push**

```bash
git push -u origin feat/library-health-phase1
gh pr create --title "Library Health Phase 1 (v0.9.38) — Failed Downloads + Auth Probe" \
  --body "Spec: docs/superpowers/specs/2026-05-26-library-health-phase1-design.md
Closes #88. Deflects #71, #29, #34, #27, #21, #50.

## Summary
- Failed Downloads viewer (Sync tab sibling), grouped by reason
- DownloadFailureClassifier — 6-bucket taxonomy + UNKNOWN catchall logging
- Atomic retry + Block actions per row, per group, all
- AuthHealthProbe at head of PlaylistFetchWorker (Spotify + YouTube)
- AuthExpiredBanner with single re-auth CTA
- DB migration v28 → v29

## Test plan
- [x] Unit: classifier, DAO, ViewModel
- [x] AndroidTest: migration_28_29
- [x] Manual on-device smoke matrix (spec §7.4)"
```

---

## PR 2 — Opus cover art (#95)

### Task 22: Build FLAC Picture metadata block (TDD)

**Files:**
- Create: `data/download/src/test/kotlin/com/stash/data/download/files/FlacPictureBlockTest.kt`
- Create: `data/download/src/main/kotlin/com/stash/data/download/files/FlacPictureBlock.kt`

A FLAC Picture metadata block has the following byte layout (big-endian):
```
[4 bytes  ] picture_type (= 3 for front cover)
[4 bytes  ] MIME length
[N bytes  ] MIME string ("image/jpeg")
[4 bytes  ] description length
[M bytes  ] description string (empty)
[4 bytes  ] width
[4 bytes  ] height
[4 bytes  ] color depth (24 for JPEG)
[4 bytes  ] number of colors (0 for non-indexed)
[4 bytes  ] picture data length
[K bytes  ] picture data (raw JPEG bytes)
```

- [ ] **Step 1: Write failing test**

```kotlin
package com.stash.data.download.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FlacPictureBlockTest {

    @Test fun `block contains MIME type and dimensions in correct positions`() {
        // 1x1 red JPEG (precomputed)
        val redJpeg = byteArrayOf(/* known good 1x1 JPEG bytes */)
        val block = FlacPictureBlock.build(redJpeg)
        val decoded = block.decodeBlock()  // helper that splits header into named fields
        assertArrayEquals(intArrayOf(3, 10), intArrayOf(decoded.pictureType, decoded.mimeLength))
        assertTrue(decoded.mime == "image/jpeg")
        assertTrue(decoded.width >= 1 && decoded.height >= 1)
        assertArrayEquals(redJpeg, decoded.pictureBytes)
    }
}
```

(The test fixture bytes can come from a real album-art file checked into `data/download/src/test/resources/` — pick the smallest valid JPEG you can find. The `decodeBlock` helper lives in the test file only, not in production.)

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*FlacPictureBlockTest*"
```

- [ ] **Step 3: Implement `FlacPictureBlock.build`**

```kotlin
package com.stash.data.download.files

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Builds a FLAC Picture metadata block from raw JPEG bytes for use as a
 * METADATA_BLOCK_PICTURE Vorbis comment value (base64-encoded). Spec:
 * https://xiph.org/flac/format.html#metadata_block_picture
 *
 * Only handles JPEG input; embedding non-JPEG art is out of scope.
 */
object FlacPictureBlock {

    /** Parse JPEG SOF marker to get dimensions. Returns (width, height). */
    private fun jpegDimensions(jpeg: ByteArray): Pair<Int, Int> {
        var i = 2  // skip SOI
        while (i < jpeg.size - 1) {
            if (jpeg[i].toInt() != 0xFF.toByte().toInt()) { i++; continue }
            val marker = jpeg[i + 1].toInt() and 0xFF
            // SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2) — baseline / extended / progressive
            if (marker in 0xC0..0xC2) {
                val height = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val width  = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return width to height
            }
            // Skip segment
            val segLen = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + segLen
        }
        return 0 to 0  // unknown — most readers tolerate this
    }

    fun build(jpeg: ByteArray): ByteArray {
        val (width, height) = jpegDimensions(jpeg)
        val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val description = ByteArray(0)
        val bos = ByteArrayOutputStream(jpeg.size + 64)
        DataOutputStream(bos).use { out ->
            out.writeInt(3)             // picture_type: front cover
            out.writeInt(mime.size)
            out.write(mime)
            out.writeInt(description.size)
            out.write(description)
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(24)            // colour depth
            out.writeInt(0)             // indexed colours
            out.writeInt(jpeg.size)
            out.write(jpeg)
        }
        return bos.toByteArray()
    }
}
```

- [ ] **Step 4: Run — expect PASS**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*FlacPictureBlockTest*"
```

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/files/FlacPictureBlock.kt \
        data/download/src/test/kotlin/com/stash/data/download/files/FlacPictureBlockTest.kt \
        data/download/src/test/resources/red-1x1.jpg
git commit -m "feat(download): add FlacPictureBlock builder for Vorbis art"
```

---

### Task 23: Rewrite `MetadataEmbedder` Opus branch

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/files/MetadataEmbedder.kt`

- [ ] **Step 1: Read the existing Opus branch**

Open `MetadataEmbedder.kt` and find the `OPUS_OGG_EXTENSIONS` branch in `buildFfmpegArgs` (or wherever the Opus-specific argv assembly lives — the issue body referenced lines ~48-95). Confirm where art is currently skipped.

- [ ] **Step 2: Modify the Opus path**

Inside the Opus branch, replace the "skip art" logic with:

```kotlin
if (artFile != null) {
    val pictureBlock = FlacPictureBlock.build(artFile.readBytes())
    val base64Block = android.util.Base64.encodeToString(pictureBlock, android.util.Base64.NO_WRAP)
    args.add("-metadata")
    args.add("METADATA_BLOCK_PICTURE=$base64Block")
}
```

Keep the tag arguments (`TITLE`, `ARTIST`, `ALBUMARTIST`, `ALBUM`, `ISRC`, `ENCODER`) exactly as they are.

Crucially: do NOT add the `-i artFile -map 1:0 -disposition:v:0 attached_pic` argv variant on the Opus path — that's what was causing the existing exit-code-234 failure.

- [ ] **Step 3: Compile**

```bash
./gradlew :data:download:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/files/MetadataEmbedder.kt
git commit -m "fix(metadata): embed Opus cover art via METADATA_BLOCK_PICTURE (#95)"
```

---

### Task 24: Flip integration test assertion

**Files:**
- Modify: `data/download/src/androidTest/kotlin/com/stash/data/download/files/MetadataEmbeddingIntegrationTest.kt` (path approximate — locate by grepping for `embedsTagsButNotArtIntoOpus`)

- [ ] **Step 1: Rename the test and flip the assertion**

```bash
grep -rn "embedsTagsButNotArtIntoOpus" --include="*.kt" .
```

Rename to `embedsTagsAndArtIntoOpus` and replace `assertNull(retriever.embeddedPicture)` with `assertNotNull(retriever.embeddedPicture)`.

- [ ] **Step 2: Run on-device integration test**

```bash
./gradlew :data:download:connectedDebugAndroidTest --tests "*MetadataEmbeddingIntegrationTest.embedsTagsAndArtIntoOpus*"
```

Expected: PASS — the Opus output file should now carry an embedded JPEG picture readable by `MediaMetadataRetriever`.

- [ ] **Step 3: Commit**

```bash
git add data/download/src/androidTest/kotlin/com/stash/data/download/files/MetadataEmbeddingIntegrationTest.kt
git commit -m "test(metadata): assert Opus output carries embedded art (#95)"
```

---

### Task 25: Manual Opus on-device verification

- [ ] **Step 1: Install and force a fresh Opus download**

```bash
./gradlew :app:installDebug
```

Pick a non-FLAC track from Spotify that's known to deliver Opus 251 from YouTube (any popular pop track works). Trigger a sync, let it download.

- [ ] **Step 2: Pull the file and verify cover art**

```bash
adb shell ls /storage/emulated/0/Music/Stash/  # locate the new Opus file
adb pull /storage/emulated/0/Music/Stash/<artist>/<title>.opus /tmp/
```

Open in Plex, Foobar2000, Symfonium, or a car-stereo emulator. Confirm the cover image is visible and matches the original Spotify album art.

- [ ] **Step 3: PR 2 push**

```bash
git push -u origin feat/library-health-phase1  # or a separate branch if you prefer
gh pr create --title "fix(metadata): Opus cover art via METADATA_BLOCK_PICTURE (#95)" \
  --body "Closes #95.

## Summary
- Adds FlacPictureBlock builder (FLAC Picture metadata block per Vorbis spec)
- Opus MetadataEmbedder now passes -metadata METADATA_BLOCK_PICTURE=<base64> instead of the unsupported attached_pic argv
- Integration test flipped: embeddedPicture must be non-null

## Test plan
- [x] Unit: FlacPictureBlockTest
- [x] Integration: embedsTagsAndArtIntoOpus
- [x] Manual: Opus track opens in Plex / Foobar / Symfonium with art"
```

---

## Release tagging (after both PRs merge)

- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts` per the existing project pattern (memory: prior bumps are visible in recent commits).
- [ ] Tag and push. Per `feedback_release_notes.md`: the release notes go in the tagged-commit message body, not the tag annotation.
- [ ] Verify the install upgrade path (existing v0.9.37 install → upgrade → migration runs cleanly).

---

## Notes for the executing subagent

1. **Always use `model: "opus"`** for every Agent dispatch in this project (memory: `feedback_subagent_model.md`).
2. **Install on device after every visible change** (memory: `feedback_install_after_fix.md`).
3. **"Ship" is a release tag, not a local install** (memory: `feedback_ship_terminology.md`) — do not auto-tag without explicit user instruction.
4. **Survey every `.worktrees/<branch>` before tagging** (memory: `feedback_check_worktrees_before_release.md`) for stray WIP fixes.
5. **Repo is `rawnaldclark/Stash`**, not `MP3APK` (memory: `project_stash_external_surfaces.md`).
6. **Preserve existing design** — for device-compat issues fix rendering, don't redesign (memory: `feedback_preserve_existing_design.md`).
