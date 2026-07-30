package com.youtv.app.domain.model

enum class SourceAddressType {
    IPV4,
    IPV6,
    HOSTNAME,
    UNKNOWN,
}

enum class SourceHealthStatus {
    UNKNOWN,
    SUCCESS,
    TIMEOUT,
    ERROR,
}

enum class SourcePlaybackEventType {
    ATTEMPT_STARTED,
    SUCCESS,
    TIMEOUT,
    ERROR,
    FLUCTUATION,
    FORMAT_CHANGED,
    SESSION_STATS,
}

data class StreamSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val order: Int = 0,
    val addressType: SourceAddressType = SourceAddressClassifier.classify(url),
    val healthStatus: SourceHealthStatus = SourceHealthStatus.UNKNOWN,
    val startupMs: Long? = null,
    val bitrateBps: Long? = null,
    val lastCheckedAt: Long? = null,
    val firstSeenAt: Long = 0L,
    val lastAttemptAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastFluctuationAt: Long? = null,
    val errorCount: Int = 0,
    val fluctuationCount: Int = 0,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoCodec: String = "",
    val videoTrackBitrate: Long? = null,
    val totalPlaybackMs: Long = 0L,
    val totalBufferingMs: Long = 0L,
    val sessionCount: Int = 0,
)

data class SourcePlaybackResult(
    val channelId: String,
    val sourceUrl: String,
    val sourceIndex: Int,
    val event: SourcePlaybackEventType,
    val startupMs: Long? = null,
    val bitrateBps: Long? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoCodec: String = "",
    val videoTrackBitrate: Long? = null,
    val playbackMs: Long = 0L,
    val bufferingMs: Long = 0L,
    val sessionIncrement: Int = 0,
    val checkedAt: Long = System.currentTimeMillis(),
)

data class Channel(
    val id: String,
    val name: String,
    val title: String = name,
    val group: String = "",
    val logo: String = "",
    val number: Int = -1,
    val sources: List<StreamSource> = emptyList(),
    val favorite: Boolean = false,
    val preferredSource: Int = 0,
)

data class ChannelGroup(
    val name: String,
    val channels: List<Channel>,
)

data class ImportIssue(
    val line: Int,
    val message: String,
)

data class ImportReport(
    val groups: List<ChannelGroup>,
    val imported: Int,
    val mergedSources: Int,
    val skipped: Int,
    val issues: List<ImportIssue>,
    val epgUrl: String? = null,
    val updatedAt: String? = null,
) {
    val isSuccess: Boolean get() = imported > 0
}

data class Program(
    val title: String,
    val beginTime: Int,
    val endTime: Int,
)

data class EpgGuide(
    val programs: Map<String, List<Program>> = emptyMap(),
    val logos: Map<String, String> = emptyMap(),
)

object SourceAddressClassifier {
    private val ipv4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    fun classify(url: String): SourceAddressType {
        val authority = runCatching { java.net.URI(url).host }.getOrNull()
            ?: Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\[([^]]+)]")
                .find(url)?.groupValues?.getOrNull(1)
            ?: return SourceAddressType.UNKNOWN
        return when {
            authority.contains(':') -> SourceAddressType.IPV6
            ipv4.matches(authority) -> SourceAddressType.IPV4
            authority.isNotBlank() -> SourceAddressType.HOSTNAME
            else -> SourceAddressType.UNKNOWN
        }
    }
}
