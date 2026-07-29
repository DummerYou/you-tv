package com.youtv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLogoCatalogTest {
    @Test
    fun `CCTV aliases choose the correct local logo`() {
        assertEquals("cctv_1", ChannelLogoCatalog.keyFor("CCTV-01高清"))
        assertEquals("cctv_5_plus", ChannelLogoCatalog.keyFor("CCTV5+ 体育赛事"))
        assertEquals("cctv_5", ChannelLogoCatalog.keyFor("CCTV-5体育"))
        assertEquals("cctv_4k", ChannelLogoCatalog.keyFor("CCTV-4K超高清"))
        assertEquals("cctv_8k", ChannelLogoCatalog.keyFor("CCTV8K 超高清"))
        assertEquals("cctv_4_europe", ChannelLogoCatalog.keyFor("CCTV4欧洲咪咕"))
    }

    @Test
    fun `education CGTN and satellite aliases are recognized`() {
        assertEquals("cetv_1", ChannelLogoCatalog.keyFor("CETV1 中国教育"))
        assertEquals("cetv_3", ChannelLogoCatalog.keyFor("中国教育电视台-3高清"))
        assertEquals("cgtn_russian", ChannelLogoCatalog.keyFor("CGTN俄语高清"))
        assertEquals("satellite_hunan", ChannelLogoCatalog.keyFor("湖南卫视4K50"))
        assertEquals("satellite_dongfang", ChannelLogoCatalog.keyFor("上海卫视高清"))
        assertEquals("satellite_hainan", ChannelLogoCatalog.keyFor("旅游卫视"))
    }

    @Test
    fun `unknown channels stay available for network logo fallback`() {
        assertNull(ChannelLogoCatalog.keyFor("测试频道"))
        assertNull(ChannelLogoCatalog.keyFor("CCTV20241"))
    }
}
