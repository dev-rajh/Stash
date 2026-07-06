package com.stash.feature.nowplaying.ui

import com.stash.data.lyrics.parser.LrcLine

/**
 * What the live-lyrics bar renders for a given [LyricsViewState]:
 *  - [Live]   synced lyrics — the current line, accent-colored, advancing
 *             with playback.
 *  - [Static] plain-only lyrics — a dim "View lyrics ♪" tap target (the
 *             ~15% of lyric hits with no timing data).
 *  - [Hidden] nothing to offer (no lyrics / instrumental / loading / error)
 *             — the bar row is absent entirely; it animates in when a fetch
 *             lands with lyrics.
 */
internal sealed interface LiveBarMode {
    data class Live(val lines: List<LrcLine>) : LiveBarMode
    data object Static : LiveBarMode
    data object Hidden : LiveBarMode
}

internal fun liveBarModeFor(state: LyricsViewState): LiveBarMode = when (state) {
    is LyricsViewState.Synced -> LiveBarMode.Live(state.lines)
    is LyricsViewState.Plain -> LiveBarMode.Static
    else -> LiveBarMode.Hidden
}
