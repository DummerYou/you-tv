package com.youtv.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.youtv.app.data.db.ChannelDao
import com.youtv.app.data.db.ChannelEntity
import com.youtv.app.data.db.ChannelGroupEntity
import com.youtv.app.data.db.BlockedSourceEntity
import com.youtv.app.data.db.PlaybackLogEntity
import com.youtv.app.data.db.SourceQualityEntity
import com.youtv.app.data.db.StreamSourceEntity
import com.youtv.app.domain.model.BlockedSource
import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.ChannelGroup
import com.youtv.app.domain.model.ImportReport
import com.youtv.app.domain.model.PlaybackLog
import com.youtv.app.domain.model.SourceAddressType
import com.youtv.app.domain.model.SourceHealthStatus
import com.youtv.app.domain.model.SourcePlaybackResult
import com.youtv.app.domain.model.SourcePlaybackEventType
import com.youtv.app.domain.model.SourceIdentity
import com.youtv.app.domain.model.SourceQualityPolicy
import com.youtv.app.domain.model.StreamSource
import com.youtv.app.domain.playlist.PlaylistFormat
import com.youtv.app.domain.playlist.PlaylistParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ChannelRepository(
    private val dao: ChannelDao,
    private val parser: PlaylistParser,
    private val gson: Gson,
    private val legacyFavoriteIndexes: Set<Int>,
) {
    fun observeGroups(): Flow<List<ChannelGroup>> = combine(
        dao.observeGroups(),
        dao.observeBlockedSources(),
    ) { groups, blockedSources ->
        val blocked = blockedSources.map { it.channelKey to it.sourceFingerprint }.toSet()
        groups.map { relation ->
            ChannelGroup(
                name = relation.group.name,
                channels = relation.channels.sortedBy { it.channel.sortOrder }.map { item ->
                    val sources = item.sources.sortedBy { it.sortOrder }.mapNotNull { source ->
                        val headers = gson.fromJson<Map<String, String>>(
                            source.headersJson,
                            object : TypeToken<Map<String, String>>() {}.type,
                        )
                        val stream = StreamSource(
                            url = source.url,
                            headers = headers,
                            order = source.sortOrder,
                            addressType = runCatching {
                                SourceAddressType.valueOf(source.addressType)
                            }.getOrDefault(SourceAddressType.UNKNOWN),
                        )
                        stream.takeUnless {
                            (SourceIdentity.channelKey(item.channel.name) to SourceIdentity.fingerprint(it)) in blocked
                        }
                    }
                    val preferredSource = sources.indexOfFirst {
                        SourceIdentity.fingerprint(it) == item.channel.lastSuccessfulSourceUrl ||
                            it.url == item.channel.lastSuccessfulSourceUrl
                    }.takeIf { it >= 0 } ?: 0
                    Channel(
                        id = item.channel.id,
                        name = item.channel.name,
                        title = item.channel.title,
                        group = item.channel.groupName,
                        logo = item.channel.logo,
                        number = item.channel.number,
                        favorite = item.channel.favorite,
                        preferredSource = preferredSource,
                        sources = sources,
                    )
                },
            )
        }
    }

    fun observeSourceQuality(channelId: String): Flow<Map<String, SourceQualityEntity>> =
        dao.observeSourceQuality(channelId).map { values -> values.associateBy { it.sourceUrl } }

    fun observeBlockedSources(): Flow<List<BlockedSource>> = dao.observeBlockedSources().map { values ->
        values.map { it.toModel() }
    }

    fun applyQuality(channel: Channel, quality: Map<String, SourceQualityEntity>): Channel =
        channel.copy(sources = channel.sources.map { source ->
            (quality[SourceIdentity.fingerprint(source)] ?: quality[source.url])
                ?.let { source.withQuality(it) } ?: source
        })

    suspend fun importPlaylist(
        content: String,
        format: PlaylistFormat = PlaylistFormat.AUTO,
        migrateLegacyFavorites: Boolean = false,
    ): ImportReport {
        val report = parser.parse(content, format)
        if (!report.isSuccess) return report
        val previous = dao.channelsSnapshot().associateBy { it.id }
        val previousSources = dao.sourcesSnapshot().groupBy { it.channelId }
        val previousQualities = dao.qualitiesSnapshot().groupBy { it.channelId }
        val importedAt = System.currentTimeMillis()
        var legacyIndex = 0
        val groupEntities = mutableListOf<ChannelGroupEntity>()
        val channelEntities = mutableListOf<ChannelEntity>()
        val sourceEntities = mutableListOf<StreamSourceEntity>()
        val qualityEntities = mutableListOf<SourceQualityEntity>()
        report.groups.forEachIndexed { groupIndex, group ->
            groupEntities += ChannelGroupEntity(group.name, groupIndex)
            group.channels.forEachIndexed { channelIndex, channel ->
                val previousChannel = previous[channel.id]
                val oldSources = previousSources[channel.id].orEmpty()
                val oldQualities = previousQualities[channel.id].orEmpty()
                val rememberedKey = previousChannel?.lastSuccessfulSourceUrl
                    ?.takeIf(String::isNotBlank)
                    ?: oldSources.firstOrNull {
                        it.sortOrder == previousChannel?.lastSuccessfulSource
                    }?.url
                val preferredSource = channel.sources.indexOfFirst {
                    SourceIdentity.fingerprint(it) == rememberedKey || it.url == rememberedKey
                }
                    .takeIf { it >= 0 }
                    ?: 0
                channelEntities += ChannelEntity(
                    id = channel.id,
                    name = channel.name,
                    title = channel.title,
                    groupName = group.name,
                    logo = channel.logo,
                    number = channel.number,
                    sortOrder = channelIndex,
                    favorite = previous[channel.id]?.favorite
                        ?: (migrateLegacyFavorites && legacyIndex in legacyFavoriteIndexes),
                    lastSuccessfulSource = preferredSource,
                    lastSuccessfulSourceUrl = channel.sources.getOrNull(preferredSource)
                        ?.let(SourceIdentity::fingerprint).orEmpty(),
                )
                channel.sources.forEach { source ->
                    val previousSource = oldSources.firstOrNull { it.url == source.url }
                    val sourceKey = SourceIdentity.fingerprint(source)
                    val previousQuality = oldQualities.firstOrNull { it.sourceUrl == sourceKey }
                        ?: oldQualities.firstOrNull { it.sourceUrl == source.url }
                    val firstSeenAt = previousQuality?.firstSeenAt?.takeIf { it > 0L }
                        ?: previousSource?.firstSeenAt?.takeIf { it > 0L }
                        ?: importedAt
                    val clearHistory = (previousQuality != null || previousSource != null) && SourceQualityPolicy.shouldClearHistory(
                        firstSeenAt = firstSeenAt,
                        lastAttemptAt = previousQuality?.lastAttemptAt ?: previousSource?.lastAttemptAt,
                        nowMillis = importedAt,
                    )
                    sourceEntities += StreamSourceEntity(
                        channelId = channel.id,
                        sortOrder = source.order,
                        url = source.url,
                        headersJson = gson.toJson(source.headers),
                        addressType = source.addressType.name,
                    )
                    qualityEntities += if (clearHistory) {
                        SourceQualityEntity(channel.id, sourceKey, firstSeenAt = firstSeenAt)
                    } else {
                        previousQuality?.copy(channelId = channel.id, sourceUrl = sourceKey)
                            ?: SourceQualityEntity(
                                channelId = channel.id,
                                sourceUrl = sourceKey,
                                healthStatus = previousSource?.healthStatus ?: SourceHealthStatus.UNKNOWN.name,
                                startupMs = previousSource?.startupMs,
                                bitrateBps = previousSource?.bitrateBps,
                                lastCheckedAt = previousSource?.lastCheckedAt,
                                firstSeenAt = firstSeenAt,
                                lastAttemptAt = previousSource?.lastAttemptAt,
                                lastErrorAt = previousSource?.lastErrorAt,
                                lastFluctuationAt = previousSource?.lastFluctuationAt,
                                errorCount = previousSource?.errorCount ?: 0,
                                fluctuationCount = previousSource?.fluctuationCount ?: 0,
                                videoWidth = previousSource?.videoWidth,
                                videoHeight = previousSource?.videoHeight,
                                videoFrameRate = previousSource?.videoFrameRate,
                                videoCodec = previousSource?.videoCodec.orEmpty(),
                                videoTrackBitrate = previousSource?.videoTrackBitrate,
                                formatCheckedAt = previousSource?.lastCheckedAt,
                            )
                    }
                }
                legacyIndex++
            }
        }
        dao.replaceAll(groupEntities, channelEntities, sourceEntities, qualityEntities)
        return report
    }

    suspend fun isEmpty(): Boolean = dao.channelCount() == 0

    suspend fun setFavorite(channelId: String, favorite: Boolean) = dao.setFavorite(channelId, favorite)

    suspend fun blockSource(channel: Channel, source: StreamSource) {
        val now = System.currentTimeMillis()
        val channelName = channel.title.ifBlank { channel.name }
        dao.insertBlockedSource(
            BlockedSourceEntity(
                channelKey = SourceIdentity.channelKey(channel.name),
                sourceFingerprint = SourceIdentity.fingerprint(source),
                channelId = channel.id,
                channelName = channelName,
                sourceNumber = source.order + 1,
                sourceUrl = source.url,
                videoWidth = source.videoWidth,
                videoHeight = source.videoHeight,
                blockedAt = now,
            ),
        )
        insertPlaybackLog(
            PlaybackLogEntity(
                occurredAt = now,
                event = "MANUAL_BLOCK",
                channelId = channel.id,
                channelName = channelName,
                sourceNumber = source.order + 1,
                sourceUrl = source.url,
                reasonCode = "manual_block",
                reason = "用户手动屏蔽播放源",
                videoWidth = source.videoWidth,
                videoHeight = source.videoHeight,
            ),
        )
    }

    suspend fun restoreBlockedSource(source: BlockedSource) {
        dao.deleteBlockedSource(source.channelKey, source.sourceFingerprint)
        insertPlaybackLog(
            PlaybackLogEntity(
                occurredAt = System.currentTimeMillis(),
                event = "MANUAL_RESTORE",
                channelId = source.channelId,
                channelName = source.channelName,
                sourceNumber = source.sourceNumber,
                sourceUrl = source.sourceUrl,
                reasonCode = "manual_restore",
                reason = "用户恢复已屏蔽播放源",
                videoWidth = source.videoWidth,
                videoHeight = source.videoHeight,
            ),
        )
    }

    suspend fun clearBlockedSources() {
        val blocked = dao.blockedSourcesSnapshot()
        dao.clearBlockedSources()
        blocked.forEach { source ->
            insertPlaybackLog(
                PlaybackLogEntity(
                    occurredAt = System.currentTimeMillis(),
                    event = "MANUAL_RESTORE",
                    channelId = source.channelId,
                    channelName = source.channelName,
                    sourceNumber = source.sourceNumber,
                    sourceUrl = source.sourceUrl,
                    reasonCode = "manual_restore_all",
                    reason = "用户恢复全部已屏蔽播放源",
                    videoWidth = source.videoWidth,
                    videoHeight = source.videoHeight,
                ),
            )
        }
    }

    suspend fun playbackLogs(limit: Int = PLAYBACK_LOG_MAX_ROWS): List<PlaybackLog> =
        dao.playbackLogs(limit.coerceIn(1, PLAYBACK_LOG_MAX_ROWS)).map { it.toModel() }

    suspend fun rememberSourceResult(result: SourcePlaybackResult) {
        recordPlaybackLog(result)
        dao.migrateLegacyQualityKey(result.channelId, result.sourceUrl, result.sourceKey)
        dao.insertQualityIfMissing(
            SourceQualityEntity(
                channelId = result.channelId,
                sourceUrl = result.sourceKey,
                firstSeenAt = result.checkedAt,
            ),
        )
        if (result.event == SourcePlaybackEventType.ATTEMPT_STARTED) {
            dao.markSourceAttempted(
                channelId = result.channelId,
                sourceUrl = result.sourceKey,
                attemptedAt = result.checkedAt,
            )
            return
        }
        if (result.event == SourcePlaybackEventType.FLUCTUATION) {
            dao.incrementSourceFluctuation(
                channelId = result.channelId,
                sourceUrl = result.sourceKey,
                checkedAt = result.checkedAt,
            )
            return
        }
        if (result.event == SourcePlaybackEventType.FORMAT_CHANGED) {
            dao.setSourceFormat(
                channelId = result.channelId,
                sourceUrl = result.sourceKey,
                videoWidth = result.videoWidth,
                videoHeight = result.videoHeight,
                videoFrameRate = result.videoFrameRate,
                videoCodec = result.videoCodec,
                videoTrackBitrate = result.videoTrackBitrate,
                checkedAt = result.checkedAt,
            )
            return
        }
        if (result.event == SourcePlaybackEventType.SESSION_STATS) {
            dao.addSourceSessionStats(
                channelId = result.channelId,
                sourceUrl = result.sourceKey,
                playbackMs = result.playbackMs,
                bufferingMs = result.bufferingMs,
                sessionIncrement = result.sessionIncrement,
            )
            return
        }
        if (result.event == SourcePlaybackEventType.AUTO_SWITCH) return
        val status = when (result.event) {
            SourcePlaybackEventType.SUCCESS -> SourceHealthStatus.SUCCESS
            SourcePlaybackEventType.TIMEOUT -> SourceHealthStatus.TIMEOUT
            SourcePlaybackEventType.ERROR -> SourceHealthStatus.ERROR
            SourcePlaybackEventType.ATTEMPT_STARTED -> error("handled above")
            SourcePlaybackEventType.FLUCTUATION -> error("handled above")
            SourcePlaybackEventType.FORMAT_CHANGED -> error("handled above")
            SourcePlaybackEventType.SESSION_STATS -> error("handled above")
            SourcePlaybackEventType.AUTO_SWITCH -> error("handled above")
        }
        dao.setSourceHealth(
            channelId = result.channelId,
            sourceUrl = result.sourceKey,
            status = status.name,
            startupMs = result.startupMs,
            bitrateBps = result.bitrateBps,
            checkedAt = result.checkedAt,
            errorIncrement = if (status == SourceHealthStatus.SUCCESS) 0 else 1,
            videoWidth = result.videoWidth,
            videoHeight = result.videoHeight,
            videoFrameRate = result.videoFrameRate,
            videoCodec = result.videoCodec,
            videoTrackBitrate = result.videoTrackBitrate,
        )
        if (status == SourceHealthStatus.SUCCESS && result.updateRememberedSource) {
            dao.setLastSuccessfulSource(result.channelId, result.sourceIndex, result.sourceKey)
        }
    }

    private suspend fun recordPlaybackLog(result: SourcePlaybackResult) {
        if (result.event in setOf(
                SourcePlaybackEventType.FORMAT_CHANGED,
                SourcePlaybackEventType.SESSION_STATS,
            )
        ) return
        val event = when (result.event) {
            SourcePlaybackEventType.ATTEMPT_STARTED -> "ATTEMPT"
            SourcePlaybackEventType.SUCCESS -> "SUCCESS"
            SourcePlaybackEventType.TIMEOUT -> "TIMEOUT"
            SourcePlaybackEventType.ERROR -> "ERROR"
            SourcePlaybackEventType.FLUCTUATION -> "BUFFERING"
            SourcePlaybackEventType.AUTO_SWITCH -> "AUTO_SWITCH"
            SourcePlaybackEventType.FORMAT_CHANGED,
            SourcePlaybackEventType.SESSION_STATS -> return
        }
        val defaultReason = when (result.event) {
            SourcePlaybackEventType.ATTEMPT_STARTED -> "开始尝试播放源"
            SourcePlaybackEventType.SUCCESS -> "已渲染首个视频画面"
            SourcePlaybackEventType.TIMEOUT -> "播放源响应超时"
            SourcePlaybackEventType.ERROR -> "播放源不可用"
            SourcePlaybackEventType.FLUCTUATION -> "播放发生缓冲"
            SourcePlaybackEventType.AUTO_SWITCH -> "重复缓冲，自动切换下一源"
            SourcePlaybackEventType.FORMAT_CHANGED,
            SourcePlaybackEventType.SESSION_STATS -> ""
        }
        insertPlaybackLog(
            PlaybackLogEntity(
                occurredAt = result.checkedAt,
                event = event,
                channelId = result.channelId,
                channelName = result.channelName,
                sourceNumber = result.sourceIndex + 1,
                sourceUrl = result.sourceUrl,
                reasonCode = result.reasonCode.ifBlank { result.event.name.lowercase() },
                reason = result.reason.ifBlank { defaultReason },
                errorCode = result.errorCode,
                startupMs = result.startupMs,
                playbackMs = result.playbackMs,
                bufferingMs = result.bufferingMs,
                videoWidth = result.videoWidth,
                videoHeight = result.videoHeight,
                videoFrameRate = result.videoFrameRate,
                videoCodec = result.videoCodec,
                videoTrackBitrate = result.videoTrackBitrate,
            ),
        )
    }

    private suspend fun insertPlaybackLog(log: PlaybackLogEntity) {
        dao.insertPlaybackLogAndPrune(
            log = log,
            cutoff = System.currentTimeMillis() - PLAYBACK_LOG_RETENTION_MILLIS,
            maxRows = PLAYBACK_LOG_MAX_ROWS,
        )
    }

    private fun StreamSource.withQuality(quality: SourceQualityEntity): StreamSource = copy(
        healthStatus = runCatching { SourceHealthStatus.valueOf(quality.healthStatus) }
            .getOrDefault(SourceHealthStatus.UNKNOWN),
        startupMs = quality.startupMs,
        bitrateBps = quality.bitrateBps,
        lastCheckedAt = quality.lastCheckedAt,
        firstSeenAt = quality.firstSeenAt,
        lastAttemptAt = quality.lastAttemptAt,
        lastErrorAt = quality.lastErrorAt,
        lastFluctuationAt = quality.lastFluctuationAt,
        errorCount = quality.errorCount,
        fluctuationCount = quality.fluctuationCount,
        videoWidth = quality.videoWidth,
        videoHeight = quality.videoHeight,
        videoFrameRate = quality.videoFrameRate,
        videoCodec = quality.videoCodec,
        videoTrackBitrate = quality.videoTrackBitrate,
        formatCheckedAt = quality.formatCheckedAt,
        totalPlaybackMs = quality.totalPlaybackMs,
        totalBufferingMs = quality.totalBufferingMs,
        sessionCount = quality.sessionCount,
    )

    private fun BlockedSourceEntity.toModel(): BlockedSource = BlockedSource(
        channelKey = channelKey,
        sourceFingerprint = sourceFingerprint,
        channelId = channelId,
        channelName = channelName,
        sourceNumber = sourceNumber,
        sourceUrl = sourceUrl,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        blockedAt = blockedAt,
    )

    private fun PlaybackLogEntity.toModel(): PlaybackLog = PlaybackLog(
        id = id,
        occurredAt = occurredAt,
        event = event,
        channelId = channelId,
        channelName = channelName,
        sourceNumber = sourceNumber,
        sourceUrl = sourceUrl,
        reasonCode = reasonCode,
        reason = reason,
        errorCode = errorCode,
        startupMs = startupMs,
        playbackMs = playbackMs,
        bufferingMs = bufferingMs,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        videoFrameRate = videoFrameRate,
        videoCodec = videoCodec,
        videoTrackBitrate = videoTrackBitrate,
    )

    private companion object {
        const val PLAYBACK_LOG_MAX_ROWS = 2_000
        const val PLAYBACK_LOG_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}
