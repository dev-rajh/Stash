package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AmzApiClient] against a [MockWebServer] emulating the
 * amz.squid.wtf proxy. The shared-client captcha interceptor is not
 * exercised here (it's a separate concern bound on the production
 * OkHttpClient) — a bare client is injected.
 */
class AmzApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AmzApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = AmzApiClient(OkHttpClient()).apply {
            baseUrl = server.url("/api").toString().removeSuffix("/")
        }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `search parses captured trackList and sends query plus content_type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_JSON))

        val items = client.search("Kanye West Can't Tell Me Nothing")

        assertThat(items).hasSize(1)
        assertThat(items[0].asin).isEqualTo("B07NHH5X4P")
        assertThat(items[0].primaryArtistName).isEqualTo("Kanye West")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).endsWith("/search")
        val body = request.body.readUtf8()
        assertThat(body).contains("Kanye West Can't Tell Me Nothing")
        assertThat(body).contains("\"content_type\":\"TRACK\"")
    }

    @Test fun `track returns parsed metadata`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(TRACK_JSON))

        val meta = client.track("B07K7VJXVG")

        assertThat(meta).isNotNull()
        assertThat(meta!!.asin).isEqualTo("B07K7VJXVG")
        assertThat(meta.isrc).isEqualTo("USUM71807761")
        assertThat(meta.isExplicit).isTrue()

        val request = server.takeRequest()
        assertThat(request.path).endsWith("/track")
        assertThat(request.body.readUtf8()).contains("\"asin\":\"B07K7VJXVG\"")
    }

    @Test fun `search returns empty list on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertThat(client.search("anything")).isEmpty()
    }

    @Test fun `search throws AmzRateLimitedException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        assertThrows(AmzRateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking { client.search("anything") }
        }
    }

    @Test fun `track throws AmzRateLimitedException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        assertThrows(AmzRateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking { client.track("B0") }
        }
    }

    @Test fun `streamUrl returns the exact expected string`() {
        client.baseUrl = "https://amz.squid.wtf/api"
        assertThat(client.streamUrl("B07K7VJXVG"))
            .isEqualTo("https://amz.squid.wtf/api/stream?asin=B07K7VJXVG&country=US&tier=best")
    }

    companion object {
        private const val SEARCH_JSON = """
        {"trackList":[{"asin":"B07NHH5X4P","title":"Can't Tell Me Nothing [Explicit]",
        "primaryArtistName":"Kanye West","artistName":"Kanye West","albumArtistName":"Kanye West",
        "album":{"title":"","image":"https://m.media-amazon.com/images/I/710XWtyJ+8L.jpg"}}]}
        """

        private const val TRACK_JSON = """
        {"metadata":{"asin":"B07K7VJXVG","title":"Ghost Town [feat. PARTYNEXTDOOR] [Explicit]",
        "artist":"Kanye West feat. PARTYNEXTDOOR","album":"ye [Explicit]","album_asin":"B07K7WZSQZ",
        "album_artist":"Kanye West feat. PARTYNEXTDOOR",
        "cover":"https://m.media-amazon.com/images/I/714yVKHQM-L.SX1200_QL90.jpg",
        "cover_cdn":"https://m.media-amazon.com/images/I/714yVKHQM-L.SX1200_QL90.jpg",
        "isrc":"USUM71807761","is_explicit":true,"lyrics":{"synced":"[00:00.00]intro"}}}
        """
    }
}
