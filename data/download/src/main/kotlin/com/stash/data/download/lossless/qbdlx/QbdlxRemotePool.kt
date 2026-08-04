package com.stash.data.download.lossless.qbdlx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the CURRENT shared token pool at runtime.
 *
 * Why this exists: the pool is baked into the APK at build time, so a shipped
 * build's credentials rot from the day it ships. Qobuz tokens rotate roughly
 * monthly, and three separate times a rotation has left a released build with
 * ZERO working tokens — lossless silently dead for everyone until we cut a new
 * release. Refreshing at runtime breaks that coupling.
 *
 * Returns null on ANY failure. The caller keeps whatever pool it already has:
 * this is an opportunistic upgrade, never a dependency. A hobbyist webhook must
 * not be able to take playback down.
 */
fun interface QbdlxRemotePool {
    /** `"token:country,token:country,…"`, or null if unavailable/empty. */
    suspend fun fetch(): String?
}

/**
 * Default [QbdlxRemotePool]: the same open JSON endpoint the release workflow
 * reads at build time, filtered the same way, so runtime and build-time pools
 * agree on what "usable" means.
 */
@Singleton
class HttpQbdlxRemotePool @Inject constructor(
    private val client: OkHttpClient,
) : QbdlxRemotePool {

    internal var endpoint: String = POOL_ENDPOINT
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(endpoint)
                // The webhook is fronted by the qbdlx SPA's origin; without these
                // it can answer differently. Mirrors the curl in release.yml.
                .header("User-Agent", BROWSER_UA)
                .header("Origin", "https://qbdlx.launchpd.cloud")
                .header("Referer", "https://qbdlx.launchpd.cloud/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                parsePool(body).ifBlank { null }
            }
        }.getOrNull()
    }

    companion object {
        const val POOL_ENDPOINT = "https://citegptapi.f5.si/webhook/qbdlx/shared"

        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /**
         * Keeps every row we can SIGN and flattens them to the tagged
         * `token:country:appId` pool format.
         *
         * The endpoint serves tokens for more than one Qobuz app_id, each needing
         * its own app_secret to sign. We used to bundle a single pair and DROP the
         * other app_id's tokens — throwing away ~13 of ~21 tokens and running the
         * primary app_id down to zero live tokens. Now we bundle each app_id's
         * secret ([knownAppIds]) and TAG every kept token with its app_id, so the
         * signer picks the right secret ([QbdlxSigningResolver]) instead of
         * mismatching it into a preview. A row we still have no secret for is
         * dropped (an unsignable token is worse than none — it just previews).
         *
         * Internal + pure so it can be tested without a network.
         */
        internal fun parsePool(
            body: String,
            primaryAppId: String = com.stash.data.download.BuildConfig.QBDLX_APP_ID,
            knownAppIds: Set<String> = knownAppIdsFromBuildConfig(),
        ): String {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
                ?: return ""
            val seen = LinkedHashMap<String, String>() // token -> "country:appId"
            for (element in root) {
                val row = element as? JsonObject ?: continue
                fun str(key: String) = row[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val token = str("token")
                val country = str("country")
                if (token.isEmpty() || country.isEmpty()) continue
                // No app_id on a row → assume the primary app's (the historical
                // default). Keep the row only if we can sign for its app_id.
                val appId = str("app_id").ifEmpty { primaryAppId }
                if (knownAppIds.isNotEmpty() && appId !in knownAppIds) continue
                seen.putIfAbsent(token, "$country:$appId")
            }
            return seen.entries.joinToString(",") { (token, tail) -> "$token:$tail" }
        }

        /** app_ids this build bundles a secret for: the primary plus every extra pair. */
        private fun knownAppIdsFromBuildConfig(): Set<String> {
            val ids = linkedSetOf(com.stash.data.download.BuildConfig.QBDLX_APP_ID)
            com.stash.data.download.BuildConfig.QBDLX_APP_SECRETS.split(",").forEach { pair ->
                val i = pair.indexOf(':')
                if (i > 0) ids += pair.take(i).trim()
            }
            return ids.filter { it.isNotEmpty() }.toSet()
        }
    }
}
