package com.stash.data.download.lossless.qbdlx

import com.stash.core.data.discography.DiscographySupplement
import com.stash.core.data.discography.MergedDiscography
import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DiscographySupplement] backed by the direct-Qobuz (qbdlx) catalog. Given a YT
 * artist name + its YT albums/singles, it gates on qbdlx being usable, matches
 * the artist on Qobuz, fetches its albums, and unions them into the YT lists via
 * [DiscographyMerger].
 *
 * Correctness-critical: the dangerous failure is a FALSE artist match grafting a
 * stranger's discography. The gate therefore FAILS SAFE — on any doubt (source
 * off, no token, blank/VA name, no candidate over threshold, ambiguous homonym,
 * or any thrown exception) it returns the YT lists UNCHANGED. The outer
 * `runCatching { … }.getOrElse { unchanged }` guarantees nothing throws out.
 */
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

        val candidates = apiClient.searchArtists(artistName, token)
            .filter { QobuzCandidateMatcher.normalize(it.name) !in VARIOUS_ARTISTS }
            .filterNot { isSuperstringPseudo(nName, QobuzCandidateMatcher.normalize(it.name)) }
            .distinctBy { it.id }
        val scored = candidates
            .map { it to QobuzCandidateMatcher.artistSimilarity(nName, QobuzCandidateMatcher.normalize(it.name)) }
            .filter { it.second >= ARTIST_MATCH_THRESHOLD }
            .sortedByDescending { it.second }
        // Collapse the artist's OWN duplicate entities (same name, different id):
        // dedup by normalized NAME so "distinct candidate" == "distinct artist".
        val distinctArtists = scored.distinctBy { QobuzCandidateMatcher.normalize(it.first.name) }
        val best = distinctArtists.firstOrNull()?.first ?: return unchanged(ytAlbums, ytSingles)

        val qAlbumsRaw = apiClient.getArtistAlbums(best.id, token)
        if (ytAlbums.isNotEmpty()) {
            // A confident single match still needs corroboration: at least one Qobuz
            // release title must overlap a YT one, else we may have matched a homonym.
            val ytKeys = ytAlbums.map { QobuzCandidateMatcher.normalize(it.title) }.toSet()
            val overlap = qAlbumsRaw.any { QobuzCandidateMatcher.normalize(it.title) in ytKeys }
            if (!overlap) return unchanged(ytAlbums, ytSingles)
        } else {
            // No YT titles to corroborate against → an ambiguous name is unresolvable.
            if (distinctArtists.size >= 2) return unchanged(ytAlbums, ytSingles)
        }

        val (rawAlbums, rawSingles) = qAlbumsRaw.partition { it.isAlbumLane }
        MergedDiscography(
            albums = DiscographyMerger.mergeLane(ytAlbums, rawAlbums.map { it.toAlbumSummary(best.name) }),
            singles = DiscographyMerger.mergeLane(ytSingles, rawSingles.map { it.toAlbumSummary(best.name) }),
        )
    }.getOrElse { unchanged(ytAlbums, ytSingles) }

    private fun unchanged(a: List<AlbumSummary>, s: List<AlbumSummary>) = MergedDiscography(a, s)

    /** A candidate whose normalized name strictly contains the query PLUS extra
     *  tokens (e.g. "my bloody valentine tribute") is a pseudo-artist — drop it so
     *  it can't create false ambiguity for the real one. Exact match (c == q) stays. */
    private fun isSuperstringPseudo(query: String, cand: String): Boolean {
        val q = query.split(" ").filter { it.isNotEmpty() }.toSet()
        val c = cand.split(" ").filter { it.isNotEmpty() }.toSet()
        return c != q && c.containsAll(q) && (c - q).isNotEmpty()
    }

    private companion object {
        // ponytail: subset-coverage in artistSimilarity already returns ~1.0 for a
        // superset/canonical name match, so 0.6 comfortably admits real matches while
        // rejecting partial-token noise. Bump only if a real false-graft slips through.
        const val ARTIST_MATCH_THRESHOLD = 0.6f
        val VARIOUS_ARTISTS = setOf(
            "various artists", "various", "verschiedene interpreten", "multi artistes", "va",
        )
    }
}

/** release_type is NULL for every item from getArtistAlbums (it only appears in
 *  album-detail), so null → albums lane; only an explicit "single"/"ep" is a single. */
private val QbdlxAlbumItem.isAlbumLane: Boolean
    get() = when (release_type?.lowercase()) {
        "single", "ep" -> false
        else -> true
    }

private fun QbdlxAlbumItem.toAlbumSummary(artistName: String) = AlbumSummary(
    id = id,
    title = title,
    artist = artist?.name?.ifBlank { artistName } ?: artistName,
    thumbnailUrl = image?.large ?: image?.small ?: image?.thumbnail,
    year = release_date_original, // "YYYY-MM-DD"; DiscographyMerger parses the year
    source = AlbumSource.QOBUZ,
)
