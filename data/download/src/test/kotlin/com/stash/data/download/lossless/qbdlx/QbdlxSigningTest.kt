package com.stash.data.download.lossless.qbdlx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Signing-resolution tests for [QbdlxCredentialStore] — the multi-credential
 * (#1) and connected-account (#3) behaviour.
 *
 * The whole point: a Qobuz token only returns full FLAC when signed with the
 * app_id/secret it was minted under. These prove each token routes to the RIGHT
 * pair — the exact bug where a pool token minted under a second app_id was signed
 * with the primary secret and silently served 30-second previews.
 */
@RunWith(RobolectricTestRunner::class)
class QbdlxSigningTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private val PRIMARY_ID = "798273057"
    private val PRIMARY_SECRET = "abb21364945c0583309667d13ca3d93a"
    private val SECOND_ID = "312369995"
    private val SECOND_SECRET = "e79f8b9be485692b0e5f9dd895826368"

    private fun store(pool: String) =
        QbdlxCredentialStore(ctx, { "" }, QbdlxRemotePool { null }).also {
            it.poolRaw = pool
            it.primaryAppId = PRIMARY_ID
            it.primaryAppSecret = PRIMARY_SECRET
            it.appSecretsRaw = "$SECOND_ID:$SECOND_SECRET"
        }

    @Before
    fun setUp() {
        runBlocking { store("").clearPersistedForTest() }
    }

    @Test
    fun `a tagged pool token signs with its own app_id and secret`() = runTest {
        val s = store("tokA:US:$SECOND_ID")
        val signing = s.signingFor("tokA")
        assertThat(signing.appId).isEqualTo(SECOND_ID)
        assertThat(signing.appSecret).isEqualTo(SECOND_SECRET)
    }

    @Test
    fun `an untagged pool token signs with the primary pair`() = runTest {
        val s = store("tokB:GB")
        val signing = s.signingFor("tokB")
        assertThat(signing.appId).isEqualTo(PRIMARY_ID)
        assertThat(signing.appSecret).isEqualTo(PRIMARY_SECRET)
    }

    @Test
    fun `a tag whose app_id has no known secret falls back to the full primary pair`() = runTest {
        val s = store("tokC:US:999999999")
        val signing = s.signingFor("tokC")
        // NOT (999999999, primarySecret) — that mismatch is the preview bug.
        assertThat(signing.appId).isEqualTo(PRIMARY_ID)
        assertThat(signing.appSecret).isEqualTo(PRIMARY_SECRET)
    }

    @Test
    fun `an unknown token signs with the primary pair`() = runTest {
        val s = store("tokA:US:$SECOND_ID")
        val signing = s.signingFor("never-seen")
        assertThat(signing.appId).isEqualTo(PRIMARY_ID)
        assertThat(signing.appSecret).isEqualTo(PRIMARY_SECRET)
    }

    @Test
    fun `both app_ids in one pool each sign with their own secret`() = runTest {
        val s = store("tokA:US:$SECOND_ID,tokB:GB:$PRIMARY_ID")
        assertThat(s.signingFor("tokA").appSecret).isEqualTo(SECOND_SECRET)
        assertThat(s.signingFor("tokB").appSecret).isEqualTo(PRIMARY_SECRET)
    }

    @Test
    fun `a connected account signs with its own stored pair`() = runTest {
        val s = store("tokA:US:$SECOND_ID")
        s.setUserCredential(token = "myAccount", appId = "712109809", appSecret = "589be88e4538daea11f509d29e4a23b1")
        val signing = s.signingFor("myAccount")
        assertThat(signing.appId).isEqualTo("712109809")
        assertThat(signing.appSecret).isEqualTo("589be88e4538daea11f509d29e4a23b1")
    }

    @Test
    fun `a connected account is the active token and keeps the source alive`() = runTest {
        val s = store("tokA:US:$SECOND_ID")
        s.setUserCredential("myAccount", "712109809", "589be88e4538daea11f509d29e4a23b1")
        assertThat(s.activeToken()).isEqualTo("myAccount")   // wins over the pool
        assertThat(s.allDead()).isFalse()
    }

    @Test
    fun `disconnecting an account reverts signing and active token to the pool`() = runTest {
        val s = store("tokA:US:$SECOND_ID")
        s.setUserCredential("myAccount", "712109809", "589be88e4538daea11f509d29e4a23b1")
        s.clearUserCredential()
        assertThat(s.activeToken()).isEqualTo("tokA")
        // "myAccount" is now unknown → primary pair
        assertThat(s.signingFor("myAccount").appId).isEqualTo(PRIMARY_ID)
    }

    @Test
    fun `a connected account survives even when the whole pool is dead`() = runTest {
        val s = store("tokA:US:$SECOND_ID")
        s.markDead("tokA")
        s.setUserCredential("myAccount", "712109809", "589be88e4538daea11f509d29e4a23b1")
        assertThat(s.allDead()).isFalse()
        assertThat(s.activeToken()).isEqualTo("myAccount")
    }
}
