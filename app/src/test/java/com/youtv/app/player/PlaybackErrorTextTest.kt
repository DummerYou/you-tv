package com.youtv.app.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorTextTest {
    @Test
    fun networkErrorsUseShortChineseMessages() {
        assertEquals(
            "网络连接失败",
            PlaybackErrorText.from(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            "网络连接超时",
            PlaybackErrorText.from(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
    }

    @Test
    fun decoderAndUnknownErrorsRemainReadable() {
        assertEquals(
            "设备无法解码该视频",
            PlaybackErrorText.from(PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        assertEquals("播放源暂时不可用", PlaybackErrorText.from(Int.MAX_VALUE))
    }
}
