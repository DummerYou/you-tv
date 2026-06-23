package com.youtv.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "channel_groups")
data class ChannelGroupEntity(
    @PrimaryKey val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "channels",
    foreignKeys = [ForeignKey(
        entity = ChannelGroupEntity::class,
        parentColumns = ["name"],
        childColumns = ["groupName"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("groupName")],
)
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val title: String,
    val groupName: String,
    val logo: String,
    val number: Int,
    val sortOrder: Int,
    val favorite: Boolean = false,
    val lastSuccessfulSource: Int = 0,
)

@Entity(
    tableName = "stream_sources",
    primaryKeys = ["channelId", "sortOrder"],
    foreignKeys = [ForeignKey(
        entity = ChannelEntity::class,
        parentColumns = ["id"],
        childColumns = ["channelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("channelId")],
)
data class StreamSourceEntity(
    val channelId: String,
    val sortOrder: Int,
    val url: String,
    val headersJson: String,
    val addressType: String,
)
