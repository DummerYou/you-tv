package com.youtv.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceSelectionPolicyTest {
    @Test
    fun manualUnknownFailureFallsBackToStableFullHdBeforeRemembered720p() {
        val sources = listOf(
            source(0, height = 720, playback = 600_000, buffering = 30_000),
            source(1, height = 1080, playback = 600_000, buffering = 20_000),
            source(2, height = 1080, playback = 600_000, buffering = 5_000),
            StreamSource("https://example.com/unknown", order = 3),
        )

        val order = SourceSelectionPolicy.rankedIndices(
            sources = sources,
            preferredIndex = 0,
            forcedIndex = 3,
        )

        assertEquals(listOf(3, 2, 1, 0), order)
    }

    @Test
    fun unstable4kFallsBehindStableFullHd() {
        val sources = listOf(
            source(0, height = 2160, playback = 600_000, buffering = 60_000),
            source(1, height = 1080, playback = 600_000, buffering = 5_000),
        )
        assertEquals(listOf(1, 0), SourceSelectionPolicy.rankedIndices(sources))
    }

    @Test
    fun clarityWinsWhenStabilityIsInSameTier() {
        val sources = listOf(
            source(0, height = 1080, playback = 600_000, buffering = 5_000),
            source(1, height = 2160, playback = 600_000, buffering = 6_000),
        )

        assertEquals(listOf(1, 0), SourceSelectionPolicy.rankedIndices(sources))
    }

    @Test
    fun measuredPixelsOrderCommonVideoFormats() {
        val sources = listOf(
            sourceWithSize(0, 720, 405),
            sourceWithSize(1, 1080, 608),
            sourceWithSize(2, 1260, 709),
            sourceWithSize(3, 1280, 720),
            sourceWithSize(4, 1680, 945),
            sourceWithSize(5, 1920, 1080),
            sourceWithSize(6, 3840, 2160),
        )

        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), SourceSelectionPolicy.rankedIndices(sources))
    }

    @Test
    fun stabilityBreaksTiesWithinSameResolution() {
        val sources = listOf(
            sourceWithSize(0, 1920, 1080, buffering = 20_000),
            sourceWithSize(1, 1920, 1080, buffering = 5_000),
        )

        assertEquals(listOf(1, 0), SourceSelectionPolicy.rankedIndices(sources))
    }

    @Test
    fun recentlyFailedHdStaysBehindSuccessful720p() {
        val failedHd = source(0, height = 1080).copy(healthStatus = SourceHealthStatus.ERROR)
        val stable720 = source(1, height = 720)
        assertEquals(listOf(1, 0), SourceSelectionPolicy.rankedIndices(listOf(failedHd, stable720)))
    }

    @Test
    fun rememberedSourceIsForcedAheadOfHigherRankedFallbacks() {
        val remembered = source(0, height = 720, playback = 600_000, buffering = 60_000)
        val stable4k = source(1, height = 2160)

        assertEquals(
            listOf(0, 1),
            SourceSelectionPolicy.rankedIndices(
                sources = listOf(remembered, stable4k),
                preferredIndex = 0,
                forcedIndex = 0,
            ),
        )
    }

    @Test
    fun sourceIdentityKeepsCctv5AndCctv5PlusSeparate() {
        assertNotEquals(SourceIdentity.channelKey("CCTV-5"), SourceIdentity.channelKey("CCTV5+"))
        assertEquals(SourceIdentity.channelKey("CCTV-5"), SourceIdentity.channelKey("cctv 5"))
    }

    private fun source(
        order: Int,
        height: Int,
        playback: Long = 600_000,
        buffering: Long = 0,
    ) = StreamSource(
        url = "https://example.com/$order",
        order = order,
        healthStatus = SourceHealthStatus.SUCCESS,
        lastCheckedAt = System.currentTimeMillis(),
        videoWidth = if (height >= 2160) 3840 else if (height >= 1080) 1920 else 1280,
        videoHeight = height,
        totalPlaybackMs = playback,
        totalBufferingMs = buffering,
    )

    private fun sourceWithSize(
        order: Int,
        width: Int,
        height: Int,
        playback: Long = 600_000,
        buffering: Long = 0,
    ) = StreamSource(
        url = "https://example.com/size/$order",
        order = order,
        healthStatus = SourceHealthStatus.SUCCESS,
        lastCheckedAt = System.currentTimeMillis(),
        videoWidth = width,
        videoHeight = height,
        totalPlaybackMs = playback,
        totalBufferingMs = buffering,
    )
}
