# Cross-Source Discography Merge — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Supplement the YouTube-Music-sourced artist discography with Qobuz albums (via qbdlx) so artists with YT catalog gaps (e.g. My Bloody Valentine) show their complete discography and play those albums lossless — with zero changes to `core:media`.

**Architecture:** A `DiscographySupplement` interface owned by `core:data` (impl in `data:download`, where the Qobuz client + matcher live) takes the YT albums/singles + artist name and returns the merged lists; `ArtistCache` caches the merged profile. A `QobuzAlbumFetcher` interface (same inversion) lets `AlbumCache` load a Qobuz album's tracklist. Qobuz album tracks build domain `Track`s that persist via the existing `ensureTrackPersisted` (canonical identity, real PK) and resolve lossless through the **existing** `QbdlxStreamResolver` search-match — no resolver, `TrackEntity`, or stash-resolve-URI change.

**Tech Stack:** Kotlin, Hilt, Coroutines, kotlinx.serialization, Room, JUnit4 + MockK/Robolectric (match existing module test styles), Media3 (untouched).

**Spec:** `docs/superpowers/specs/2026-07-07-cross-source-discography-merge-design.md` (Phase 1 section). This plan carries the spec's four **Plan-time must-address** items — see Tasks 6, 12.

**Refinement over the spec's literal component list (dependency-correctness):** the spec placed `DiscographyMerger` in `core:data`. Title-dedup reuses `QobuzCandidateMatcher.normalize`, which lives in `data:download`; `core:data → data:download` is the cycle we invert away. So the merge runs **inside the `data:download` supplement impl** (a pure `DiscographyMerger` object unit-tested there), and the `DiscographySupplement` interface returns already-merged lists. Same testable-pure-unit, correct module.

---

## File Structure

**`data:ytmusic`** (models shared by both `core:data` and `data:download`)
- Modify `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt` — add `AlbumSource` enum + `AlbumSummary.source` (defaulted).

**`data:download`** (Qobuz client, matcher, supplement impls)
- Modify `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClient.kt` — add `searchArtists`, `getArtistAlbums`, `getAlbum`.
- Create `.../qbdlx/QbdlxCatalogModels.kt` — DTOs for the new endpoints.
- Create `.../qbdlx/QobuzDiscographyProvider.kt` — `implements DiscographySupplement`; gate + artist-match + fetch + merge.
- Create `.../qbdlx/DiscographyMerger.kt` — pure merge object (internal to the impl).
- Create `.../qbdlx/QobuzAlbumFetcherImpl.kt` — `implements QobuzAlbumFetcher`; maps Qobuz album → `AlbumDetail`.
- Modify `.../qbdlx/di/QbdlxModule.kt` — `@Binds` both interfaces.

**`core:data`** (interfaces + caches)
- Create `core/data/src/main/kotlin/com/stash/core/data/discography/DiscographySupplement.kt` — interface + `MergedDiscography`.
- Create `.../discography/QobuzAlbumFetcher.kt` — interface.
- Create `.../discography/NoopDiscographySupplement.kt` — used ONLY by tests/previews; NOT Hilt-bound (see Task 4).
- Modify `.../cache/ArtistCache.kt` — inject supplement, `fetchAndMerge`, both call sites, `withTimeout`.
- Modify `.../cache/AlbumCache.kt` — inject fetcher, `get(id, source)` routing.

**`app` + `feature:search`** (nav + VM)
- Modify `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt` — `SearchAlbumRoute.source`.
- Modify `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt` — 3 `SearchAlbumRoute(...)` sites pass `source`.
- Modify `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt` — read `source` nav arg; branch `synthesizeDomainTracks` + guard `observeAlbum` side-effects.

---

## Task 1: Capture live Qobuz fixtures (enables TDD for Tasks 3, 5, 6, 7)

The new endpoints are not yet exercised, so there are no fixtures. Capture real JSON once with a live pool token so every downstream test parses real shapes (mirrors how the existing qbdlx tests were built).

**Files:**
- Create: `data/download/src/test/resources/qbdlx/artist_search_mbv.json`
- Create: `data/download/src/test/resources/qbdlx/artist_albums_mbv.json`
- Create: `data/download/src/test/resources/qbdlx/album_loveless.json`

- [ ] **Step 1: Extract a live token + app_id**

Run (Git Bash), decrypting the embedded pool the same way the app does at runtime is non-trivial offline; instead pull one token from the running debug app's logs or use the Settings token picker. Simplest: read `QBDLX_APP_ID` from `data/download/build.gradle.kts` BuildConfig and grab a `token` from a device logcat line `QbdlxCredentialStore` during a stream. Record `APP_ID` and `TOKEN` as shell vars.

- [ ] **Step 2: Hit the three endpoints and save bodies**

```bash
APP_ID=... ; TOKEN=...
BASE=https://www.qobuz.com/api.json/0.2
H=(-H "X-App-Id: $APP_ID" -H "X-User-Auth-Token: $TOKEN" -H "Accept: application/json")
curl -s "${H[@]}" "$BASE/catalog/search?query=my+bloody+valentine&type=artists&limit=10&app_id=$APP_ID" \
  > data/download/src/test/resources/qbdlx/artist_search_mbv.json
# Read the matched artist_id from the search result, then:
ARTIST_ID=...   # from artist_search_mbv.json → artists.items[0].id
curl -s "${H[@]}" "$BASE/artist/get?artist_id=$ARTIST_ID&extra=albums&limit=100&offset=0&app_id=$APP_ID" \
  > data/download/src/test/resources/qbdlx/artist_albums_mbv.json
ALBUM_ID=...    # Loveless id from artist_albums_mbv.json → albums.items[].id
curl -s "${H[@]}" "$BASE/album/get?album_id=$ALBUM_ID&app_id=$APP_ID" \
  > data/download/src/test/resources/qbdlx/album_loveless.json
```

- [ ] **Step 3: Sanity-check the fixtures**

