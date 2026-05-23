package com.stash.data.lyrics.source

import kotlinx.serialization.Serializable

@Serializable
internal data class LrclibGetResponse(
    val id: Long,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Int? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
