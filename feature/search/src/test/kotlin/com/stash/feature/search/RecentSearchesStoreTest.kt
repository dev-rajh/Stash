package com.stash.feature.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DataStore-backed unit tests for [RecentSearchesStore] (Robolectric +
 * ApplicationProvider + a real temp DataStore). The preferencesDataStore
 * delegate is a single per-process instance, so persisted state leaks between
 * tests unless wiped — clear it in @Before. Mirrors QbdlxCredentialStoreTest.
 */
@RunWith(RobolectricTestRunner::class)
class RecentSearchesStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun store() = RecentSearchesStore(ctx)

    @Before
    fun clear() = runBlocking { store().clear() }

    @Test
    fun `record prepends most-recent-first`() = runTest {
        val s = store(); s.record("beatles"); s.record("stones")
        assertThat(s.recent.first()).containsExactly("stones", "beatles").inOrder()
    }

    @Test
    fun `record dedupes case-insensitively and moves to front`() = runTest {
        val s = store(); s.record("Beatles"); s.record("stones"); s.record("BEATLES")
        assertThat(s.recent.first()).containsExactly("BEATLES", "stones").inOrder()
    }

    @Test
    fun `caps at 10 dropping oldest`() = runTest {
        val s = store(); (1..12).forEach { s.record("q$it") }
        val r = s.recent.first()
        assertThat(r).hasSize(10)
        assertThat(r.first()).isEqualTo("q12")
        assertThat(r).doesNotContain("q1")
        assertThat(r).doesNotContain("q2")
    }

    @Test
    fun `blank is ignored`() = runTest {
        val s = store(); s.record("   "); s.record("")
        assertThat(s.recent.first()).isEmpty()
    }

    @Test
    fun `remove drops one entry`() = runTest {
        val s = store(); s.record("a"); s.record("b"); s.remove("a")
        assertThat(s.recent.first()).containsExactly("b")
    }

    @Test
    fun `clear empties`() = runTest {
        val s = store(); s.record("a"); s.clear()
        assertThat(s.recent.first()).isEmpty()
    }
}
