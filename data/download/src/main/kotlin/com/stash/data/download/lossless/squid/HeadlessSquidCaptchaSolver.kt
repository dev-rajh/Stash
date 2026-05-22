package com.stash.data.download.lossless.squid

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spins up an offscreen [WebView], navigates to squid.wtf, lets the
 * site's own ALTCHA solver run, and harvests the `captcha_verified_at`
 * cookie. Returns the cookie value on success or null on timeout/failure.
 *
 * Must be called from the main thread — WebView APIs require it. The
 * suspend function handles the dispatcher switch internally.
 *
 * **Manual-verification note (Task 13):** if loading the page alone
 * doesn't trigger the ALTCHA challenge (the visible flow needs a
 * Download click to provoke it), [solve] needs JS injection to click
 * the Download button programmatically. Iterate during manual smoke
 * test; once the working JS is known, paste it into the body below
 * the [WebView.loadUrl] call.
 */
@Singleton
class HeadlessSquidCaptchaSolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun solve(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? = withContext(Dispatchers.Main) {
        Log.d(TAG, "solve start (timeoutMs=$timeoutMs)")
        val webView = buildWebView()
        try {
            withTimeoutOrNull(timeoutMs) {
                webView.loadUrl(SQUID_WTF_URL)
                pollForCookie()
            }.also { result ->
                if (result == null) {
                    Log.w(TAG, "solve timeout after ${timeoutMs}ms")
                }
            }
        } finally {
            webView.cleanup()
        }
    }

    private suspend fun pollForCookie(): String? {
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (!coroutineContext.isActive) return null
            val cookies = CookieManager.getInstance().getCookie(SQUID_WTF_URL)
            val match = COOKIE_REGEX.find(cookies.orEmpty())
            if (match != null) {
                val cookie = match.groupValues[1]
                Log.d(TAG, "solve success (cookie len=${cookie.length})")
                return cookie
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(context).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
            }
            webViewClient = WebViewClient()
        }
    }

    private fun WebView.cleanup() {
        stopLoading()
        loadUrl("about:blank")
        clearHistory()
        destroy()
    }

    private companion object {
        const val TAG = "HeadlessSquidSolver"
        const val SQUID_WTF_URL = "https://qobuz.squid.wtf/"
        const val POLL_INTERVAL_MS = 500L
        const val DEFAULT_TIMEOUT_MS = 30_000L
        val COOKIE_REGEX = Regex("captcha_verified_at=([^;\\s]+)")
    }
}
