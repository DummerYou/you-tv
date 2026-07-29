package com.youtv.app.domain.model

import kotlin.math.ceil

enum class SourceQualityAge {
    FRESH,
    HISTORICAL,
    EXPIRED,
}

data class EffectiveSourceQuality(
    val age: SourceQualityAge,
    val healthStatus: SourceHealthStatus,
    val errorCount: Int,
    val fluctuationCount: Int,
)

object SourceQualityPolicy {
    const val FRESH_MILLIS = 7L * 24 * 60 * 60 * 1_000
    const val HALF_WEIGHT_MILLIS = 14L * 24 * 60 * 60 * 1_000
    const val HISTORY_MILLIS = 30L * 24 * 60 * 60 * 1_000
    const val CLEAR_HISTORY_MILLIS = 90L * 24 * 60 * 60 * 1_000

    fun evaluate(source: StreamSource, nowMillis: Long = System.currentTimeMillis()): EffectiveSourceQuality {
        val age = qualityAge(source.lastCheckedAt, nowMillis)
        val effectiveStatus = when {
            age == SourceQualityAge.FRESH -> source.healthStatus
            age == SourceQualityAge.HISTORICAL && source.healthStatus == SourceHealthStatus.SUCCESS ->
                SourceHealthStatus.SUCCESS
            else -> SourceHealthStatus.UNKNOWN
        }
        return EffectiveSourceQuality(
            age = age,
            healthStatus = effectiveStatus,
            errorCount = decayedCount(source.errorCount, source.lastErrorAt, nowMillis),
            fluctuationCount = decayedCount(
                source.fluctuationCount,
                source.lastFluctuationAt,
                nowMillis,
            ),
        )
    }

    fun qualityAge(checkedAt: Long?, nowMillis: Long = System.currentTimeMillis()): SourceQualityAge {
        val timestamp = checkedAt ?: return SourceQualityAge.EXPIRED
        val elapsed = (nowMillis - timestamp).coerceAtLeast(0L)
        return when {
            elapsed <= FRESH_MILLIS -> SourceQualityAge.FRESH
            elapsed <= HISTORY_MILLIS -> SourceQualityAge.HISTORICAL
            else -> SourceQualityAge.EXPIRED
        }
    }

    fun decayedCount(count: Int, eventAt: Long?, nowMillis: Long = System.currentTimeMillis()): Int {
        if (count <= 0 || eventAt == null) return 0
        val elapsed = (nowMillis - eventAt).coerceAtLeast(0L)
        val factor = when {
            elapsed <= FRESH_MILLIS -> 1.0
            elapsed <= HALF_WEIGHT_MILLIS -> 0.5
            elapsed <= HISTORY_MILLIS -> 0.25
            else -> 0.0
        }
        return ceil(count.coerceAtMost(MAX_EVENT_COUNT) * factor).toInt()
    }

    fun shouldClearHistory(
        firstSeenAt: Long,
        lastAttemptAt: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val reference = lastAttemptAt ?: firstSeenAt
        return reference > 0L && nowMillis - reference > CLEAR_HISTORY_MILLIS
    }

    const val MAX_EVENT_COUNT = 99
}
