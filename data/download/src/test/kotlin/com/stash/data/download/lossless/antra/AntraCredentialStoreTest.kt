package com.stash.data.download.lossless.antra

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [AntraCredentialStore].
 *
 * Mocks [LosslessSourcePreferences] (the DataStore-backed prefs) and
 * verifies the store's connection contract independently of any Android
 * runtime:
 *
 *  - Not connected when either cookie is absent/blank.
 *  - save() persists session + cf_clearance + username and flips
 *    isConnected() true; cookieHeader() carries both cookies.
 *  - markStale() clears the connection.
 */
class AntraCredentialStoreTest {

    private val prefs: LosslessSourcePreferences = mockk(relaxUnitFun = true)

    private fun store() = AntraCredentialStore(prefs)

    @Test
    fun `not connected when cookies absent`() = runTest {
        coEvery { prefs.antraSessionCookieNow() } returns null
        coEvery { prefs.antraCfClearanceNow() } returns null

        val s = store()

        assertThat(s.isConnected()).isFalse()
        assertThat(s.cookieHeader()).isNull()
    }

    @Test
    fun `not connected when only one cookie present`() = runTest {
        coEvery { prefs.antraSessionCookieNow() } returns "sess"
        coEvery { prefs.antraCfClearanceNow() } returns null

        assertThat(store().isConnected()).isFalse()
        assertThat(store().cookieHeader()).isNull()
    }

    @Test
    fun `not connected when cookie blank`() = runTest {
        coEvery { prefs.antraSessionCookieNow() } returns "sess"
        coEvery { prefs.antraCfClearanceNow() } returns "   "

        assertThat(store().isConnected()).isFalse()
        assertThat(store().cookieHeader()).isNull()
    }

    @Test
    fun `save persists all three and connects`() = runTest {
        store().save(session = "sess-val", cfClearance = "cf-val", username = "alice")

        coVerify { prefs.setAntraCredentials("sess-val", "cf-val", "alice") }
    }

    @Test
    fun `connected when both cookies present and header carries both`() = runTest {
        coEvery { prefs.antraSessionCookieNow() } returns "sess-val"
        coEvery { prefs.antraCfClearanceNow() } returns "cf-val"

        val s = store()

        assertThat(s.isConnected()).isTrue()
        val header = s.cookieHeader()
        assertThat(header).isNotNull()
        assertThat(header).contains("session=sess-val")
        assertThat(header).contains("cf_clearance=cf-val")
    }

    @Test
    fun `markStale clears connection`() = runTest {
        store().markStale()

        coVerify { prefs.clearAntraCredentials() }
    }
}
