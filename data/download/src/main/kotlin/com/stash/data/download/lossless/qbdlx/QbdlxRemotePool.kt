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
         * Keeps rows that are usable by THIS build and flattens them to the
         * `token:country` pool format.
         *
         * The app_id filter is load-bearing, not incidental: the endpoint also
         * serves tokens for a different Qobuz app_id, and those need that app's
         * own app_secret to sign a request. We bundle exactly one app_id/secret
         * pair, so a token from the other app would fail every signature. Drop
         * them rather than ship credentials we cannot sign for.
         *
         * Internal + pure so it can be tested without a network.
         */
        internal fun parsePool(
            body: String,
            appId: String = com.stash.data.download.BuildConfig.QBDLX_APP_ID,
        ): String {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
                ?: return ""
            val seen = LinkedHashMap<String, String>()
            for (element in root) {
                val row = element as? JsonObject ?: continue
                fun str(key: String) = row[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val token = str("token")
                val country = str("country")
                val rowAppId = str("app_id")
                if (token.isEmpty() || country.isEmpty()) continue
                if (appId.isNotEmpty() && rowAppId != appId) continue
                seen.putIfAbsent(token, country)
            }
            return seen.entries.joinToString(",") { (token, country) -> "$token:$country" }
        }
    }
}
