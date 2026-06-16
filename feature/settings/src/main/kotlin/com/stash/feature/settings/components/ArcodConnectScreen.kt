package com.stash.feature.settings.components

import android.annotation.SuppressLint
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hosts a [WebView] pointed at `arcod.xyz` so the user can sign in with
 * Google (Supabase OAuth) without leaving the app, then harvests the
 * resulting Supabase session straight out of the page's `localStorage`.
 *
 * ARCOD is a single-page app whose Supabase client stashes the logged-in
 * session under a fixed `localStorage` key once the OAuth round-trip
 * completes. There is no cookie and no redirect we can intercept, so the
 * only reliable signal is to poll `localStorage` from JS and read the
 * value back. `domStorageEnabled` is therefore mandatory — Android
 * WebViews ship with DOM storage OFF, and without it `getItem` returns
 * null forever and the harvest never fires.
 *
 * On success the parsed `access_token` / `refresh_token` /
 * `expires_at` (epoch SECONDS in the payload → milliseconds here) are
 * forwarded to [onConnected]; the caller persists them via
 * `ArcodCredentialStore` and the ARCOD source/interceptor pick them up.
 */
@Composable
fun ArcodConnectScreen(
    onConnected: (accessToken: String, refreshToken: String, expiresAtMs: Long) -> Unit,
    onClose: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var statusText by remember {
        mutableStateOf("Sign in with Google to connect ARCOD. We'll detect it automatically.")
    }
    var captured by remember { mutableStateOf(false) }

    // ── Session polling ─────────────────────────────────────────────────
    // Supabase writes the session to localStorage after the OAuth round-
    // trip; there's no event we can hook from native code, so we poll the
    // key every second. evaluateJavascript hands the value back as a
    // JS-string-literal (quoted + escaped), or the literal "null".
    LaunchedEffect(Unit) {
        while (isActive && !captured) {
            delay(POLL_INTERVAL_MS)
            val wv = webViewRef ?: continue
            val raw = wv.readLocalStorage(SUPABASE_AUTH_TOKEN_KEY)
            val session = raw?.let(::parseSupabaseSession) ?: continue
            captured = true
            statusText = "Connected — saving and closing."
            onConnected(session.accessToken, session.refreshToken, session.expiresAtMs)
            // Brief beat so the success text is visible before the pop.
            delay(400)
            onClose()
        }
    }

    // ── Back-press handling ────────────────────────────────────────────
    // Walk the WebView's own history first (the Google login flow is
    // several pages deep) and only exit the screen once it can't go back.
    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onClose()
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────
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
                    text = "Connect ARCOD",
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
                factory = { context -> buildArcodWebView(context).also { webViewRef = it } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Suspending wrapper over the async [WebView.evaluateJavascript] callback. */
private suspend fun WebView.readLocalStorage(key: String): String? =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript("localStorage.getItem('$key')") { result ->
            cont.resume(result)
        }
    }

@SuppressLint("SetJavaScriptEnabled")
private fun buildArcodWebView(context: android.content.Context): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true   // Supabase SPA + Google OAuth need JS
            domStorageEnabled = true   // REQUIRED: the session lives in localStorage
            mediaPlaybackRequiresUserGesture = true
            // Google's OAuth page rejects the bare Android WebView UA with a
            // "this browser may not be secure" wall. Present a stock mobile
            // Chrome UA so the login flow completes.
            userAgentString = MOBILE_CHROME_UA
        }
        webViewClient = WebViewClient()
        loadUrl(ARCOD_URL)
    }

/**
 * The localStorage value comes back from [WebView.evaluateJavascript] as a
 * JS string literal: a double-quoted, backslash-escaped rendering of the
 * Supabase session JSON (or the bare token "null" when the key is absent).
 *
 * We don't need a full JSON parser for three well-known fields, and pulling
 * kotlinx-serialization-json onto this module just for this isn't worth it —
 * so we unescape the literal, then regex the three values out of the inner
 * JSON. Returns null if the key is absent or any field is missing.
 */
private fun parseSupabaseSession(evalResult: String): ArcodHarvestedSession? {
    if (evalResult.isBlank() || evalResult == "null") return null

    // evaluateJavascript double-quotes + escapes the value. Strip the outer
    // quotes and unescape so we're left with the raw inner JSON.
    val inner = evalResult
        .removeSurrounding("\"")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\/", "/")
    if (inner == "null" || inner.isBlank()) return null

    val accessToken = ACCESS_TOKEN_RE.find(inner)?.groupValues?.get(1)
        ?: return null
    val refreshToken = REFRESH_TOKEN_RE.find(inner)?.groupValues?.get(1)
        ?: return null
    val expiresAtSeconds = EXPIRES_AT_RE.find(inner)?.groupValues?.get(1)
        ?.toLongOrNull() ?: return null

    return ArcodHarvestedSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMs = expiresAtSeconds * 1000L,
    )
}

private data class ArcodHarvestedSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

// `"field":"value"` — capture the (unescaped) string value.
private val ACCESS_TOKEN_RE = Regex("\"access_token\"\\s*:\\s*\"([^\"]*)\"")
private val REFRESH_TOKEN_RE = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]*)\"")

// `"expires_at":1234567890` — capture the integer epoch-seconds value.
private val EXPIRES_AT_RE = Regex("\"expires_at\"\\s*:\\s*(\\d+)")

private const val ARCOD_URL = "https://arcod.xyz/"
private const val SUPABASE_AUTH_TOKEN_KEY = "sb-fnlghyzwyoklfqyhqlav-auth-token"
private const val POLL_INTERVAL_MS = 1_000L
private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 6 Pro) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
