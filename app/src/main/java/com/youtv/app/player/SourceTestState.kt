package com.youtv.app.player

enum class SourceTestStatus {
    PENDING,
    TESTING,
    AVAILABLE,
    FLUCTUATING,
    TIMEOUT,
    FAILED,
}

data class SourceTestResult(
    val status: SourceTestStatus,
    val startupMs: Long? = null,
    val bitrateBps: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoCodec: String = "",
)

sealed interface SourceTestState {
    data object Idle : SourceTestState

    data class Running(
        val channelId: String,
        val currentIndex: Int,
        val total: Int,
        val results: Map<Int, SourceTestResult>,
    ) : SourceTestState

    data class Completed(
        val channelId: String,
        val total: Int,
        val results: Map<Int, SourceTestResult>,
        val cancelled: Boolean,
    ) : SourceTestState
}
