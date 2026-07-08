package com.stash.data.download.lossless.qbdlx

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.AlbumSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscographyMergerTest {
    private fun yt(t: String, y: String?) = AlbumSummary("yt_$t", t, "MBV", null, y, AlbumSource.YOUTUBE)
    private fun qb(t: String, y: String?) = AlbumSummary("qb_$t", t, "MBV", null, y, AlbumSource.QOBUZ)

    @Test fun `qobuz wins a title collision when year within 1`() {
        val out = DiscographyMerger.mergeLane(listOf(yt("Loveless","1991")), listOf(qb("Loveless","1991")))
        assertEquals(1, out.size); assertEquals(AlbumSource.QOBUZ, out[0].source)
    }
    @Test fun `yt-only album survives`() {
        val out = DiscographyMerger.mergeLane(listOf(yt("Live at X","2010")), listOf(qb("Loveless","1991")))
        assertEquals(setOf("Live at X","Loveless"), out.map { it.title }.toSet())
    }
    @Test fun `collision kept as two when secondary signal disagrees`() {
        val out = DiscographyMerger.mergeLane(listOf(yt("Greatest Hits","1990")), listOf(qb("Greatest Hits","2020")))
        assertEquals(2, out.size)
    }
    @Test fun `ordering is newest-first by earliest known year`() {
        val out = DiscographyMerger.mergeLane(
            listOf(yt("Loveless","1991"), yt("m b v","2013")),
            listOf(qb("Loveless","2021")),
        )
        assertEquals(listOf("m b v","Loveless"), out.map { it.title }) // 2013 before 1991
    }
    @Test fun `null years sort last`() {
        val out = DiscographyMerger.mergeLane(listOf(yt("Unknown",null), yt("m b v","2013")), emptyList())
        assertEquals(listOf("m b v","Unknown"), out.map { it.title })
    }
}
