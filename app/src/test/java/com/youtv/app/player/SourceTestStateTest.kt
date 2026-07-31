package com.youtv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTestStateTest {
    @Test
    fun runningStateKeepsOriginalSourceNumberOrder() {
        val results = (0 until 4).associateWith {
            SourceTestResult(
                if (it == 1) SourceTestStatus.TESTING else SourceTestStatus.PENDING,
            )
        }
        val state = SourceTestState.Running(
            channelId = "channel",
            currentIndex = 1,
            total = 4,
            results = results,
        )

        assertEquals(listOf(0, 1, 2, 3), state.results.keys.toList())
        assertEquals(SourceTestStatus.TESTING, state.results.getValue(1).status)
    }

    @Test
    fun cancelledStateRetainsFinishedResults() {
        val state = SourceTestState.Completed(
            channelId = "channel",
            total = 2,
            results = mapOf(
                0 to SourceTestResult(SourceTestStatus.AVAILABLE, startupMs = 320L),
                1 to SourceTestResult(SourceTestStatus.PENDING),
            ),
            cancelled = true,
        )

        assertTrue(state.cancelled)
        assertEquals(320L, state.results.getValue(0).startupMs)
    }
}
