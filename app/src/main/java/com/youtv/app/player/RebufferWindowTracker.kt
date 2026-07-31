package com.youtv.app.player

import java.util.ArrayDeque

internal class RebufferWindowTracker(
    private val threshold: Int,
    private val windowMillis: Long,
) {
    private val timestamps = ArrayDeque<Long>()

    fun record(nowMillis: Long): Boolean {
        while (timestamps.isNotEmpty() && nowMillis - timestamps.first() > windowMillis) {
            timestamps.removeFirst()
        }
        timestamps.addLast(nowMillis)
        if (timestamps.size < threshold) return false
        timestamps.clear()
        return true
    }

    fun reset() {
        timestamps.clear()
    }
}
