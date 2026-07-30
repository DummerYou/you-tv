package com.youtv.app.player

import com.youtv.app.domain.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAttemptLedgerTest {
    @Test
    fun qualitySnapshotKeepsRemainingOrderAndDoesNotRetryAttemptedSource() {
        val oldSources = listOf(source("a"), source("b"), source("c"))
        val refreshed = listOf(source("c"), source("a"), source("b"), source("d"))
        val ledger = PlaybackAttemptLedger()
        ledger.markAttempted(oldSources[0])
        ledger.markAttempted(oldSources[1])

        val remaining = ledger.remapRemaining(
            previousSources = oldSources,
            previousRemainingIndices = listOf(2),
            currentSources = refreshed,
            rankedCurrentIndices = listOf(1, 2, 0, 3),
        )

        assertEquals(listOf(0, 3), remaining)
        assertTrue(ledger.hasAttempted(refreshed[1]))
        assertTrue(ledger.hasAttempted(refreshed[2]))
        assertFalse(ledger.hasAttempted(refreshed[0]))
    }

    @Test
    fun sameUrlWithDifferentHeadersIsADifferentAttempt() {
        val ledger = PlaybackAttemptLedger()
        val first = StreamSource("https://example.com/live", headers = mapOf("Token" to "a"))
        val second = StreamSource("https://example.com/live", headers = mapOf("Token" to "b"))
        ledger.markAttempted(first)
        assertFalse(ledger.hasAttempted(second))
    }

    private fun source(name: String) = StreamSource("https://example.com/$name")
}
