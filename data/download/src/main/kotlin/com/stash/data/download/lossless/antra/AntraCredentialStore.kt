package com.stash.data.download.lossless.antra

import com.stash.data.download.lossless.LosslessSourcePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the credentials Stash uses to authenticate against
 * antra.hoshi.cfd's lossless download endpoint.
 *
 * antra gates downloads behind two cookies the user obtains in a browser:
 *
 *  - `session`      — issued after logging in to the antra site.
 *  - `cf_clearance` — issued after passing Cloudflare's challenge.
 *
 * Both must be present for a request to authenticate, so [isConnected]
 * is true only when each cookie is non-blank. The persistence itself
 * lives on [LosslessSourcePreferences] (the shared DataStore-backed prefs
 * the squid captcha cookie also uses), so credentials survive process
 * restarts; this class is the thin domain wrapper that derives the
 * "connected?" state and the ready-to-send Cookie header from them.
 *
 * Nothing calls this yet — the antra source/auth flow lands in later
 * tasks. This is the credential store only.
 */
@Singleton
class AntraCredentialStore @Inject constructor(
    private val prefs: LosslessSourcePreferences,
) {

    /** True only when BOTH antra cookies are present (non-blank). */
    suspend fun isConnected(): Boolean {
        val session = prefs.antraSessionCookieNow()
        val cfClearance = prefs.antraCfClearanceNow()
        return !session.isNullOrBlank() && !cfClearance.isNullOrBlank()
    }

    /**
     * The `Cookie` header value for an authenticated antra request, in the
     * form `session=<s>; cf_clearance=<c>`. Returns null when not
     * [isConnected] (so callers fall through cleanly rather than sending a
     * half-authenticated request).
     */
    suspend fun cookieHeader(): String? {
        val session = prefs.antraSessionCookieNow()?.takeIf { it.isNotBlank() } ?: return null
        val cfClearance = prefs.antraCfClearanceNow()?.takeIf { it.isNotBlank() } ?: return null
        return "$SESSION_COOKIE=$session; $CF_CLEARANCE_COOKIE=$cfClearance"
    }

    /** The connected antra account's username, for Settings display. */
    suspend fun username(): String? = prefs.antraUsernameNow()

    /** Persists the login `session` + `cf_clearance` cookies and username. */
    suspend fun save(session: String, cfClearance: String, username: String?) {
        prefs.setAntraCredentials(session = session, cfClearance = cfClearance, username = username)
    }

    /**
     * Clears the connection — call when the session is detected stale
     * (e.g. a 401/403 from antra). Afterwards [isConnected] is false and
     * [cookieHeader] returns null until the user re-authenticates.
     */
    suspend fun markStale() {
        prefs.clearAntraCredentials()
    }

    private companion object {
        const val SESSION_COOKIE = "session"
        const val CF_CLEARANCE_COOKIE = "cf_clearance"
    }
}
