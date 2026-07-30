package com.youtv.app.domain.model

data class SourceRank(
    val healthTier: Int,
    val resolutionTier: Int,
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
        val ranked = available.filterNot { it == forced }.sortedWith(comparator(sources, preferredIndex))
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
            resolutionTier = resolutionTier(source, quality.healthStatus),
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

    private fun comparator(sources: List<StreamSource>, preferredIndex: Int?): Comparator<Int> =
        compareBy<Int> { rank(sources[it], it == preferredIndex).healthTier }
            .thenBy { rank(sources[it], it == preferredIndex).resolutionTier }
            .thenBy { rank(sources[it], it == preferredIndex).stabilityScore }
            .thenBy { rank(sources[it], it == preferredIndex).errorCount }
            .thenBy { rank(sources[it], it == preferredIndex).frameRateRank }
            .thenBy { rank(sources[it], it == preferredIndex).startupMs }
            .thenBy { rank(sources[it], it == preferredIndex).bitrateRank }
            .thenBy { rank(sources[it], it == preferredIndex).preferredRank }
            .thenBy { rank(sources[it], it == preferredIndex).originalOrder }

    private fun resolutionTier(source: StreamSource, status: SourceHealthStatus): Int {
        if (status != SourceHealthStatus.SUCCESS) return 0
        val height = source.videoHeight ?: return 2
        return when {
            height >= 1080 -> 0
            height >= 720 -> 1
            else -> 2
        }
    }
}
