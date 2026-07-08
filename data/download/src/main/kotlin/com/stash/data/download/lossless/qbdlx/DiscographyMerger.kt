package com.stash.data.download.lossless.qbdlx

import com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher
import com.stash.data.ytmusic.model.AlbumSummary

/** Pure discography merge: union YT + Qobuz within one lane (album or single),
 *  Qobuz-preferred on a corroborated title collision, newest-first by earliest
 *  known year. NOTE: callers merge each lane separately (album↔album,
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

    /** Corroborate a same-title collision so a differently-dated compilation
     *  doesn't false-merge. A *generic compilation* title ("Greatest Hits",
     *  "Best Of", …) legitimately recurs across years as DIFFERENT releases, so
     *  it only merges when a secondary signal agrees (years within 1). A
     *  distinctive album title that recurs is the same record re-dated by a
     *  remaster (Loveless 1991 → Qobuz 2021), so it merges on the title alone.
     *  Unknown year → trust the title. */
    private fun sameRelease(a: AlbumSummary, b: AlbumSummary): Boolean {
        if (!isGenericTitle(key(a.title))) return true // distinctive title → same record (remaster re-date)
        val ya = yearInt(a.year); val yb = yearInt(b.year)
        if (ya == Int.MIN_VALUE || yb == Int.MIN_VALUE) return true // one unknown → trust the title
        return kotlin.math.abs(ya - yb) <= 1
    }

    // ponytail: marker list is the ceiling; extend it if a real catalog surfaces
    // another generic compilation phrase that false-merges across years.
    private val GENERIC_TITLE_MARKERS = listOf(
        "greatest hits", "best of", "essential", "anthology", "collection", "compilation",
    )
    private fun isGenericTitle(normalizedTitle: String) =
        GENERIC_TITLE_MARKERS.any { normalizedTitle.contains(it) }

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
