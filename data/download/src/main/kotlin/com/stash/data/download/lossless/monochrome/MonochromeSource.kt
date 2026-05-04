package com.stash.data.download.lossless.monochrome

import android.util.Log
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * [LosslessSource] backed by Tidal's catalog via `api.monochrome.tf`
 * (uimaxbai/hifi-api fork running at the Monochrome operator's hosted
 * instance). Searches for the requested track, scores candidates by
 * ISRC / title / artist / duration agreement, and resolves to a plain
 * FLAC URL from Tidal's CDN.
 *
 * Quality is hardcoded to `"LOSSLESS"` (16-bit/44.1 FLAC). Hi-Res
 * (`HI_RES_LOSSLESS`) requires Widevine DRM and is out of scope for v0.9.10.
 * If the manifest's `encryptionType` is anything other than `"NONE"`,
 * the source returns null and the registry tries the next source.
 *
 * Sibling to [com.stash.data.download.lossless.qobuz.QobuzSource]. Same
 * operator-credentialed risk model — different operator, different
 * upstream service, different account pool, uncorrelated outage risk.
 */
@Singleton
class MonochromeSource @Inject constructor(
    private val apiClient: MonochromeApiClient,
    private val rateLimiter: AggregatorRateLimiter,
) : LosslessSource {

    override val id: String = SOURCE_ID

    override val displayName: String = "Tidal (via Monochrome)"

    override suspend fun isEnabled(): Boolean =
        !rateLimiter.stateOf(id).isCircuitBroken

    override suspend fun resolve(query: TrackQuery): SourceResult? {
        // 1. Search.
        val searchTerm = "${query.artist} ${query.title}"
        val searchData = callLimited { apiClient.search(searchTerm) } ?: return null

        val candidates = searchData.data?.items.orEmpty()
            .filter { it.streamReady }
        if (candidates.isEmpty()) return null

        // 2. Score and pick the best candidate.
        val scored = candidates.map { it to confidence(query, it) }
        val best = scored
            .filter { it.second >= MIN_CONFIDENCE }
            .maxByOrNull { it.second }

        if (best == null) {
            val top = scored.sortedByDescending { it.second }.take(3)
            Log.d(
                TAG,
                "no candidate above $MIN_CONFIDENCE for '${query.artist} - ${query.title}': " +
                    top.joinToString(", ") { (c, s) ->
                        "[${"%.2f".format(s)} '${c.title}' by '${c.firstArtistName()}']"
                    },
            )
            return null
        }

        // 3. Resolve to stream manifest. Hardcoded LOSSLESS — we never
        //    want HI_RES (Widevine) or HIGH (lossy AAC).
        val manifestEnvelope = callLimited { apiClient.track(best.first.id, "LOSSLESS") }
            ?: return null
        val manifestB64 = manifestEnvelope.data?.manifest
        if (manifestB64.isNullOrBlank()) {
            Log.d(TAG, "empty manifest for track ${best.first.id}")
            return null
        }

        // 4. Decode base64 manifest + reject anything DRM-encrypted.
        val decoded = decodeManifest(manifestB64, manifestJson) ?: run {
            Log.w(TAG, "manifest decode failed for track ${best.first.id}")
            return null
        }
        if (decoded.encryptionType != "NONE") {
            Log.d(TAG, "skipping encrypted manifest (${decoded.encryptionType}) for track ${best.first.id}")
            return null
        }
        val downloadUrl = decoded.urls.firstOrNull()
        if (downloadUrl.isNullOrBlank()) {
            Log.d(TAG, "empty url list in decoded manifest for track ${best.first.id}")
            return null
        }

        return SourceResult(
            sourceId = id,
            downloadUrl = downloadUrl,
            // Tidal CDN URLs are pre-signed — no extra headers needed.
            downloadHeaders = emptyMap(),
            format = AudioFormat(
                codec = "flac",
                // CD-quality lossless: 16-bit / 44.1 kHz, ~1411 kbps.
                bitrateKbps = 1411,
                sampleRateHz = 44_100,
                bitsPerSample = 16,
            ),
            confidence = best.second,
            sourceTrackId = best.first.id.toString(),
            coverArtUrl = coverIdToUrl(best.first.album?.cover),
        )
    }

    override suspend fun rateLimitState(): RateLimitState = rateLimiter.stateOf(id)

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Wraps an API call with rate-limiter bookkeeping. Returns null on
     * any failure mode so callers can `?: return null` to skip cleanly.
     * Direct port of QobuzSource.callLimited minus the captcha-expired
     * branch (Monochrome has no captcha cookie).
     */
    private suspend fun <T> callLimited(block: suspend () -> T): T? {
        if (!rateLimiter.acquire(id)) return null
        return try {
            val result = block()
            rateLimiter.reportSuccess(id)
            result
        } catch (e: MonochromeApiException) {
            when (e.status) {
                429 -> rateLimiter.reportRateLimited(id)
                else -> rateLimiter.reportFailure(id)
            }
            Log.w(TAG, "monochrome API call failed: ${e.message}")
            null
        } catch (e: Exception) {
            rateLimiter.reportFailure(id)
            Log.w(TAG, "monochrome call threw: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Confidence score on [0.0, 1.0]. Direct port of QobuzSource.confidence
     * adapted to TidalTrack. ISRC equality short-circuits to 0.95.
     */
    private fun confidence(query: TrackQuery, candidate: TidalTrack): Float {
        // ISRC match → highest non-1.0 score.
        val queryIsrc = query.isrc?.takeIf { it.isNotBlank() }
        val candidateIsrc = candidate.isrc?.takeIf { it.isNotBlank() }
        if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            return 0.95f
        }

        val titleSim = jaccard(normalize(query.title), normalize(candidate.title))
        val artistSim = artistSimilarity(
            normalize(query.artist),
            normalize(candidate.firstArtistName()),
        )

        // Duration similarity. Same scoring as QobuzSource.
        val durationFactor: Float = run {
            val queryMs = query.durationMs ?: return@run 1.0f
            if (queryMs <= 0 || candidate.duration <= 0) return@run 1.0f
            val candidateMs = candidate.duration * 1000L
            val drift = abs(queryMs - candidateMs).toDouble() / queryMs.toDouble()
            when {
                drift < 0.05 -> 1.0f
                drift < 0.10 -> 0.85f
                drift < 0.20 -> 0.6f
                else -> 0.3f
            }
        }

        return (titleSim * artistSim * durationFactor)
    }

    private fun TidalTrack.firstArtistName(): String =
        artist?.name?.takeIf { it.isNotBlank() }
            ?: artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: ""

    companion object {
        /** Per LosslessSource KDoc convention. */
        const val SOURCE_ID = "monochrome_tidal"
        private const val TAG = "MonochromeSource"

        /** Threshold below which a candidate is rejected outright. */
        private const val MIN_CONFIDENCE = 0.5f

        /** Reused JSON parser for base64-decoded manifests. */
        private val manifestJson = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        // ── Pure-function helpers (kept package-internal for testing) ─
        // Direct copy from QobuzSource — same Jaccard + artist-subset
        // logic. A future refactor may extract to a shared helpers file
        // once a third source joins (YAGNI for two sources).

        /**
         * Lowercase + strip parenthetical content, "feat./featuring"
         * suffixes, and non-alphanumeric characters; collapse whitespace.
         */
        internal fun normalize(s: String): String =
            s.lowercase()
                .replace(Regex("\\([^)]*\\)"), " ")
                .replace(Regex("\\[[^]]*\\]"), " ")
                .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
                .replace(Regex("[''`]"), "")
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** Jaccard similarity on whitespace-tokenized strings. */
        internal fun jaccard(a: String, b: String): Float {
            val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
            val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
            if (setA.isEmpty() || setB.isEmpty()) return 0f
            val intersection = setA.intersect(setB).size.toFloat()
            val union = setA.union(setB).size.toFloat()
            return intersection / union
        }

        /**
         * Artist-aware similarity: max of plain jaccard and subset-coverage.
         * Direct copy of QobuzSource.artistSimilarity. See that file for
         * the full rationale on Spotify-expansion vs canonical-short-form
         * matching.
         */
        internal fun artistSimilarity(a: String, b: String): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val tokensA = a.split(" ").filter { it.isNotEmpty() }.toSet()
            val tokensB = b.split(" ").filter { it.isNotEmpty() }.toSet()
            if (tokensA.isEmpty() || tokensB.isEmpty()) return 0f

            val intersection = tokensA.intersect(tokensB).size.toFloat()
            val union = tokensA.union(tokensB).size.toFloat()
            val jaccard = if (union == 0f) 0f else intersection / union

            // Subset coverage with distinctive-token gate.
            val (smaller, larger) = if (tokensA.size <= tokensB.size) tokensA to tokensB else tokensB to tokensA
            val isSubset = smaller.all { it in larger }
            val hasDistinctive = smaller.any { it.length > 3 }
            val subsetScore = if (isSubset && hasDistinctive && smaller.size >= 1) 1.0f else 0f

            return maxOf(jaccard, subsetScore)
        }
    }
}
