package com.stash.core.data.lastfm

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryArtistTest {
    @Test
    fun `returns the artist unchanged when there is only one`() {
        assertEquals("Radiohead", primaryArtist("Radiohead"))
    }

    @Test
    fun `takes the first artist from a comma-joined list`() {
        assertEquals("Calvin Harris", primaryArtist("Calvin Harris, Dua Lipa"))
    }

    @Test
    fun `keeps ampersand band names intact`() {
        assertEquals("Simon & Garfunkel", primaryArtist("Simon & Garfunkel"))
    }

    @Test
    fun `keeps feat suffixes intact because only comma splits`() {
        assertEquals("Drake feat. Rihanna", primaryArtist("Drake feat. Rihanna"))
    }

    @Test
    fun `falls back to the original when the first segment is blank`() {
        assertEquals(", Someone", primaryArtist(", Someone"))
    }

    @Test
    fun `returns blank input unchanged`() {
        assertEquals("", primaryArtist(""))
    }
}
