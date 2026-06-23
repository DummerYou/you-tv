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
        var legacyIndex = 0
        val groupEntities = mutableListOf<ChannelGroupEntity>()
        val channelEntities = mutableListOf<ChannelEntity>()
        val sourceEntities = mutableListOf<StreamSourceEntity>()
        report.groups.forEachIndexed { groupIndex, group ->
            groupEntities += ChannelGroupEntity(group.name, groupIndex)
            group.channels.forEachIndexed { channelIndex, channel ->
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
                    lastSuccessfulSource = previous[channel.id]?.lastSuccessfulSource
                        ?.coerceIn(0, (channel.sources.size - 1).coerceAtLeast(0)) ?: 0,
                )
                channel.sources.forEach { source ->
                    sourceEntities += StreamSourceEntity(
                        channelId = channel.id,
                        sortOrder = source.order,
                        url = source.url,
                        headersJson = gson.toJson(source.headers),
                        addressType = source.addressType.name,
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

    suspend fun rememberSuccessfulSource(channelId: String, sourceIndex: Int) =
        dao.setLastSuccessfulSource(channelId, sourceIndex)
}
