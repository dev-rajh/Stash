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
import org.robolectric.annotation.Config

/**
 * Runtime pool refresh — the fix for "a shipped build's tokens rot".
 *
 * Qobuz tokens rotate roughly monthly and the pool is baked into the APK at
 * build time, so three separate releases have gone 100% dead in the wild with no
 * recovery short of shipping again. The store now re-fetches the shared pool
 * when every token it holds is dead.
 *
 * The rules being pinned here are as much about what it must NOT do: no fetching
 * on the happy path, no unbounded retries, and never let a failing endpoint make
 * things worse than not having one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QbdlxPoolRefreshTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = runBlocking {
        QbdlxCredentialStore(ctx, { "" }, QbdlxRemotePool { null }).clearPersistedForTest()
    }

    private fun store(pool: String, remote: QbdlxRemotePool) =
        QbdlxCredentialStore(ctx, { "" }, remote).also { it.poolRaw = pool }

    @Test fun `a fully dead pool is replaced by the freshly fetched one`() = runTest {
        var fetches = 0
        val s = store("dead:FR", QbdlxRemotePool { fetches++; "fresh:FR" })
        s.markDead("dead")

        assertThat(s.activeToken()).isEqualTo("fresh")
        assertThat(fetches).isEqualTo(1)
    }

    @Test fun `a live pool never triggers a fetch`() = runTest {
        // The happy path must cost nothing — no network on every resolve.
        var fetches = 0
        val s = store("live:FR", QbdlxRemotePool { fetches++; "other:GB" })

        assertThat(s.activeToken()).isEqualTo("live")
        assertThat(fetches).isEqualTo(0)
    }

    @Test fun `a failing endpoint leaves the existing pool alone`() = runTest {
        // An unreachable webhook must never be worse than not having one.
        val s = store("dead:FR", QbdlxRemotePool { null })
        s.markDead("dead")

        assertThat(s.activeToken()).isNull()   // still dead, but nothing corrupted
        s.recordAlive("dead")
        assertThat(s.activeToken()).isEqualTo("dead")
    }

    @Test fun `refresh attempts are rate limited while the pool stays dead`() = runTest {
        var fetches = 0
        val s = store("dead:FR", QbdlxRemotePool { fetches++; null })
        var now = 1_000_000L
        s.clock = { now }
        s.markDead("dead")

        repeat(5) { s.activeToken() }
        assertThat(fetches).isEqualTo(1)

        // Still inside the window — no second call.
        now += QbdlxCredentialStore.REFRESH_MIN_INTERVAL_MS - 1
        s.markDead("dead")
        s.activeToken()
        assertThat(fetches).isEqualTo(1)

        // Window elapsed — allowed to try again.
        now += 2
        s.markDead("dead")
        s.activeToken()
        assertThat(fetches).isEqualTo(2)
    }

    @Test fun `a refreshed pool survives a restart via the cache`() = runTest {
        val s = store("dead:FR", QbdlxRemotePool { "fresh:FR" })
        s.markDead("dead")
        assertThat(s.activeToken()).isEqualTo("fresh")

        // New instance = new process. The bundled pool is the stale one again,
        // but the cache should win, with no second fetch needed.
        var fetches = 0
        val restarted = QbdlxCredentialStore(ctx, { "dead:FR" }, QbdlxRemotePool { fetches++; null })
        assertThat(restarted.activeToken()).isEqualTo("fresh")
        assertThat(fetches).isEqualTo(0)
    }

    @Test fun `a dead pool stops reporting allDead once refreshed`() = runTest {
        // allDead() gates the source off AND drives the "paste a token" badge, so
        // it has to recover on its own too — not just activeToken().
        val s = store("dead:FR", QbdlxRemotePool { "fresh:FR" })
        s.markDead("dead")

        assertThat(s.allDead()).isFalse()
    }

    // ---- pool parsing (pure, no network) ----

    @Test fun `parsePool keeps only rows for our app_id and dedupes`() {
        val body = """
            [
              {"token":"t1","country":"FR","app_id":"798273057"},
              {"token":"t1","country":"FR","app_id":"798273057"},
              {"token":"t2","country":"GB","app_id":"798273057"},
              {"token":"other","country":"US","app_id":"312369995"},
              {"token":"","country":"NO","app_id":"798273057"},
              {"token":"t3","country":"","app_id":"798273057"}
            ]
        """.trimIndent()

        val pool = HttpQbdlxRemotePool.parsePool(body, appId = "798273057")

        // Other-app tokens are dropped on purpose: they need that app's own
        // secret to sign, which this build does not bundle.
        assertThat(pool).isEqualTo("t1:FR,t2:GB")
    }

    @Test fun `parsePool returns empty for junk rather than throwing`() {
        assertThat(HttpQbdlxRemotePool.parsePool("not json", appId = "798273057")).isEmpty()
        assertThat(HttpQbdlxRemotePool.parsePool("{}", appId = "798273057")).isEmpty()
        assertThat(HttpQbdlxRemotePool.parsePool("[]", appId = "798273057")).isEmpty()
    }
}