Run: `python -c "import json,sys; [json.load(open('data/download/src/test/resources/qbdlx/'+f)) for f in ['artist_search_mbv.json','artist_albums_mbv.json','album_loveless.json']]; print('ok')"`
Expected: `ok`. Confirm `artist_albums_mbv.json` actually contains *Loveless* and *Isn't Anything* (the whole point). If the token 401s, get a fresh one and retry — do not proceed with empty fixtures.

- [ ] **Step 4: Commit**

```bash
git add data/download/src/test/resources/qbdlx/
git commit -m "test(qbdlx): capture live artist-search/artist-albums/album fixtures"
```

---

## Task 2: `AlbumSource` enum + `AlbumSummary.source`

**Files:**
- Modify: `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/SearchAllResults.kt`
- Test: `data/ytmusic/src/test/kotlin/com/stash/data/ytmusic/model/AlbumSummarySerializationTest.kt`

- [ ] **Step 1: Write the failing back-compat test**

```kotlin
package com.stash.data.ytmusic.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumSummarySerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `old cached json without source decodes to YOUTUBE`() {
        val legacy = """{"id":"MPRE1","title":"Loveless","artist":"MBV","thumbnailUrl":null,"year":"1991"}"""
        val decoded = json.decodeFromString<AlbumSummary>(legacy)
        assertEquals(AlbumSource.YOUTUBE, decoded.source)
    }

    @Test fun `qobuz source round-trips`() {
        val a = AlbumSummary("123", "Loveless", "MBV", null, "1991", AlbumSource.QOBUZ)
        assertEquals(a, json.decodeFromString(json.encodeToString(a)))
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`AlbumSource` unresolved).
Run: `./gradlew :data:ytmusic:testDebugUnitTest --tests "*AlbumSummarySerializationTest"`

- [ ] **Step 3: Add the enum + field**

In `SearchAllResults.kt`, above `AlbumSummary`:
```kotlin
/** Which catalog an album entry came from. Drives AlbumCache routing + play path. */
@Serializable
enum class AlbumSource { YOUTUBE, QOBUZ }
```
Add the field (defaulted last, so existing cached JSON decodes):
```kotlin
@Serializable
data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
    val source: AlbumSource = AlbumSource.YOUTUBE,
)
```

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2.

- [ ] **Step 5: Commit**
```bash
git add data/ytmusic/src/main/kotlin/.../SearchAllResults.kt data/ytmusic/src/test/kotlin/.../AlbumSummarySerializationTest.kt
git commit -m "feat(model): AlbumSummary.source (AlbumSource enum), defaulted for cache back-compat"
```

---

## Task 3: `QbdlxApiClient` — artist search, artist albums, album detail

Add three metadata calls. Auth reuses the existing app_id + `X-User-Auth-Token` header (the `get()` private helper). These endpoints do **not** need the MD5 `request_sig` that `getFileUrl` needs.

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxApiClient.kt`
- Create: `.../qbdlx/QbdlxCatalogModels.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxCatalogParseTest.kt`

- [ ] **Step 1: DTOs from the captured fixtures**

Open the three fixtures; model only the fields used. Typical Qobuz shapes (verify against the actual fixtures and adjust names/nullability):
```kotlin
package com.stash.data.download.lossless.qbdlx
import kotlinx.serialization.Serializable

@Serializable data class QbdlxArtistSearchResponse(val artists: QbdlxArtistList = QbdlxArtistList())
@Serializable data class QbdlxArtistList(val items: List<QbdlxArtistItem> = emptyList())
@Serializable data class QbdlxArtistItem(val id: Long = 0, val name: String = "")

@Serializable data class QbdlxArtistAlbumsResponse(val albums: QbdlxAlbumList = QbdlxAlbumList())
@Serializable data class QbdlxAlbumList(val items: List<QbdlxAlbumItem> = emptyList())
@Serializable data class QbdlxAlbumItem(
    val id: String = "",
    val title: String = "",
    val artist: QbdlxPerformer? = null,     // reuse existing QbdlxPerformer if shape matches
    val image: QbdlxImage? = null,          // reuse existing QbdlxImage
    val released_at: Long? = null,          // epoch seconds; may instead be `release_date_original` "YYYY-MM-DD"
    val release_date_original: String? = null,
    val tracks_count: Int = 0,
    val release_type: String? = null,       // "album" | "single" | "ep" | "compilation" ...
)

@Serializable data class QbdlxAlbumDetailResponse(
    val id: String = "",
    val title: String = "",
    val artist: QbdlxPerformer? = null,
    val image: QbdlxImage? = null,
    val release_date_original: String? = null,
    val tracks: QbdlxAlbumTrackList = QbdlxAlbumTrackList(),
)
@Serializable data class QbdlxAlbumTrackList(val items: List<QbdlxAlbumTrackItem> = emptyList())
@Serializable data class QbdlxAlbumTrackItem(
    val id: Long = 0,
    val title: String = "",
    val performer: QbdlxPerformer? = null,
    val duration: Int = 0,                  // seconds
)
```
Note: `QbdlxPerformer`/`QbdlxImage` already exist in `QbdlxQobuzModels.kt` — reuse them if their fields line up; otherwise add minimal variants here.

- [ ] **Step 2: Write the failing parse test** (offline, no network — mirrors existing qbdlx tests reading `test/resources`)

```kotlin
class QbdlxCatalogParseTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private fun fixture(n: String) = javaClass.classLoader!!.getResourceAsStream("qbdlx/$n")!!.reader().readText()

    @Test fun `artist search parses items`() {
        val r = json.decodeFromString<QbdlxArtistSearchResponse>(fixture("artist_search_mbv.json"))
        assertTrue(r.artists.items.any { it.name.contains("bloody", true) })
    }
    @Test fun `artist albums include Loveless and Isnt Anything`() {
        val r = json.decodeFromString<QbdlxArtistAlbumsResponse>(fixture("artist_albums_mbv.json"))
        val titles = r.albums.items.map { it.title.lowercase() }
        assertTrue(titles.any { it.contains("loveless") })
        assertTrue(titles.any { it.contains("isn't anything") || it.contains("isnt anything") })
    }
    @Test fun `album detail parses tracks with ids and durations`() {
        val r = json.decodeFromString<QbdlxAlbumDetailResponse>(fixture("album_loveless.json"))
        assertTrue(r.tracks.items.isNotEmpty())
        assertTrue(r.tracks.items.all { it.id > 0 && it.duration > 0 })
    }
}
```

