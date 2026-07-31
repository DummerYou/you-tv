package com.youtv.app.player

import com.youtv.app.domain.model.SourceSelectionPolicy
import com.youtv.app.domain.model.StreamSource

internal object ProbePromotionPolicy {
    fun candidatesInBestBand(
        sources: List<StreamSource>,
        indices: List<Int>,
        preferredIndex: Int,
        isCoolingDown: (Int) -> Boolean,
    ): List<Int> {
        val eligible = indices.filterNot(isCoolingDown)
        if (eligible.isEmpty()) return emptyList()
        val ranks = eligible.associateWith { index ->
            SourceSelectionPolicy.rank(
                source = sources[index],
                preferred = index == preferredIndex,
            )
        }
        val bestHealthTier = eligible.minOf { ranks.getValue(it).healthTier }
        val bestStabilityTier = eligible
            .filter { ranks.getValue(it).healthTier == bestHealthTier }
            .minOf { ranks.getValue(it).stabilityTier }
        return eligible.filter { index ->
            ranks.getValue(index).healthTier == bestHealthTier &&
                ranks.getValue(index).stabilityTier == bestStabilityTier
        }
    }
}
