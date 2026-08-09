package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Process-lifetime health signal for the qbdlx (direct Qobuz) source, fed by
 * [StreamSourceRegistry] as it works through the resolver chain.
 *
 * Drives exactly one thing: the Home "connect ARCOD" rescue banner. When the
 * shared token pool dies (as it did at scale in 2026-08 — community reports
 * of days without FLAC), every user WITHOUT a connected ARCOD account
 * silently loses lossless: the chain falls through to YouTube and the only
 * fix — connecting the second source — is buried in Audio & Quality. This
 * signal lets Home offer that fix at the moment it matters.
 *
 * "Down" means a streak of consecutive resolves where qbdlx produced
 * nothing. A single miss is routinely a catalog gap; [QBDLX_DOWN_THRESHOLD]
 * misses in a row across arbitrary tracks is the pool being dead.
 * In-memory only, deliberately: a fresh process re-detects within a few
 * tracks, and persisting an "outage" flag risks a stale banner outliving
 * the outage.
 */
// ponytail: counts registry-level misses, so it can't tell "dead token" from
// "obscure library full of catalog gaps". Refine inside QbdlxStreamResolver
// (403-vs-no_match) if false positives ever matter — the banner is
// dismissible and its advice (connect the free second source) is sound
// either way.
@Singleton
class LosslessSourceHealth @Inject constructor() {

    private val consecutiveQbdlxMisses = MutableStateFlow(0)

    /** True while the last [QBDLX_DOWN_THRESHOLD]+ qbdlx attempts all produced nothing. */
    val qbdlxLooksDown: Flow<Boolean> = consecutiveQbdlxMisses
        .map { it >= QBDLX_DOWN_THRESHOLD }
        .distinctUntilChanged()

    fun recordQbdlxServed() {
        consecutiveQbdlxMisses.value = 0
    }

    fun recordQbdlxMiss() {
        consecutiveQbdlxMisses.update { it + 1 }
    }

    companion object {
        const val QBDLX_DOWN_THRESHOLD = 6
    }
}
