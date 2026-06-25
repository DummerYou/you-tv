package com.youtv.app.domain.playlist

import com.youtv.app.domain.model.SourceAddressType
import com.youtv.app.domain.model.SourceAddressClassifier
import com.youtv.app.data.PlaylistTextDecoder
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParserTest {
    private val parser = PlaylistParser()

    @Test
    fun `Guovin txt preserves IPv6 and merges repeated channels`() {
        val ipv6 = "http://[2409:8087:1a01:df::7005]:80/ottrrs/PLTV/index.m3u8"
        val report = parser.parse(
            """
            央视频道,#genre#
            CCTV-1,http://222.223.41.27:8888/hls/1/index.m3u8
            CCTV-5+,$ipv6
            CCTV-13,https://example.com/first.m3u8
            CCTV-13,https://example.com/second.m3u8?token=redacted
            """.trimIndent()
        )

        assertTrue(report.isSuccess)
        assertEquals(3, report.imported)
        assertEquals(1, report.mergedSources)
        val cctv5 = report.groups.single().channels.first { it.name == "CCTV-5+" }
        assertEquals(ipv6, cctv5.sources.single().url)
        assertEquals(SourceAddressType.IPV6, cctv5.sources.single().addressType)
        assertEquals(2, report.groups.single().channels.first { it.name == "CCTV-13" }.sources.size)
    }

    @Test
    fun `M3U preserves headers and IPv6 literal`() {
        val report = parser.parse(
            """
            #EXTM3U x-tvg-url="https://example.com/epg.xml"
            #EXTINF:-1 tvg-name="CCTV-17" group-title="央视频道",CCTV-17
            #EXTVLCOPT:http-user-agent=MyTV
            http://[2409:8087:2001:20:2800:0:df6e:eb23]/live/index.m3u8?token=redacted
            """.trimIndent()
        )

        assertEquals("https://example.com/epg.xml", report.epgUrl)
        val source = report.groups.single().channels.single().sources.single()
        assertEquals("MyTV", source.headers["user-agent"])
        assertEquals(SourceAddressType.IPV6, source.addressType)
    }

    @Test
    fun `JSON keeps headers and skips malformed items`() {
        val report = parser.parse(
            """[
              {"group":"卫视","name":"浙江卫视","title":"浙江卫视","uris":["https://example.com/live.m3u8"],"headers":{"user-agent":"TV"}},
              {"group":"卫视","name":"无效频道","uris":[]}
            ]"""
        )

        assertEquals(1, report.imported)
        assertEquals(1, report.skipped)
        assertEquals("TV", report.groups.single().channels.single().sources.single().headers["user-agent"])
    }

    @Test
    fun `invalid line does not discard valid channels`() {
        val report = parser.parse("央视频道,#genre#\n坏数据\nCCTV-1,http://127.0.0.1/live.m3u8")
        assertEquals(1, report.imported)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `txt update metadata and empty result groups are hidden`() {
        val report = parser.parse(
            """
            🕘️更新时间,#genre#
            2026-06-22 10:53:07,http://127.0.0.1/update.m3u8

            📺央视频道,#genre#
            CCTV-1,http://127.0.0.1/live.m3u8

            🈳无结果频道,#genre#
            CETV-1,url
            """.trimIndent()
        )

        assertTrue(report.isSuccess)
        assertEquals("2026-06-22 10:53:07", report.updatedAt)
        assertEquals(1, report.imported)
        assertEquals(listOf("📺央视频道"), report.groups.map { it.name })
    }

    @Test
    fun `playlist decoder falls back to GB18030`() {
        val bytes = "央视频道,#genre#".toByteArray(Charset.forName("GB18030"))
        assertEquals("央视频道,#genre#", PlaylistTextDecoder.decode(bytes))
    }

    @Test
    fun `source address classifier recognizes common source types`() {
        assertEquals(SourceAddressType.IPV4, SourceAddressClassifier.classify("http://127.0.0.1/live.m3u8"))
        assertEquals(SourceAddressType.IPV6, SourceAddressClassifier.classify("http://[2409:8087::1]/live.m3u8"))
        assertEquals(SourceAddressType.HOSTNAME, SourceAddressClassifier.classify("https://example.com/live.m3u8"))
    }
}
