package com.youtv.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeBudgetTest {
    @Test
    fun formatFallbackUsesOnlyRemainingUrlBudget() {
        assertEquals(1_000L, PlaybackTimeBudget.remaining(10_000L, 14_000L, 5_000L))
        assertEquals(0L, PlaybackTimeBudget.remaining(10_000L, 16_000L, 5_000L))
    }
}
