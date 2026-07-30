package com.youtv.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgChannelLookupTest {
    @Test
    fun cctv5AndCctv5PlusRemainDistinct() {
        val cctv5 = listOf(Program("体育新闻", 1, 2))
        val cctv5Plus = listOf(Program("赛事直播", 1, 2))
        val lookup = EpgChannelLookup(
            EpgGuide(
                programs = mapOf("CCTV-5" to cctv5, "CCTV5+" to cctv5Plus),
                logos = mapOf("CCTV-5" to "five", "CCTV5+" to "plus"),
            ),
        )

        assertEquals(cctv5, lookup.programsFor("cctv 5 HD"))
        assertEquals(cctv5Plus, lookup.programsFor("CCTV 5＋"))
        assertEquals("five", lookup.logoFor("CCTV-5高清"))
        assertEquals("plus", lookup.logoFor("CCTV5+"))
    }

    @Test
    fun ambiguousFuzzyMatchReturnsNothing() {
        val lookup = EpgChannelLookup(
            EpgGuide(logos = mapOf("上海新闻" to "news", "上海新闻综合" to "general")),
        )
        assertTrue(lookup.logoFor("上海新闻综合频道").isEmpty())
    }
}
