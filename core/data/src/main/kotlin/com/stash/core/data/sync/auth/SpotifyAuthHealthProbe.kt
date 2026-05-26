package com.stash.core.data.sync.auth

import android.util.Log
import com.stash.data.spotify.SpotifyApiClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAuthHealthProbe @Inject constructor(
    private val api: SpotifyApiClient,
) : AuthHealthProbe {
    override val source: AuthSource = AuthSource.SPOTIFY

    override suspend fun isExpired(): Boolean = try {
        api.getCurrentUserProfile() == null
    } catch (e: Throwable) {
        Log.w("SpotifyAuthProbe", "probe failed conservatively", e)
        false
    }
}
