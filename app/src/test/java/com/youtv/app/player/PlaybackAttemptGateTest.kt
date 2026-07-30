package com.youtv.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAttemptGateTest {
    @Test
    fun onlyNewestAttemptIsCurrent() {
        val gate = PlaybackAttemptGate()
        val first = gate.next()
        assertTrue(gate.isCurrent(first))

        val second = gate.next()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun invalidationRejectsOutstandingAttempt() {
        val gate = PlaybackAttemptGate()
        val token = gate.next()
        gate.invalidate()
        assertFalse(gate.isCurrent(token))
    }
}
