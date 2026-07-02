package com.stash.feature.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recentSearchesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_searches",
)

/**
 * Most-recent-first list of committed search queries, capped at [MAX].
 * Backed by a single newline-delimited string value (the same single-string
 * pattern LosslessSourcePreferences uses for its comma-list). Dedupe is
 * case-insensitive; re-recording an existing query moves it to the front.
 */
@Singleton
class RecentSearchesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("queries")

    val recent: Flow<List<String>> = context.recentSearchesDataStore.data.map { prefs ->
        prefs[key].toList()
    }

    /** Record [query] as the most-recent search. No-op for blank input. */
    suspend fun record(query: String) {
        val q = query.trim().replace("\n", " ")
        if (q.isEmpty()) return
        context.recentSearchesDataStore.edit { prefs ->
            val deduped = prefs[key].toList().filterNot { it.equals(q, ignoreCase = true) }
            prefs[key] = (listOf(q) + deduped).take(MAX).joinToString("\n")
        }
    }

    /** Remove a single query (case-insensitive). */
    suspend fun remove(query: String) {
        context.recentSearchesDataStore.edit { prefs ->
            prefs[key] = prefs[key].toList()
                .filterNot { it.equals(query, ignoreCase = true) }
                .joinToString("\n")
        }
    }

    /** Clear all recent searches. */
    suspend fun clear() {
        context.recentSearchesDataStore.edit { it.remove(key) }
    }

    /** Parse the stored newline-delimited value into a clean list. */
    private fun String?.toList(): List<String> =
        this?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    companion object {
        /** Max retained queries — industry-standard recents depth. */
        const val MAX = 10
    }
}
