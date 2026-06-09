package com.stash.feature.settings.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.stash.data.download.lossless.antra.AntraFingerprint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Hosts a [WebView] pointed at `antra.hoshi.cfd` so the user can log in and
 * pass Cloudflare's JS challenge in-app. antra gates its API behind a
 * `session` cookie (from login) plus a `cf_clearance` cookie (from the
 * Cloudflare challenge); both must be present before requests authenticate.
 *
 * A polling coroutine reads [CookieManager] until BOTH cookies appear, then
 * confirms the login is real by running an in-page `fetch('/api/auth/me')`
 * (which executes with the page's valid `cf_clearance` + matching TLS, so it
 * succeeds where a cold request would 403). When `/api/auth/me` returns a
 * username, it forwards `(session, cf_clearance, username)` to [onConnected]
 * and pops. Mirrors [SquidWtfCaptchaScreen].
 *
 * The WebView's User-Agent is pinned to [AntraFingerprint.USER_AGENT] — the
 * SAME UA the OkHttp interceptor replays — so the `cf_clearance` minted here
 * stays valid when Stash later replays the cookies (Approach A).
 */
@Composable
fun AntraConnectScreen(
    onConnected: (session: String, cfClearance: String, username: String) -> Unit,
    onClose: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var statusText by remember {
        mutableStateOf(
            "To connect:\n" +
                "1. Log in to your antra account\n" +
                "2. Wait for the Cloudflare check to pass\n" +
                "Connection saves automatically once you're logged in.",
        )
    }
    var captured by remember { mutableStateOf(false) }

    // ── Cookie polling + login confirmation ─────────────────────────────
    LaunchedEffect(Unit) {
        while (isActive && !captured) {
            delay(POLL_INTERVAL_MS)
            val cookies = CookieManager.getInstance().getCookie(ANTRA_URL).orEmpty()
            val session = SESSION_REGEX.find(cookies)?.groupValues?.get(1)
            val cfClearance = CF_CLEARANCE_REGEX.find(cookies)?.groupValues?.get(1)
            if (session.isNullOrBlank() || cfClearance.isNullOrBlank()) continue

            // Both cookies present — confirm the session is actually logged
            // in (cookies can exist pre-login) by hitting /api/auth/me in the
            // page context.
            val wv = webViewRef ?: continue
            val username = fetchUsername(wv)
            if (!username.isNullOrBlank()) {
                captured = true
                statusText = "Connected as $username — saving and closing."
                onConnected(session, cfClearance, username)
                delay(400)
                onClose()
            }
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Connect antra",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (captured) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context -> buildWebView(context).also { webViewRef = it } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Runs `fetch('/api/auth/me', {credentials:'include'})` inside the page and
 * returns the `username` field, or null if not logged in / the call failed.
 * The fetch runs in the WebView's context, so it carries the live
 * `cf_clearance` and the matching TLS fingerprint.
 */
private suspend fun fetchUsername(webView: WebView): String? =
    suspendCancellableCoroutine { cont ->
        // An async IIFE: evaluateJavascript returns the awaited promise's
        // resolved value (the response body text, or "" on any failure).
        val awaited =
            "(async()=>{try{const r=await fetch('/api/auth/me',{credentials:'include'});" +
                "return r.ok?await r.text():''}catch(e){return ''}})()"
        webView.evaluateJavascript(awaited) { result ->
            // result is a JSON-encoded string (quoted) of the body text, or
            // "null" — decode one layer, then parse the username.
            val body = decodeJsString(result)
            val username = runCatching {
                if (body.isBlank()) null else JSONObject(body).optString("username").takeIf { it.isNotBlank() }
            }.getOrNull()
            if (cont.isActive) cont.resume(username)
        }
    }

/** Decodes the JSON-string literal evaluateJavascript hands back. */
private fun decodeJsString(raw: String?): String {
    if (raw == null || raw == "null") return ""
    // raw is a quoted JSON string like "\"{\\\"username\\\":...}\"".
    return runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault("")
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(context: android.content.Context): WebView {
    CookieManager.getInstance().setAcceptCookie(true)

    return WebView(context).apply {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.apply {
            javaScriptEnabled = true     // Cloudflare challenge is JS
            domStorageEnabled = true     // antra SPA uses localStorage
            // Pin the UA to the shared fingerprint so the cf_clearance this
            // WebView mints matches what the OkHttp interceptor replays.
            userAgentString = AntraFingerprint.USER_AGENT
            mediaPlaybackRequiresUserGesture = true
        }
        webViewClient = WebViewClient()
        loadUrl(ANTRA_URL)
    }
}

private const val ANTRA_URL = "https://antra.hoshi.cfd/"
private const val POLL_INTERVAL_MS = 800L
private val SESSION_REGEX = Regex("(?:^|;\\s*)session=([^;\\s]+)")
private val CF_CLEARANCE_REGEX = Regex("(?:^|;\\s*)cf_clearance=([^;\\s]+)")
