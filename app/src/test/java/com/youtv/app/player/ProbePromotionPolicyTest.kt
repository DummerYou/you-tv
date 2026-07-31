package com.youtv.app.player

import com.youtv.app.domain.model.SourceHealthStatus
import com.youtv.app.domain.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbePromotionPolicyTest {
    @Test
    fun probeCanOnlyPromoteCandidatesInBestHealthAndStabilityBand() {
        val sources = listOf(
            source(0, bufferingMs = 60_000L),
            source(1, bufferingMs = 0L),
            source(2, bufferingMs = 5_000L),
        )

        assertEquals(
            listOf(1),
            ProbePromotionPolicy.candidatesInBestBand(
                sources = sources,
                indices = listOf(0, 1, 2),
                preferredIndex = 0,
                isCoolingDown = { false },
            ),
        )
    }

    @Test
    fun coolingCandidateIsExcludedFromProbePromotion() {
        val sources = listOf(source(0), source(1))

        assertEquals(
            listOf(1),
            ProbePromotionPolicy.candidatesInBestBand(
                sources = sources,
                indices = listOf(0, 1),
                preferredIndex = 0,
                isCoolingDown = { it == 0 },
            ),
        )
    }

    private fun source(index: Int, bufferingMs: Long = 0L) = StreamSource(
        url = "https://example.com/$index",
        healthStatus = SourceHealthStatus.SUCCESS,
        lastCheckedAt = System.currentTimeMillis(),
        videoWidth = 1920,
        videoHeight = 1080,
        totalPlaybackMs = 600_000L,
        totalBufferingMs = bufferingMs,
    )
}
