package com.youtv.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlaybackResultTest {
    @Test
    fun normalPlaybackUpdatesRememberedSourceByDefault() {
        val result = SourcePlaybackResult(
            channelId = "channel",
            sourceUrl = "https://example.com/live.m3u8",
            sourceIndex = 0,
            event = SourcePlaybackEventType.SUCCESS,
        )

        assertTrue(result.updateRememberedSource)
    }

    @Test
    fun sourceTestCanPersistQualityWithoutUpdatingRememberedSource() {
        val result = SourcePlaybackResult(
            channelId = "channel",
            sourceUrl = "https://example.com/live.m3u8",
            sourceIndex = 0,
            event = SourcePlaybackEventType.SUCCESS,
            updateRememberedSource = false,
        )

        assertFalse(result.updateRememberedSource)
    }
}
