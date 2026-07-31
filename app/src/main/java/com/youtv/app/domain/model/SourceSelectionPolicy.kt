package com.youtv.app.domain.model

data class SourceRank(
    val healthTier: Int,
    val stabilityTier: Int,
    val resolutionPixelsRank: Long,
    val stabilityScore: Double,
    val errorCount: Int,
    val frameRateRank: Float,
    val startupMs: Long,
    val bitrateRank: Long,
    val preferredRank: Int,
    val originalOrder: Int,
)

object SourceSelectionPolicy {
    private const val STABILITY_PLAYBACK_PRIOR_MS = 5L * 60 * 1_000
    private const val STABILITY_BUFFER_PRIOR_MS = 5_000L

    fun rankedIndices(
        sources: List<StreamSource>,
        preferredIndex: Int? = null,
        forcedIndex: Int? = null,
        excludedIndices: Set<Int> = emptySet(),
    ): List<Int> {
        val available = sources.indices.filterNot(excludedIndices::contains)
        val forced = forcedIndex?.takeIf { it in available }
        val ranks = available.associateWith { rank(sources[it], it == preferredIndex) }
        val ranked = available.filterNot { it == forced }.sortedWith(comparator(ranks))
        return listOfNotNull(forced) + ranked
    }

    fun rank(source: StreamSource, preferred: Boolean = false): SourceRank {
        val quality = SourceQualityPolicy.evaluate(source)
        return SourceRank(
            healthTier = when (quality.healthStatus) {
                SourceHealthStatus.SUCCESS -> 0
                SourceHealthStatus.UNKNOWN -> 1
                SourceHealthStatus.TIMEOUT, SourceHealthStatus.ERROR -> 2
            },
            stabilityTier = stabilityTier(source),
            resolutionPixelsRank = resolutionPixelsRank(source),
            stabilityScore = stabilityScore(source),
            errorCount = quality.errorCount,
            frameRateRank = -(source.videoFrameRate ?: 0f),
            startupMs = source.startupMs ?: Long.MAX_VALUE,
            bitrateRank = -(source.bitrateBps ?: 0L),
            preferredRank = if (preferred) 0 else 1,
            originalOrder = source.order,
        )
    }

    fun stabilityScore(source: StreamSource): Double =
        (source.totalBufferingMs + STABILITY_BUFFER_PRIOR_MS).toDouble() /
            (source.totalPlaybackMs + STABILITY_PLAYBACK_PRIOR_MS).coerceAtLeast(1L)

    private fun comparator(ranks: Map<Int, SourceRank>): Comparator<Int> =
        compareBy<Int> { ranks.getValue(it).healthTier }
            .thenBy { ranks.getValue(it).stabilityTier }
            .thenBy { ranks.getValue(it).resolutionPixelsRank }
            .thenBy { ranks.getValue(it).frameRateRank }
            .thenBy { ranks.getValue(it).startupMs }
            .thenBy { ranks.getValue(it).bitrateRank }
            .thenBy { ranks.getValue(it).stabilityScore }
            .thenBy { ranks.getValue(it).errorCount }
            .thenBy { ranks.getValue(it).preferredRank }
            .thenBy { ranks.getValue(it).originalOrder }

    private fun stabilityTier(source: StreamSource): Int {
        val score = stabilityScore(source)
        return when {
            score <= EXCELLENT_STABILITY_SCORE -> 0
            score <= ACCEPTABLE_STABILITY_SCORE -> 1
            else -> 2
        }
    }

    private fun resolutionPixelsRank(source: StreamSource): Long {
        val width = source.videoWidth?.takeIf { it > 0 } ?: return Long.MAX_VALUE
        val height = source.videoHeight?.takeIf { it > 0 } ?: return Long.MAX_VALUE
        return -(width.toLong() * height.toLong())
    }

    private const val EXCELLENT_STABILITY_SCORE = 0.01
    private const val ACCEPTABLE_STABILITY_SCORE = 0.03
}
