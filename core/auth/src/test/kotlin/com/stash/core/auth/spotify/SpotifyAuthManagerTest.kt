package com.stash.core.auth.spotify

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpotifyAuthManagerTest {

    @Test
    fun `getClientVersion caches fallback after scrape miss`() {
        val requestCount = AtomicInteger(0)
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "<html><body>No client version here</body></html>"
                            .toResponseBody("text/html".toMediaType()),
                    )
                    .build()
            })
            .build()

        val manager = SpotifyAuthManager(okHttpClient)

        assertEquals(SpotifyAuthConfig.CLIENT_VERSION_FALLBACK, manager.getClientVersion())
        assertEquals(SpotifyAuthConfig.CLIENT_VERSION_FALLBACK, manager.getClientVersion())
        assertEquals(1, requestCount.get())
    }
}