- [ ] **Step 3: Run — expect FAIL** (DTOs/response types missing or field mismatch). Fix DTO field names against the real fixtures until the shapes match.
Run: `./gradlew :data:download:testDebugUnitTest --tests "*QbdlxCatalogParseTest"`

- [ ] **Step 4: Add the client methods**

In `QbdlxApiClient.kt`, alongside `search`:
```kotlin
/** Resolve an artist by name → catalog artist candidates. */
suspend fun searchArtists(query: String, token: String, limit: Int = 10): List<QbdlxArtistItem> =
    withContext(Dispatchers.IO) {
        val url = "$baseUrl/api.json/0.2/catalog/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("type", "artists")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("app_id", appId)
            .build()
        runCatching { json.decodeFromString<QbdlxArtistSearchResponse>(get(url.toString(), token)).artists.items }
            .getOrDefault(emptyList())
    }

/** Full album list for a Qobuz artist id. */
suspend fun getArtistAlbums(artistId: Long, token: String, limit: Int = 100): List<QbdlxAlbumItem> =
    withContext(Dispatchers.IO) {
        val url = "$baseUrl/api.json/0.2/artist/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_id", artistId.toString())
            .addQueryParameter("extra", "albums")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", "0")
            .addQueryParameter("app_id", appId)
            .build()
        runCatching { json.decodeFromString<QbdlxArtistAlbumsResponse>(get(url.toString(), token)).albums.items }
            .getOrDefault(emptyList())
    }

/** Album detail (tracklist with track ids) for a Qobuz album id. */
suspend fun getAlbum(albumId: String, token: String): QbdlxAlbumDetailResponse =
    withContext(Dispatchers.IO) {
        val url = "$baseUrl/api.json/0.2/album/get".toHttpUrl().newBuilder()
            .addQueryParameter("album_id", albumId)
            .addQueryParameter("app_id", appId)
            .build()
        json.decodeFromString(get(url.toString(), token))
    }
```
`get()` already throws `QbdlxAuthException` on 401 / `QbdlxApiException` otherwise; callers handle those.

- [ ] **Step 5: Run — expect PASS.** Same command as Step 3.

- [ ] **Step 6: Commit**
```bash
git add data/download/src/main/kotlin/.../qbdlx/QbdlxApiClient.kt data/download/src/main/kotlin/.../qbdlx/QbdlxCatalogModels.kt data/download/src/test/kotlin/.../QbdlxCatalogParseTest.kt
git commit -m "feat(qbdlx): artist-search + artist-albums + album-detail endpoints"
```

---

## Task 4: `DiscographySupplement` + `QobuzAlbumFetcher` interfaces (core:data)

Pure interface task — no logic, no test yet.

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/discography/DiscographySupplement.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/discography/QobuzAlbumFetcher.kt`

- [ ] **Step 1: Write the interfaces**

```kotlin
package com.stash.core.data.discography
import com.stash.data.ytmusic.model.AlbumSummary

/** Result of merging Qobuz albums into a YT discography. */
data class MergedDiscography(val albums: List<AlbumSummary>, val singles: List<AlbumSummary>)

/**
 * Supplements a YT-sourced discography with Qobuz albums. The impl (in
 * data:download, where the Qobuz client + matcher live) does the artist match,
 * fetch, and merge; core:data only calls this and caches the result.
 *
 * Best-effort: any failure/timeout/no-confident-match returns the YT lists
 * UNCHANGED. Never throws to the caller.
 */
interface DiscographySupplement {
    suspend fun mergeInto(
        artistName: String,
        ytAlbums: List<AlbumSummary>,
        ytSingles: List<AlbumSummary>,
    ): MergedDiscography
}
```
```kotlin
package com.stash.core.data.discography
import com.stash.data.ytmusic.model.AlbumDetail

/** Loads a Qobuz album's tracklist. Impl in data:download. Throws on failure
 *  (AlbumCache surfaces it exactly like a YT album load failure). */
interface QobuzAlbumFetcher {
    suspend fun getAlbum(qobuzAlbumId: String): AlbumDetail
}
```

- [ ] **Step 2: Compile.** Run: `./gradlew :core:data:compileDebugKotlin` — expect success.

- [ ] **Step 3: Commit**
```bash
git add core/data/src/main/kotlin/com/stash/core/data/discography/
git commit -m "feat(discography): DiscographySupplement + QobuzAlbumFetcher interfaces"
```

---

## Task 5: `DiscographyMerger` — pure merge (within-bucket dedup, Qobuz-preferred, min-year order)

Carries spec **must-address** merge rules. Pure object in `data:download` (uses `QobuzCandidateMatcher.normalize`).

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/DiscographyMerger.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/DiscographyMergerTest.kt`

- [ ] **Step 1: Write failing table tests**

