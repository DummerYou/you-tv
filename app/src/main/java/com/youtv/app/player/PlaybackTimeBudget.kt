package com.youtv.app.player

internal object PlaybackTimeBudget {
    fun remaining(startedAtMs: Long, nowMs: Long, totalBudgetMs: Long): Long =
        (totalBudgetMs - (nowMs - startedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L)
}
