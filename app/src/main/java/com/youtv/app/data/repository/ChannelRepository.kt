package com.youtv.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.youtv.app.data.db.ChannelDao
import com.youtv.app.data.db.ChannelEntity
import com.youtv.app.data.db.ChannelGroupEntity
import com.youtv.app.data.db.StreamSourceEntity
import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.ChannelGroup
import com.youtv.app.domain.model.ImportReport
import com.youtv.app.domain.model.SourceAddressType
import com.youtv.app.domain.model.SourceHealthStatus
import com.youtv.app.domain.model.SourcePlaybackResult
import com.youtv.app.domain.model.SourcePlaybackEventType
import com.youtv.app.domain.model.SourceQualityPolicy
import com.youtv.app.domain.model.StreamSource
import com.youtv.app.domain.playlist.PlaylistFormat
import com.youtv.app.domain.playlist.PlaylistParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChannelRepository(
    private val dao: ChannelDao,
    private val parser: PlaylistParser,
    private val gson: Gson,
    private val legacyFavoriteIndexes: Set<Int>,
) {
    fun observeGroups(): Flow<List<ChannelGroup>> = dao.observeGroups().map { groups ->
        groups.map { relation ->
            ChannelGroup(
                name = relation.group.name,
                channels = relation.channels.sortedBy { it.channel.sortOrder }.map { item ->
                    Channel(
                        id = item.channel.id,
                        name = item.channel.name,
                        title = item.channel.title,
                        group = item.channel.groupName,
                        logo = item.channel.logo,
                        number = item.channel.number,
                        favorite = item.channel.favorite,
                        preferredSource = item.channel.lastSuccessfulSource,
                        sources = item.sources.sortedBy { it.sortOrder }.map { source ->
                            StreamSource(
                                url = source.url,
                                headers = gson.fromJson(
                                    source.headersJson,
                                    object : TypeToken<Map<String, String>>() {}.type,
                                ),
                                order = source.sortOrder,
                                addressType = runCatching {
                                    SourceAddressType.valueOf(source.addressType)
                                }.getOrDefault(SourceAddressType.UNKNOWN),
                                healthStatus = runCatching {
                                    SourceHealthStatus.valueOf(source.healthStatus)
                                }.getOrDefault(SourceHealthStatus.UNKNOWN),
                                startupMs = source.startupMs,
                                bitrateBps = source.bitrateBps,
                                lastCheckedAt = source.lastCheckedAt,
                                firstSeenAt = source.firstSeenAt,
                                lastAttemptAt = source.lastAttemptAt,
                                lastErrorAt = source.lastErrorAt,
                                lastFluctuationAt = source.lastFluctuationAt,
                                errorCount = source.errorCount,
                                fluctuationCount = source.fluctuationCount,
                                videoWidth = source.videoWidth,
                                videoHeight = source.videoHeight,
                                videoFrameRate = source.videoFrameRate,
                                videoCodec = source.videoCodec,
                                videoTrackBitrate = source.videoTrackBitrate,
                            )
                        },
                    )
                },
            )
        }
    }

    suspend fun importPlaylist(
        content: String,
        format: PlaylistFormat = PlaylistFormat.AUTO,
        migrateLegacyFavorites: Boolean = false,
    ): ImportReport {
        val report = parser.parse(content, format)
        if (!report.isSuccess) return report
        val previous = dao.channelsSnapshot().associateBy { it.id }
        val previousSources = dao.sourcesSnapshot().groupBy { it.channelId }
        val importedAt = System.currentTimeMillis()
        var legacyIndex = 0
        val groupEntities = mutableListOf<ChannelGroupEntity>()
        val channelEntities = mutableListOf<ChannelEntity>()
        val sourceEntities = mutableListOf<StreamSourceEntity>()
        report.groups.forEachIndexed { groupIndex, group ->
            groupEntities += ChannelGroupEntity(group.name, groupIndex)
            group.channels.forEachIndexed { channelIndex, channel ->
                val previousChannel = previous[channel.id]
                val oldSources = previousSources[channel.id].orEmpty()
                val rememberedUrl = previousChannel?.lastSuccessfulSourceUrl
                    ?.takeIf(String::isNotBlank)
                    ?: oldSources.firstOrNull {
                        it.sortOrder == previousChannel?.lastSuccessfulSource
                    }?.url
                val preferredSource = channel.sources.indexOfFirst { it.url == rememberedUrl }
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
                    lastSuccessfulSourceUrl = channel.sources.getOrNull(preferredSource)?.url.orEmpty(),
                )
                channel.sources.forEach { source ->
                    val previousSource = oldSources.firstOrNull { it.url == source.url }
                    val firstSeenAt = previousSource?.firstSeenAt?.takeIf { it > 0L } ?: importedAt
                    val clearHistory = previousSource != null && SourceQualityPolicy.shouldClearHistory(
                        firstSeenAt = firstSeenAt,
                        lastAttemptAt = previousSource.lastAttemptAt,
                        nowMillis = importedAt,
                    )
                    sourceEntities += StreamSourceEntity(
                        channelId = channel.id,
                        sortOrder = source.order,
                        url = source.url,
                        headersJson = gson.toJson(source.headers),
                        addressType = source.addressType.name,
                        healthStatus = previousSource?.healthStatus
                            ?.takeUnless { clearHistory } ?: SourceHealthStatus.UNKNOWN.name,
                        startupMs = previousSource?.startupMs?.takeUnless { clearHistory },
                        bitrateBps = previousSource?.bitrateBps?.takeUnless { clearHistory },
                        lastCheckedAt = previousSource?.lastCheckedAt?.takeUnless { clearHistory },
                        firstSeenAt = firstSeenAt,
                        lastAttemptAt = previousSource?.lastAttemptAt,
                        lastErrorAt = previousSource?.lastErrorAt?.takeUnless { clearHistory },
                        lastFluctuationAt = previousSource?.lastFluctuationAt?.takeUnless { clearHistory },
                        errorCount = previousSource?.errorCount?.takeUnless { clearHistory } ?: 0,
                        fluctuationCount = previousSource?.fluctuationCount?.takeUnless { clearHistory } ?: 0,
                        videoWidth = previousSource?.videoWidth?.takeUnless { clearHistory },
                        videoHeight = previousSource?.videoHeight?.takeUnless { clearHistory },
                        videoFrameRate = previousSource?.videoFrameRate?.takeUnless { clearHistory },
                        videoCodec = previousSource?.videoCodec?.takeUnless { clearHistory }.orEmpty(),
                        videoTrackBitrate = previousSource?.videoTrackBitrate?.takeUnless { clearHistory },
                    )
                }
                legacyIndex++
            }
        }
        dao.replaceAll(groupEntities, channelEntities, sourceEntities)
        return report
    }

    suspend fun isEmpty(): Boolean = dao.channelCount() == 0

    suspend fun setFavorite(channelId: String, favorite: Boolean) = dao.setFavorite(channelId, favorite)

    suspend fun rememberSourceResult(result: SourcePlaybackResult) {
        if (result.event == SourcePlaybackEventType.ATTEMPT_STARTED) {
            dao.markSourceAttempted(
                channelId = result.channelId,
                sourceUrl = result.sourceUrl,
                attemptedAt = result.checkedAt,
            )
            return
        }
        if (result.event == SourcePlaybackEventType.FLUCTUATION) {
            dao.incrementSourceFluctuation(
                channelId = result.channelId,
                sourceUrl = result.sourceUrl,
                checkedAt = result.checkedAt,
            )
            return
        }
        if (result.event == SourcePlaybackEventType.FORMAT_CHANGED) {
            dao.setSourceFormat(
                channelId = result.channelId,
                sourceUrl = result.sourceUrl,
                videoWidth = result.videoWidth,
                videoHeight = result.videoHeight,
                videoFrameRate = result.videoFrameRate,
                videoCodec = result.videoCodec,
                videoTrackBitrate = result.videoTrackBitrate,
            )
            return
        }
        val status = when (result.event) {
            SourcePlaybackEventType.SUCCESS -> SourceHealthStatus.SUCCESS
            SourcePlaybackEventType.TIMEOUT -> SourceHealthStatus.TIMEOUT
            SourcePlaybackEventType.ERROR -> SourceHealthStatus.ERROR
            SourcePlaybackEventType.ATTEMPT_STARTED -> error("handled above")
            SourcePlaybackEventType.FLUCTUATION -> error("handled above")
            SourcePlaybackEventType.FORMAT_CHANGED -> error("handled above")
        }
        dao.setSourceHealth(
            channelId = result.channelId,
            sourceUrl = result.sourceUrl,
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
        if (status == SourceHealthStatus.SUCCESS) {
            dao.setLastSuccessfulSource(result.channelId, result.sourceIndex, result.sourceUrl)
        }
    }
}
