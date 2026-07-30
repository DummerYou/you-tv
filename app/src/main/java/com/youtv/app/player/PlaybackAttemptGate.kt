package com.youtv.app.player

internal class PlaybackAttemptGate {
    private var generation = 0L

    fun next(): Long = ++generation

    fun invalidate() {
        generation++
    }

    fun isCurrent(token: Long): Boolean = token == generation
}
