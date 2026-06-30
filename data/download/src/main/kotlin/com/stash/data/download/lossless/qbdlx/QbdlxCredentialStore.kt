package com.stash.data.download.lossless.qbdlx

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.data.download.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Its own preferences DataStore (mirrors
 * [com.stash.data.download.lossless.arcod.ArcodCredentialStore]) so the qbdlx
 * token state lives apart from the cross-source
 * [com.stash.data.download.lossless.LosslessSourcePreferences] schema.
 */
private val Context.qbdlxCredentialsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "qbdlx_creds",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Manages the qbdlx Qobuz token pool: a bundled set of `user_auth_token:country`
 * pairs (from [BuildConfig.QBDLX_TOKEN_POOL]) plus an optional user-pasted token
 * that takes priority and serves as the refresh path when the bundled pool ages
 * out (~monthly).
 *
 * Responsibilities:
 *  - [activeToken]: the token to use now — pasted (if live) first, else
 *    round-robin across the live (non-dead) pool tokens to spread load (the
 *    breaker is per-source, but Qobuz ban-risk is per-account, so we don't pin
 *    one token). Null when nothing is live.
 *  - [tokensForRegion]: ordered live tokens for a region-locked retry,
 *    country-matched first, bounded at [MAX_REGION_TRIES] so one locked track
 *    can't fan out across every account.
 *  - [markDead]/[recordAlive]: persist a token as dead (auth-failed) / clear it,
 *    so a cold start doesn't re-probe dead tokens.
 *  - [allDead]: true when the pasted token (if any) is dead AND every pool token
 *    is dead — drives the Settings "expired, paste a fresh token" surface.
 *
 * `app_id` + `app_secret` are constant and read directly from BuildConfig by the
 * client/signer; only the rotating tokens are managed here.
 */
@Singleton
class QbdlxCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pastedTokenKey = stringPreferencesKey("pasted_token")
    private val deadTokensKey = stringPreferencesKey("dead_tokens")

    /**
     * Test seam: the raw `token:country,token:country` pool. Defaults to the
     * bundled BuildConfig value; tests override it with a known pool so they
     * don't depend on BuildConfig.
     */
    internal var poolRaw: String = BuildConfig.QBDLX_TOKEN_POOL

    /** Round-robin cursor across live pool tokens. In-memory (per process). */
    private val rrIndex = AtomicInteger(0)

    /** Parsed pool: (token, ISO-2 country). Split on the LAST ':' defensively. */
    private fun pool(): List<Pair<String, String>> =
        poolRaw.split(",")
            .mapNotNull { entry ->
                val e = entry.trim().ifEmpty { return@mapNotNull null }
                val i = e.lastIndexOf(':')
                if (i <= 0) e to "" else e.substring(0, i) to e.substring(i + 1)
            }

    private suspend fun deadSet(): Set<String> =
        context.qbdlxCredentialsDataStore.data.first()[deadTokensKey]
            ?.split(",")?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    private suspend fun pastedToken(): String? =
        context.qbdlxCredentialsDataStore.data.first()[pastedTokenKey]?.takeIf { it.isNotBlank() }

    /** The token to use now: pasted (if live) first, else round-robin over live pool tokens. */
    suspend fun activeToken(): String? {
        val dead = deadSet()
        pastedToken()?.let { if (it !in dead) return it }
        val live = pool().map { it.first }.filter { it !in dead }
        if (live.isEmpty()) return null
        return live[(rrIndex.getAndIncrement() % live.size + live.size) % live.size]
    }

    /**
     * Live tokens to try for a region-locked track: country-matched first, then
     * the rest, capped at [MAX_REGION_TRIES].
     */
    suspend fun tokensForRegion(country: String?): List<String> {
        val dead = deadSet()
        val live = pool().filter { it.first !in dead }
        val sorted = if (country.isNullOrBlank()) {
            live
        } else {
            live.sortedByDescending { it.second.equals(country, ignoreCase = true) }
        }
        return sorted.map { it.first }.take(MAX_REGION_TRIES)
    }

    /** Persist [token] as dead (auth-failed), so it's skipped until [recordAlive]. */
    suspend fun markDead(token: String) {
        context.qbdlxCredentialsDataStore.edit { prefs ->
            val current = prefs[deadTokensKey]?.split(",")?.filter { it.isNotEmpty() }?.toMutableSet()
                ?: mutableSetOf()
            current += token
            prefs[deadTokensKey] = current.joinToString(",")
        }
    }

    /** Clear a token's dead flag after a successful call. */
    suspend fun recordAlive(token: String) {
        context.qbdlxCredentialsDataStore.edit { prefs ->
            val current = prefs[deadTokensKey]?.split(",")?.filter { it.isNotEmpty() }?.toMutableSet()
                ?: return@edit
            if (current.remove(token)) prefs[deadTokensKey] = current.joinToString(",")
        }
    }

    /** Set (or clear, with null) the user-pasted token. */
    suspend fun setPastedToken(token: String?) {
        context.qbdlxCredentialsDataStore.edit { prefs ->
            val t = token?.trim()
            if (t.isNullOrEmpty()) prefs.remove(pastedTokenKey) else prefs[pastedTokenKey] = t
        }
    }

    /** True when the pasted token (if any) is dead AND every pool token is dead. */
    suspend fun allDead(): Boolean {
        val dead = deadSet()
        pastedToken()?.let { if (it !in dead) return false }
        return pool().map { it.first }.all { it in dead }
    }

    /** Test-only: wipe persisted pasted/dead state (the DataStore is per-process). */
    internal suspend fun clearPersistedForTest() {
        context.qbdlxCredentialsDataStore.edit { it.clear() }
    }

    companion object {
        const val MAX_REGION_TRIES = 3
    }
}
