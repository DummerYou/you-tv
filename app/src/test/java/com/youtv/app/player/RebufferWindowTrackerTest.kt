package com.youtv.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebufferWindowTrackerTest {
    @Test
    fun triggersOnSecondRebufferInsideOneMinute() {
        val tracker = RebufferWindowTracker(threshold = 2, windowMillis = 60_000L)

        assertFalse(tracker.record(0L))
        assertTrue(tracker.record(60_000L))
    }

    @Test
    fun expiredRebuffersDoNotCountTowardThreshold() {
        val tracker = RebufferWindowTracker(threshold = 2, windowMillis = 60_000L)

        assertFalse(tracker.record(0L))
        assertFalse(tracker.record(60_001L))
    }

    @Test
    fun triggerResetsTheWindow() {
        val tracker = RebufferWindowTracker(threshold = 2, windowMillis = 60_000L)

        tracker.record(0L)
        assertTrue(tracker.record(1L))
        assertFalse(tracker.record(2L))
    }
}
