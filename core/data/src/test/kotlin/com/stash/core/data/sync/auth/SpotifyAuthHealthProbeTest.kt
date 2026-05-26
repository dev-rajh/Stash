package com.stash.core.data.sync.auth

import com.stash.core.auth.model.UserInfo
import com.stash.data.spotify.SpotifyApiClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthHealthProbeTest {

    private val api: SpotifyApiClient = mockk()
    private val probe = SpotifyAuthHealthProbe(api)

    @Test fun `returns true when getCurrentUserProfile returns null`() = runTest {
        coEvery { api.getCurrentUserProfile() } returns null
        assertTrue(probe.isExpired())
    }

    @Test fun `returns false when getCurrentUserProfile returns a profile`() = runTest {
        coEvery { api.getCurrentUserProfile() } returns UserInfo(id = "abc", displayName = "x")
        assertFalse(probe.isExpired())
    }

    @Test fun `returns false when network throws (conservative)`() = runTest {
        coEvery { api.getCurrentUserProfile() } throws RuntimeException("network down")
        assertFalse(probe.isExpired())
    }

    @Test fun `propagates CancellationException for structured concurrency`() = runTest {
        coEvery { api.getCurrentUserProfile() } throws kotlinx.coroutines.CancellationException("cancelled")
        val thrown = runCatching { probe.isExpired() }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }
}