```kotlin
class DiscographyMergerTest {
    private fun yt(t: String, y: String?) = AlbumSummary("yt_$t", t, "MBV", null, y, AlbumSource.YOUTUBE)
    private fun qb(t: String, y: String?) = AlbumSummary("qb_$t", t, "MBV", null, y, AlbumSource.QOBUZ)

    @Test fun `qobuz wins a title collision when year within 1`() {
        val out = DiscographyMerger.mergeLane(listOf(yt("Loveless","1991")), listOf(qb("Loveless","1991")))
        assertEquals(1, out.size); assertEquals(AlbumSource.QOBUZ, out[0].source)
    }
    @Test fun `yt-only album survives`() {
        val out = DiscographyMerger.mergeLane(listOf(yt("Live at X","2010")), listOf(qb("Loveless","1991")))
        assertEquals(setOf("Live at X","Loveless"), out.map { it.title }.toSet())
    }
    @Test fun `collision kept as two when secondary signal disagrees`() {
        // same title, years 30y apart → NOT the same release, keep both
        val out = DiscographyMerger.mergeLane(listOf(yt("Greatest Hits","1990")), listOf(qb("Greatest Hits","2020")))
        assertEquals(2, out.size)
    }
    @Test fun `ordering is newest-first by earliest known year`() {
        // Qobuz dates Loveless by remaster 2021; YT has 1991 → sort by min(1991)
        val out = DiscographyMerger.mergeLane(
            listOf(yt("Loveless","1991"), yt("m b v","2013")),
            listOf(qb("Loveless","2021")),
        )
        assertEquals(listOf("m b v","Loveless"), out.map { it.title }) // 2013 before 1991
    }
    @Test fun `null years sort last`() {
        val out = DiscographyMerger.mergeLane(listOf(yt("Unknown",null), yt("m b v","2013")), emptyList())
        assertEquals(listOf("m b v","Unknown"), out.map { it.title })
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**
Run: `./gradlew :data:download:testDebugUnitTest --tests "*DiscographyMergerTest"`

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.lossless.qbdlx
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.model.AlbumSummary

/** Pure discography merge: union YT + Qobuz within one lane (album or single),
 *  Qobuz-preferred on a corroborated title collision, newest-first by earliest
 *  known year. NOTE: callers must merge each lane separately (album↔album,
 *  single↔single) — a single must never evict an album of the same name. */
object DiscographyMerger {

    fun mergeLane(yt: List<AlbumSummary>, qobuz: List<AlbumSummary>): List<AlbumSummary> {
        val result = ArrayList<AlbumSummary>(yt.size + qobuz.size)
        val ytByKey = yt.groupBy { key(it.title) }
        val consumedYt = HashSet<String>()

        for (q in qobuz) {
            val k = key(q.title)
            val ytMatch = ytByKey[k]?.firstOrNull { it.id !in consumedYt }
            if (ytMatch != null && sameRelease(ytMatch, q)) {
                result += q.copy(year = earliestYear(ytMatch.year, q.year)) // Qobuz wins, YT's original year for order
                consumedYt += ytMatch.id
            } else {
                result += q // Qobuz-only, or collision that isn't the same release → keep both
            }
        }
        for (y in yt) if (y.id !in consumedYt) result += y
        return result.sortedWith(compareByDescending { yearInt(it.year) }) // nulls (Int.MIN) last
    }

    private fun key(title: String) = QobuzCandidateMatcher.normalize(title)

    /** Corroborate a same-title collision so a 1-track single or a differently-
     *  dated compilation doesn't false-merge: require years within 1 (when both
     *  known). Track-count check is not available at summary level. */
    private fun sameRelease(a: AlbumSummary, b: AlbumSummary): Boolean {
        val ya = yearInt(a.year); val yb = yearInt(b.year)
        if (ya == Int.MIN_VALUE || yb == Int.MIN_VALUE) return true // one unknown → trust the title
        return kotlin.math.abs(ya - yb) <= 1
    }

    private fun earliestYear(a: String?, b: String?): String? {
        val ya = yearInt(a); val yb = yearInt(b)
        val min = minOf(ya, yb)
        return if (min == Int.MIN_VALUE) (a ?: b) else min.toString()
    }

    /** Parse a 4-digit year out of "1991" or "1991-11-04"; Int.MIN_VALUE when unknown. */
    private fun yearInt(s: String?): Int {
        if (s.isNullOrBlank()) return Int.MIN_VALUE
        return Regex("\\d{4}").find(s)?.value?.toIntOrNull() ?: Int.MIN_VALUE
    }
}
```

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2.

- [ ] **Step 5: Commit**
```bash
git add data/download/src/main/kotlin/.../DiscographyMerger.kt data/download/src/test/kotlin/.../DiscographyMergerTest.kt
git commit -m "feat(discography): pure DiscographyMerger (within-lane dedup, Qobuz-preferred, min-year order)"
```

---

## Task 6: `QobuzDiscographyProvider` — gate + artist match + fetch + merge

Carries spec **must-address #1** (artist-match threshold, candidate dedup, Various-Artists blocklist, zero-YT-album ambiguity abort).

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QobuzDiscographyProvider.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/qbdlx/QobuzDiscographyProviderTest.kt`

- [ ] **Step 1: Write failing tests** (fake the API client + credential store + prefs; assert gate/match behavior)

Cover: (a) disabled/no-token → returns YT lists unchanged; (b) confident single-candidate match with ≥1 YT title overlap → Qobuz albums merged in; (c) **zero YT albums + two name-similar candidates → aborts** (YT unchanged); (d) "Various Artists" candidate → skipped; (e) a superstring pseudo-artist candidate ("MBV Tribute") is excluded from the candidate set so it doesn't trip the ambiguity abort for the real MBV.

```kotlin
class QobuzDiscographyProviderTest {
    // fakes: QbdlxApiClient (searchArtists/getArtistAlbums), QbdlxCredentialStore
    // (activeToken), QbdlxQobuzSource.isEnabledForStreaming(). Construct provider
    // with them. Use small AlbumSummary/QbdlxArtistItem builders.

