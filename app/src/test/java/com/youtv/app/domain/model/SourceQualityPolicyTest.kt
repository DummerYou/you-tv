package com.youtv.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceQualityPolicyTest {
    private val now = 200L * DAY

    @Test
    fun `quality remains fresh for seven days`() {
        val source = source(lastCheckedAt = now - 7L * DAY)

        val quality = SourceQualityPolicy.evaluate(source, now)

        assertEquals(SourceQualityAge.FRESH, quality.age)
        assertEquals(SourceHealthStatus.SUCCESS, quality.healthStatus)
        assertEquals(8, quality.errorCount)
    }

    @Test
    fun `historical success remains useful but failures need recheck`() {
        val halfWeight = SourceQualityPolicy.evaluate(source(lastCheckedAt = now - 10L * DAY), now)
        val success = SourceQualityPolicy.evaluate(source(lastCheckedAt = now - 20L * DAY), now)
        val failure = SourceQualityPolicy.evaluate(
            source(
                healthStatus = SourceHealthStatus.ERROR,
                lastCheckedAt = now - 20L * DAY,
            ),
            now,
        )

        assertEquals(SourceQualityAge.HISTORICAL, success.age)
        assertEquals(SourceHealthStatus.SUCCESS, success.healthStatus)
        assertEquals(SourceHealthStatus.UNKNOWN, failure.healthStatus)
        assertEquals(4, halfWeight.errorCount)
        assertEquals(2, success.errorCount)
    }

    @Test
    fun `quality and counters expire after thirty days`() {
        val boundary = SourceQualityPolicy.evaluate(source(lastCheckedAt = now - 30L * DAY), now)
        val quality = SourceQualityPolicy.evaluate(source(lastCheckedAt = now - 31L * DAY), now)

        assertEquals(SourceQualityAge.HISTORICAL, boundary.age)
        assertEquals(2, boundary.errorCount)
        assertEquals(SourceQualityAge.EXPIRED, quality.age)
        assertEquals(SourceHealthStatus.UNKNOWN, quality.healthStatus)
        assertEquals(0, quality.errorCount)
        assertEquals(0, quality.fluctuationCount)
    }

    @Test
    fun `history is cleared only after ninety days without an attempt`() {
        assertFalse(
            SourceQualityPolicy.shouldClearHistory(
                firstSeenAt = now - 200L * DAY,
                lastAttemptAt = now - 90L * DAY,
                nowMillis = now,
            ),
        )
        assertTrue(
            SourceQualityPolicy.shouldClearHistory(
                firstSeenAt = now - 200L * DAY,
                lastAttemptAt = now - 91L * DAY,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `format age is independent from recent health check`() {
        val source = source(lastCheckedAt = now).copy(formatCheckedAt = now - 31L * DAY)
        assertEquals(SourceQualityAge.FRESH, SourceQualityPolicy.evaluate(source, now).age)
        assertEquals(
            SourceQualityAge.EXPIRED,
            SourceQualityPolicy.qualityAge(source.formatCheckedAt, now),
        )
    }

    private fun source(
        healthStatus: SourceHealthStatus = SourceHealthStatus.SUCCESS,
        lastCheckedAt: Long,
    ) = StreamSource(
        url = "http://127.0.0.1/live",
        healthStatus = healthStatus,
        lastCheckedAt = lastCheckedAt,
        errorCount = 8,
        fluctuationCount = 4,
        lastErrorAt = lastCheckedAt,
        lastFluctuationAt = lastCheckedAt,
    )

    private companion object {
        const val DAY = 24L * 60 * 60 * 1_000
    }
}
