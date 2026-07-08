package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import com.stash.data.ytmusic.model.AlbumSource
import com.stash.data.ytmusic.model.AlbumSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [QobuzDiscographyProvider]. The three collaborators
 * ([QbdlxApiClient], [QbdlxCredentialStore], [QbdlxQobuzSource]) are all final
 * Kotlin classes, so — mirroring [QbdlxQobuzSourceTest] — they are MockK'd
 * (`coEvery` for the suspend calls). The real [DiscographyMerger] and
 * [com.stash.data.download.lossless.qobuz.QobuzCandidateMatcher] run
 * end-to-end so the match gate + merge behave exactly as in production.
 *
 * The gate's job is to FAIL SAFE: on any doubt it must return the YT lists
 * UNCHANGED rather than graft a stranger's discography.
 */
class QobuzDiscographyProviderTest {

    private val apiClient: QbdlxApiClient = mockk()
    private val credentialStore: QbdlxCredentialStore = mockk()
    private val qobuzSource: QbdlxQobuzSource = mockk()

    private fun provider() = QobuzDiscographyProvider(apiClient, credentialStore, qobuzSource)

    private fun ytAlbum(title: String, year: String? = "1991") =
        AlbumSummary("yt_$title", title, "My Bloody Valentine", null, year, AlbumSource.YOUTUBE)

    private fun qAlbum(title: String, year: String? = "1991") =
        QbdlxAlbumItem(id = "qb_$title", title = title, release_date_original = year)

    /** Usable qbdlx: toggle on, live token. */
    private fun usable(token: String = "tok1") {
        coEvery { qobuzSource.isEnabledForStreaming() } returns true
        coEvery { credentialStore.activeToken() } returns token
    }

    // (a) ────────────────────────────────────────────────────────────────
    @Test
    fun `disabled source returns yt lists unchanged and never fetches`() = runTest {
        coEvery { qobuzSource.isEnabledForStreaming() } returns false
        val albums = listOf(ytAlbum("Loveless"))
        val singles = listOf(ytAlbum("Sunny Sundae Smile"))

        val out = provider().mergeInto("My Bloody Valentine", albums, singles)

        assertThat(out.albums).isEqualTo(albums)
        assertThat(out.singles).isEqualTo(singles)
        coVerify(exactly = 0) { apiClient.searchArtists(any(), any()) }
        coVerify(exactly = 0) { apiClient.getArtistAlbums(any(), any()) }
    }

    // (b) ────────────────────────────────────────────────────────────────
    @Test
    fun `no active token returns yt lists unchanged and never fetches`() = runTest {
        coEvery { qobuzSource.isEnabledForStreaming() } returns true
        coEvery { credentialStore.activeToken() } returns null
        val albums = listOf(ytAlbum("Loveless"))

        val out = provider().mergeInto("My Bloody Valentine", albums, emptyList())

        assertThat(out.albums).isEqualTo(albums)
        coVerify(exactly = 0) { apiClient.searchArtists(any(), any()) }
    }

    // (c) ────────────────────────────────────────────────────────────────
    @Test
    fun `confident single match with title overlap merges qobuz albums in`() = runTest {
        usable()
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"), qAlbum("m b v", "2013"))

        val out = provider().mergeInto(
            "My Bloody Valentine",
            listOf(ytAlbum("Loveless", "1991")),
            emptyList(),
        )

        assertThat(out.albums.map { it.title }).containsExactly("m b v", "Loveless").inOrder()
        assertThat(out.albums.map { it.source }).containsExactly(AlbumSource.QOBUZ, AlbumSource.QOBUZ)
    }

    // (d) ────────────────────────────────────────────────────────────────
    @Test
    fun `zero yt albums plus two differently-named candidates aborts homonym`() = runTest {
        usable()
        // "the doors" vs "doors": distinct normalized names, both cross threshold
        // (subset coverage → 1.0). Ambiguous → must NOT graft either discography.
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "The Doors"), QbdlxArtistItem(2, "Doors"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("L.A. Woman", "1971")) // present but must be discarded

        val out = provider().mergeInto("The Doors", emptyList(), emptyList())

        assertThat(out.albums).isEmpty() // grafted nothing → aborted
    }

    // (e) ────────────────────────────────────────────────────────────────
    @Test
    fun `various-artists candidates are excluded and treated as no match`() = runTest {
        usable()
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(9, "Various Artists"), QbdlxArtistItem(10, "Verschiedene Interpreten"))
        val albums = listOf(ytAlbum("Loveless"))

        val out = provider().mergeInto("My Bloody Valentine", albums, emptyList())

        assertThat(out.albums).isEqualTo(albums)
        coVerify(exactly = 0) { apiClient.getArtistAlbums(any(), any()) }
    }

    // (f) FLAGSHIP ─────────────────────────────────────────────────────────
    @Test
    fun `zero yt albums plus duplicate same-name rows proceeds MBV case`() = runTest {
        usable()
        // Qobuz returns the artist's OWN entity twice (same name, different id).
        // These collapse to ONE distinct artist — must NOT be killed as ambiguous.
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(QbdlxArtistItem(1, "My Bloody Valentine"), QbdlxArtistItem(2, "My Bloody Valentine"))
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"), qAlbum("m b v", "2013"))

        val out = provider().mergeInto("My Bloody Valentine", emptyList(), emptyList())

        assertThat(out.albums.map { it.title }).containsExactly("m b v", "Loveless").inOrder()
        assertThat(out.albums.map { it.source }).containsExactly(AlbumSource.QOBUZ, AlbumSource.QOBUZ)
        coVerify { apiClient.getArtistAlbums(1, any()) } // used the first/best real row
    }

    // (g) ────────────────────────────────────────────────────────────────
    @Test
    fun `superstring pseudo-artist is excluded so it can't fake ambiguity`() = runTest {
        usable()
        // "my bloody valentine tribute" strictly contains the query + extra tokens.
        // It must be dropped from the candidate set, leaving ONE real artist → proceed.
        coEvery { apiClient.searchArtists(any(), any()) } returns
            listOf(
                QbdlxArtistItem(1, "My Bloody Valentine"),
                QbdlxArtistItem(2, "My Bloody Valentine Tribute"),
            )
        coEvery { apiClient.getArtistAlbums(any(), any()) } returns
            listOf(qAlbum("Loveless", "1991"))

        val out = provider().mergeInto("My Bloody Valentine", emptyList(), emptyList())

        // Proceeded (not aborted): the pseudo did not create false ambiguity.
        assertThat(out.albums.map { it.title }).containsExactly("Loveless")
        assertThat(out.albums.single().source).isEqualTo(AlbumSource.QOBUZ)
        coVerify { apiClient.getArtistAlbums(1, any()) }
    }
}