    @Test fun `disabled source returns yt lists unchanged`() = runTest {
        val out = provider(enabled = false).mergeInto("MBV", listOf(ytLoveless), emptyList())
        assertEquals(listOf(ytLoveless), out.albums); assertTrue(out.singles.isEmpty())
    }
    @Test fun `confident match with title overlap merges qobuz albums`() = runTest {
        // YT has "you made me realise"; Qobuz artist has Loveless + that EP.
        val out = provider(candidates = listOf(mbv), albums = qbAlbums).mergeInto(
            "My Bloody Valentine", listOf(ytEp), emptyList())
        assertTrue(out.albums.any { it.title.equals("Loveless", true) && it.source == AlbumSource.QOBUZ })
    }
    @Test fun `zero yt albums plus ambiguous candidates aborts`() = runTest {
        val out = provider(candidates = listOf(nirvanaGrunge, nirvanaUk)).mergeInto("Nirvana", emptyList(), emptyList())
        assertTrue(out.albums.isEmpty()) // supplement skipped, nothing grafted
    }
    @Test fun `various artists candidate is ignored`() = runTest {
        val out = provider(candidates = listOf(variousArtists)).mergeInto("Various Artists", emptyList(), emptyList())
        assertTrue(out.albums.isEmpty())
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**
Run: `./gradlew :data:download:testDebugUnitTest --tests "*QobuzDiscographyProviderTest"`

- [ ] **Step 3: Implement**

```kotlin
@Singleton
class QobuzDiscographyProvider @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
    private val source: QbdlxQobuzSource,
) : DiscographySupplement {

    override suspend fun mergeInto(
        artistName: String,
        ytAlbums: List<AlbumSummary>,
        ytSingles: List<AlbumSummary>,
    ): MergedDiscography = runCatching {
        if (!source.isEnabledForStreaming()) return unchanged(ytAlbums, ytSingles)
        val token = credentialStore.activeToken() ?: return unchanged(ytAlbums, ytSingles)

        val nName = QobuzCandidateMatcher.normalize(artistName)
        if (nName.isBlank() || nName in VARIOUS_ARTISTS) return unchanged(ytAlbums, ytSingles)

        // Candidate set = DISTINCT real artists similar to the query, excluding
        // Various-Artists and superstring pseudo-entities (tributes/compilations
        // whose name strictly CONTAINS the query with extra tokens).
        val candidates = apiClient.searchArtists(artistName, token)
            .filter { QobuzCandidateMatcher.normalize(it.name) !in VARIOUS_ARTISTS }
            .filterNot { isSuperstringPseudo(nName, QobuzCandidateMatcher.normalize(it.name)) }
            .distinctBy { it.id }
        val scored = candidates
            .map { it to QobuzCandidateMatcher.artistSimilarity(nName, QobuzCandidateMatcher.normalize(it.name)) }
            .filter { it.second >= ARTIST_MATCH_THRESHOLD }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull()?.first ?: return unchanged(ytAlbums, ytSingles)

        // Corroboration:
        //  - YT has albums  → require ≥1 normalized-title overlap.
        //  - YT has none    → require UNAMBIGUOUS name (no 2nd candidate at threshold).
        val qAlbumsRaw = apiClient.getArtistAlbums(best.id, token)
        if (ytAlbums.isNotEmpty()) {
            val ytKeys = ytAlbums.map { QobuzCandidateMatcher.normalize(it.title) }.toSet()
            val overlap = qAlbumsRaw.any { QobuzCandidateMatcher.normalize(it.title) in ytKeys }
            if (!overlap) return unchanged(ytAlbums, ytSingles)
        } else {
            if (scored.size >= 2) return unchanged(ytAlbums, ytSingles) // ambiguous name → skip
        }

        val (qAlbums, qSingles) = qAlbumsRaw
            .map { it.toAlbumSummary(best.name) }
            .partition { it.releaseIsAlbum } // bucket by Qobuz release type
        MergedDiscography(
            albums = DiscographyMerger.mergeLane(ytAlbums, qAlbums),
            singles = DiscographyMerger.mergeLane(ytSingles, qSingles),
        )
    }.getOrElse { unchanged(ytAlbums, ytSingles) }

    private fun unchanged(a: List<AlbumSummary>, s: List<AlbumSummary>) = MergedDiscography(a, s)

    /** A candidate whose token set strictly contains the query's plus extra
     *  tokens (e.g. "my bloody valentine tribute") — exclude from candidacy. */
    private fun isSuperstringPseudo(query: String, cand: String): Boolean {
        val q = query.split(" ").toSet(); val c = cand.split(" ").toSet()
        return c != q && c.containsAll(q) && (c - q).isNotEmpty()
    }

    private companion object {
        const val ARTIST_MATCH_THRESHOLD = 0.6f
        val VARIOUS_ARTISTS = setOf(
            "various artists", "various", "verschiedene interpreten", "multi artistes", "va",
        )
    }
}
```
Add mapping helpers (in this file or `QbdlxCatalogModels.kt`):
```kotlin
private val QbdlxAlbumItem.releaseIsAlbum: Boolean
    get() = when (release_type?.lowercase()) {
        "single", "ep" -> false
        else -> true // album, compilation, live, null → album lane
    }
private fun QbdlxAlbumItem.toAlbumSummary(artistName: String) = AlbumSummary(
    id = id,
    title = title,
    artist = artist?.name?.ifBlank { artistName } ?: artistName,
    thumbnailUrl = image?.large ?: image?.small ?: image?.thumbnail,
    year = release_date_original ?: released_at?.let { epochToYear(it) },
    source = AlbumSource.QOBUZ,
)
```

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2.

- [ ] **Step 5: Commit**
```bash
git add data/download/src/main/kotlin/.../QobuzDiscographyProvider.kt data/download/src/test/kotlin/.../QobuzDiscographyProviderTest.kt
git commit -m "feat(discography): QobuzDiscographyProvider — gated artist match + merge (threshold, dedup, VA blocklist, ambiguity abort)"
```

---

## Task 7: `QobuzAlbumFetcherImpl` — Qobuz album → `AlbumDetail`

Maps a Qobuz album to the existing `AlbumDetail` type. **Qobuz tracks carry `videoId = ""`** (the no-YouTube sentinel; Phase 1 keeps `AlbumDetail`/`TrackSummary` types unchanged).

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QobuzAlbumFetcherImpl.kt`
- Test: `.../QobuzAlbumFetcherImplTest.kt`

- [ ] **Step 1: Failing test** (fake client returns the `album_loveless.json` parse; assert AlbumDetail shape)

```kotlin
@Test fun `maps qobuz album to AlbumDetail with blank videoIds and real durations`() = runTest {
    val detail = fetcher(albumJson = "album_loveless.json").getAlbum("123")
    assertEquals("Loveless", detail.title)
    assertTrue(detail.tracks.isNotEmpty())
    assertTrue(detail.tracks.all { it.videoId == "" })          // no YT id
    assertTrue(detail.tracks.all { it.durationSeconds > 0 })
    assertTrue(detail.moreByArtist.isEmpty())                    // not populated in Phase 1
}
@Test fun `throws when no live token`() = runTest {
    assertFailsWith<IllegalStateException> { fetcher(token = null).getAlbum("123") }
}
```

- [ ] **Step 2: Run — expect FAIL.**
Run: `./gradlew :data:download:testDebugUnitTest --tests "*QobuzAlbumFetcherImplTest"`

- [ ] **Step 3: Implement**

```kotlin
@Singleton
class QobuzAlbumFetcherImpl @Inject constructor(
    private val apiClient: QbdlxApiClient,
    private val credentialStore: QbdlxCredentialStore,
) : QobuzAlbumFetcher {
    override suspend fun getAlbum(qobuzAlbumId: String): AlbumDetail {
        val token = credentialStore.activeToken() ?: error("qbdlx: no live token")
        val r = apiClient.getAlbum(qobuzAlbumId, token)
        val artistName = r.artist?.name.orEmpty()
        return AlbumDetail(
            id = r.id,
            title = r.title,
            artist = artistName,
            artistId = null,
            thumbnailUrl = r.image?.large ?: r.image?.small ?: r.image?.thumbnail,
            year = r.release_date_original,
            tracks = r.tracks.items.map { t ->
                TrackSummary(
                    videoId = "",                       // no YouTube id — resolved by metadata via qbdlx
                    title = t.title,
                    artist = t.performer?.name?.ifBlank { artistName } ?: artistName,
                    album = r.title,
                    durationSeconds = t.duration.toDouble(),
                    thumbnailUrl = r.image?.large ?: r.image?.thumbnail,
                )
            },
            moreByArtist = emptyList(),
        )
    }
}
```

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2.

- [ ] **Step 5: Commit**
```bash
git add data/download/src/main/kotlin/.../QobuzAlbumFetcherImpl.kt data/download/src/test/kotlin/.../QobuzAlbumFetcherImplTest.kt
git commit -m "feat(discography): QobuzAlbumFetcherImpl — Qobuz album -> AlbumDetail (blank videoId sentinel)"
```

---

## Task 8: Hilt `@Binds` for both interfaces

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/di/QbdlxModule.kt`

- [ ] **Step 1: Add the bindings** (in the `abstract class QbdlxModule` body)

```kotlin
@Binds
abstract fun bindDiscographySupplement(impl: QobuzDiscographyProvider): DiscographySupplement

@Binds
abstract fun bindQobuzAlbumFetcher(impl: QobuzAlbumFetcherImpl): QobuzAlbumFetcher
```
Add imports for the two `core:data` interfaces + the two impls.

- [ ] **Step 2: Compile the app graph** — surfaces any duplicate/missing-binding error.
Run: `./gradlew :app:compileDebugKotlin` — expect success.

- [ ] **Step 3: Commit**
```bash
git add data/download/src/main/kotlin/.../di/QbdlxModule.kt
git commit -m "feat(discography): Hilt-bind DiscographySupplement + QobuzAlbumFetcher"
```

---

## Task 9: `ArtistCache` — inject supplement, merge at BOTH fetch sites, `withTimeout`

`ArtistCache` calls `api.getArtist()` in two places: cold miss (~L150) and stale refresh (~L134). Route both through one `fetchAndMerge` so the 6h refresh doesn't overwrite the merged profile with YT-only.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/cache/ArtistCache.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/cache/ArtistCacheMergeTest.kt` (or extend the existing ArtistCache test)

- [ ] **Step 1: Failing tests** (fake `YTMusicApiClient` + fake `DiscographySupplement`; fixed clock)

```kotlin
@Test fun `cold miss merges supplement into cached profile`() = runTest {
    val cache = cache(supplement = fakeMerging(addsAlbumTitled = "Loveless"))
    val out = cache.get("mbv").first().profile
    assertTrue(out.albums.any { it.title == "Loveless" && it.source == AlbumSource.QOBUZ })
}
@Test fun `stale refresh keeps the supplement (not overwritten with YT-only)`() = runTest {
    // seed a stale entry, then get() → Stale then Fresh; assert the Fresh STILL has Qobuz albums
    val fresh = cache(...).get("mbv").toList().last().profile
    assertTrue(fresh.albums.any { it.source == AlbumSource.QOBUZ })
}
@Test fun `supplement timeout falls back to yt-only, no throw`() = runTest {
    val out = cache(supplement = neverReturns()).get("mbv").first().profile
    assertTrue(out.albums.all { it.source == AlbumSource.YOUTUBE })
}
```

- [ ] **Step 2: Run — expect FAIL.**
Run: `./gradlew :core:data:testDebugUnitTest --tests "*ArtistCacheMergeTest"`

- [ ] **Step 3: Implement**

Add `private val supplement: DiscographySupplement` to BOTH constructors (primary + `@Inject`). Extract:
```kotlin
private suspend fun fetchAndMerge(artistId: String): ArtistProfile {
    val yt = api.getArtist(artistId)
    val merged = try {
        withTimeout(SUPPLEMENT_TIMEOUT_MS) { supplement.mergeInto(yt.name, yt.albums, yt.singles) }
    } catch (t: Throwable) {
        if (t is CancellationException && currentCoroutineContext().isActive.not()) throw t
        MergedDiscography(yt.albums, yt.singles) // timeout/failure → YT-only
    }
    return yt.copy(albums = merged.albums, singles = merged.singles)
}
private companion object { const val SUPPLEMENT_TIMEOUT_MS = 4_000L }
```
Note: `withTimeout` throws `TimeoutCancellationException` (a `CancellationException` subclass) — the `catch` above must swallow the timeout but still honor real structured-cancellation. Simplest robust form: catch `TimeoutCancellationException` explicitly for the fallback, and rethrow other `CancellationException`. Replace the two `api.getArtist(artistId)` + `persist(...)` sequences (miss L150-152 and refresh L134-136) with `fetchAndMerge(artistId)`.

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2. Also run the existing ArtistCache test to confirm no regression: `./gradlew :core:data:testDebugUnitTest --tests "*ArtistCache*"`

- [ ] **Step 5: Commit**
```bash
git add core/data/src/main/kotlin/.../cache/ArtistCache.kt core/data/src/test/kotlin/.../ArtistCacheMergeTest.kt
git commit -m "feat(discography): ArtistCache merges the Qobuz supplement at both fetch sites (timeout-guarded)"
```

---

## Task 10: `AlbumCache` — route `get(id, source)` by source

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/cache/AlbumCache.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/cache/AlbumCacheRoutingTest.kt`

- [ ] **Step 1: Failing tests** (fake `YTMusicApiClient` + fake `QobuzAlbumFetcher`)

```kotlin
@Test fun `qobuz source routes to the fetcher`() = runTest {
    val cache = cache(fetcher = fakeFetcher(returns = lovelessDetail))
    assertEquals("Loveless", cache.get("123", AlbumSource.QOBUZ).title)
    assertEquals(0, ytApiCalls) // YT api NOT called
}
@Test fun `youtube source keeps existing path`() = runTest {
    cache(...).get("MPRE1", AlbumSource.YOUTUBE); assertEquals(1, ytApiCalls)
}
```

- [ ] **Step 2: Run — expect FAIL.**
Run: `./gradlew :core:data:testDebugUnitTest --tests "*AlbumCacheRoutingTest"`

- [ ] **Step 3: Implement**

Inject `private val qobuzFetcher: QobuzAlbumFetcher`. Change the signature to `suspend fun get(id: String, source: AlbumSource = AlbumSource.YOUTUBE)`. Key the cache map by `"$source:$id"` (so a numeric Qobuz id can never collide with a YT browseId). In the fetch body, branch: `if (source == AlbumSource.QOBUZ) qobuzFetcher.getAlbum(id) else api.getAlbum(id)`.

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2. Existing AlbumCache tests: `./gradlew :core:data:testDebugUnitTest --tests "*AlbumCache*"` (the default `source` arg keeps old call sites compiling until Task 11 updates them).

- [ ] **Step 5: Commit**
```bash
git add core/data/src/main/kotlin/.../cache/AlbumCache.kt core/data/src/test/kotlin/.../AlbumCacheRoutingTest.kt
git commit -m "feat(discography): AlbumCache routes get(id, source) to the Qobuz fetcher"
```

---

## Task 11: Nav plumbing — thread `AlbumSource` to the tap surface

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt` (`SearchAlbumRoute`)
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt` (3 `SearchAlbumRoute(...)` sites — L97, L299, L319)

- [ ] **Step 1: Add the nav arg**

```kotlin
@Serializable
data class SearchAlbumRoute(
    val browseId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
    val source: AlbumSource = AlbumSource.YOUTUBE, // default → old back-stack entries decode
)
```
Import `com.stash.data.ytmusic.model.AlbumSource`. (Nav-compose serializes `@Serializable` enums via the type-safe route; the default covers deserialization of any pre-update back-stack entry.)

- [ ] **Step 2: Pass `source = album.source` at all three construction sites** in `StashNavHost.kt` (they already build from an `AlbumSummary` `album`).

- [ ] **Step 3: Compile.** Run: `./gradlew :app:compileDebugKotlin` — expect success.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat(discography): thread AlbumSource through SearchAlbumRoute to the album screen"
```

---

## Task 12: `AlbumDiscoveryViewModel` — Qobuz branch (persist for play/resume; guard videoId side-effects)

Carries spec **must-address #2** (guard blank-videoId side-effects), **#3** (eager-persist timing / source label), **#4** (same-canonical-id dup edge, noted).

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt`
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/AlbumDiscoveryViewModelQobuzTest.kt`

- [ ] **Step 1: Failing tests** (fake `AlbumCache` returning a QOBUZ detail; fake `MusicRepository`; MockK `PlayerRepository`)

```kotlin
@Test fun `qobuz album passes source to AlbumCache`() = runTest {
    vm(source = QOBUZ, browseId = "123"); advanceUntilIdle()
    coVerify { albumCache.get("123", AlbumSource.QOBUZ) }
}
@Test fun `qobuz play persists tracks and queues real ids`() = runTest {
    coEvery { musicRepo.ensureTrackPersisted(any()) } returnsMany listOf(1001L, 1002L)
    val vm = vm(source = QOBUZ); advanceUntilIdle()
    vm.playAlbum(0); advanceUntilIdle()
    coVerify { player.setQueue(match { it.map(Track::id) == listOf(1001L,1002L) && it.all { t -> t.youtubeId == null } }, 0) }
}
@Test fun `qobuz album does NOT run videoId-keyed side effects`() = runTest {
    val vm = vm(source = QOBUZ); advanceUntilIdle()
    coVerify(exactly = 0) { prefetcher.prefetch(any()) }
    coVerify(exactly = 0) { delegate.refreshDownloadedIds(any()) }
    coVerify(exactly = 0) { musicRepo.backfillAlbumForTracks(any(), any(), any()) }
}
@Test fun `youtube album is unchanged (regression)`() = runTest {
    val vm = vm(source = YOUTUBE); advanceUntilIdle()
    coVerify { prefetcher.prefetch(any()) }
}
```

- [ ] **Step 2: Run — expect FAIL.**
Run: `./gradlew :feature:search:testDebugUnitTest --tests "*AlbumDiscoveryViewModelQobuzTest"`

- [ ] **Step 3: Implement**

Read the nav source:
```kotlin
private val albumSource: AlbumSource = savedStateHandle["source"] ?: AlbumSource.YOUTUBE
```
In `observeAlbum()`, pass it and guard the three side-effects:
```kotlin
val detail = albumCache.get(browseId, albumSource)
...
if (albumSource == AlbumSource.YOUTUBE) {
    if (!prefetchKicked && detail.tracks.isNotEmpty()) {
        prefetchKicked = true
        prefetcher.prefetch(detail.tracks.take(6).map { it.videoId })
    }
    delegate.refreshDownloadedIds(detail.tracks.map { it.videoId })
    // ... existing backfillAlbumForTracks block ...
}
// Qobuz tracks carry blank videoIds → these videoId-keyed ops would mis-key; skip.
```
Split queue-building so Qobuz tracks persist to real PKs (survives resume + hearts):
```kotlin
fun playAlbum(startIndex: Int = 0) {
    viewModelScope.launch {
        val tracks = buildQueueTracks()
        if (tracks.isEmpty()) return@launch
        playerRepository.setQueue(tracks, startIndex.coerceIn(0, tracks.size - 1))
    }
}
// addAlbumToQueue(): use buildQueueTracks() likewise.

private suspend fun buildQueueTracks(): List<Track> {
    val base = synthesizeDomainTracks()  // now source-branched (below)
    if (albumSource != AlbumSource.QOBUZ) return base
    // Persist each Qobuz track by canonical identity → real PK, so a persisted
    // queue resumes and the heart observes it. (Phase 1: rows carry
    // source=YOUTUBE, youtubeId=null — harmless to qbdlx resolution; MusicSource.QOBUZ
    // is a Phase-2 concern.)
    return base.map { it.copy(id = musicRepository.ensureTrackPersisted(it)) }
}
```
Branch `synthesizeDomainTracks` so Qobuz rows get `id = 0` (let `ensureTrackPersisted` assign) and `youtubeId = null`:
```kotlin
return tracks.map { t ->
    if (albumSource == AlbumSource.QOBUZ) Track(
        id = 0L, title = t.title, artist = t.artist.ifBlank { albumArtist },
        album = albumTitle, durationMs = (t.durationSeconds * 1000L).toLong(),
        albumArtUrl = t.thumbnailUrl ?: albumArt, youtubeId = null,
        source = MusicSource.YOUTUBE, isStreamable = true,
    ) else Track(
        id = t.videoId.hashCode().toLong(), /* ...existing YT mapping... */
    )
}
```

- [ ] **Step 4: Run — expect PASS.** Same command as Step 2. Regression: `./gradlew :feature:search:testDebugUnitTest --tests "*AlbumDiscovery*"`

- [ ] **Step 5: Commit**
```bash
git add feature/search/src/main/kotlin/.../AlbumDiscoveryViewModel.kt feature/search/src/test/kotlin/.../AlbumDiscoveryViewModelQobuzTest.kt
git commit -m "feat(discography): AlbumDiscoveryVM Qobuz branch — persist-for-resume + guard videoId side-effects"
```

**Must-address #4 (noted, no code):** two tracks in one Qobuz album that canonicalize identically collapse to one PK via `ensureTrackPersisted`, so a tapped `startIndex` could resolve to the wrong row. Rare (duplicate-title within one album); acceptable for Phase 1. If it surfaces, Phase 2's by-id path removes the ambiguity.

---

## Task 13: On-device verification (the real acceptance test)

**Files:** none (manual, on the Pixel 6 Pro debug variant — `192.168.137.88:45397`, `com.stash.app.debug`).

- [ ] **Step 1: Build + install.** `./gradlew :app:installDebug` — expect `Installed on N devices`.
- [ ] **Step 2: Navigate to My Bloody Valentine** (Search → "my bloody valentine" → artist, or Now Playing tap-to-artist on an MBV track). Confirm the discography now shows **Loveless** and **Isn't Anything** (Qobuz-supplemented) alongside the YT entries, with **no duplicates**.
- [ ] **Step 3: Tap Loveless → Play.** Confirm playback starts and Now Playing shows the **FLAC** badge with `streamOrigin = qbdlx` (pull logcat: `adb -s 192.168.137.88:45397 logcat -d -s StreamSourceRegistry QbdlxStreamResolver`). Expect a `qbdlx served ...` line.
- [ ] **Step 4: Resume check.** With a Qobuz album playing, force-stop and relaunch; confirm the queue resumes (not empty) — validates the persist-for-resume path.
- [ ] **Step 5: Negative check.** Open a mainstream artist fully present on YT (e.g. Drake); confirm the grid is unchanged / not duplicated and still plays (supplement merged without disruption). Open an ambiguous-name artist with a sparse YT page if available; confirm no obviously-wrong records were grafted.
- [ ] **Step 6:** If all pass, the feature is device-verified. Note any anomaly against the relevant task rather than force-completing.

---

## Notes for the executor

- **Test styles:** match each module's existing tests — `data:download` uses plain JUnit + offline `test/resources` fixtures; `core:data` cache tests inject a fixed clock; `feature:search` VM tests use MockK + `runTest`/`advanceUntilIdle`. Don't introduce new frameworks.
- **Pre-existing red tests** are catalogued in memory (`:data:ytmusic YTMusicApiClientTest`, `:feature:search PreviewPrefetcherTest`) — always use `--tests` filters so they don't mask your signal.
- **Gradle:** use the daemon (not `--no-daemon`); `--tests` filter every run.
- **Zero core:media change is an invariant of Phase 1** — if a task tempts you to touch `core:media`, the resolver, `TrackEntity`, or `stashResolveUri`, stop: that's Phase 2, and the design is wrong if Phase 1 needs it.
