package com.youtv.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebufferWindowTrackerTest {
    @Test
    fun triggersOnThirdRebufferInsideWindow() {
        val tracker = RebufferWindowTracker(threshold = 3, windowMillis = 120_000L)

        assertFalse(tracker.record(0L))
        assertFalse(tracker.record(30_000L))
        assertTrue(tracker.record(90_000L))
    }

    @Test
    fun expiredRebuffersDoNotCountTowardThreshold() {
        val tracker = RebufferWindowTracker(threshold = 3, windowMillis = 120_000L)

        assertFalse(tracker.record(0L))
        assertFalse(tracker.record(60_000L))
        assertFalse(tracker.record(180_001L))
    }

    @Test
    fun triggerResetsTheWindow() {
        val tracker = RebufferWindowTracker(threshold = 3, windowMillis = 120_000L)

        tracker.record(0L)
        tracker.record(1L)
        assertTrue(tracker.record(2L))
        assertFalse(tracker.record(3L))
    }
}
