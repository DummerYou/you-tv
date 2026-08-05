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
    val lastSuccessfulSourceUrl: String = "",
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
    val healthStatus: String = "UNKNOWN",
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
)

@Entity(
    tableName = "source_quality",
    primaryKeys = ["channelId", "sourceUrl"],
    indices = [Index("channelId")],
)
data class SourceQualityEntity(
    val channelId: String,
    val sourceUrl: String,
    val healthStatus: String = "UNKNOWN",
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
    val formatCheckedAt: Long? = null,
)

@Entity(
    tableName = "blocked_sources",
    primaryKeys = ["channelKey", "sourceFingerprint"],
    indices = [Index("channelId")],
)
data class BlockedSourceEntity(
    val channelKey: String,
    val sourceFingerprint: String,
    val channelId: String,
    val channelName: String,
    val sourceNumber: Int,
    val sourceUrl: String = "",
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val blockedAt: Long,
)

@Entity(
    tableName = "playback_logs",
    indices = [Index("occurredAt")],
)
data class PlaybackLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val occurredAt: Long,
    val event: String,
    val channelId: String,
    val channelName: String,
    val sourceNumber: Int,
    val sourceUrl: String,
    val reasonCode: String,
    val reason: String,
    val errorCode: Int? = null,
    val startupMs: Long? = null,
    val playbackMs: Long = 0L,
    val bufferingMs: Long = 0L,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Float? = null,
    val videoCodec: String = "",
    val videoTrackBitrate: Long? = null,
)
