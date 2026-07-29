package com.youtv.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class ChannelWithSources(
    @Embedded val channel: ChannelEntity,
    @Relation(parentColumn = "id", entityColumn = "channelId")
    val sources: List<StreamSourceEntity>,
)

data class GroupWithChannels(
    @Embedded val group: ChannelGroupEntity,
    @Relation(
        entity = ChannelEntity::class,
        parentColumn = "name",
        entityColumn = "groupName",
    )
    val channels: List<ChannelWithSources>,
)

@Dao
interface ChannelDao {
    @Transaction
    @Query("SELECT * FROM channel_groups ORDER BY sortOrder")
    fun observeGroups(): Flow<List<GroupWithChannels>>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun channelCount(): Int

    @Query("SELECT * FROM channels")
    suspend fun channelsSnapshot(): List<ChannelEntity>

    @Query("SELECT * FROM stream_sources")
    suspend fun sourcesSnapshot(): List<StreamSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<ChannelGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<StreamSourceEntity>)

    @Query("DELETE FROM channel_groups")
    suspend fun clearGroups()

    @Query("UPDATE channels SET favorite = :favorite WHERE id = :channelId")
    suspend fun setFavorite(channelId: String, favorite: Boolean)

    @Query("UPDATE channels SET lastSuccessfulSource = :sourceIndex, lastSuccessfulSourceUrl = :sourceUrl WHERE id = :channelId")
    suspend fun setLastSuccessfulSource(channelId: String, sourceIndex: Int, sourceUrl: String)

    @Query(
        "UPDATE stream_sources SET lastAttemptAt = :attemptedAt WHERE channelId = :channelId AND url = :sourceUrl",
    )
    suspend fun markSourceAttempted(channelId: String, sourceUrl: String, attemptedAt: Long)

    @Query(
        """UPDATE stream_sources SET
            healthStatus = :status,
            startupMs = :startupMs,
            bitrateBps = :bitrateBps,
            lastCheckedAt = :checkedAt,
            errorCount = CASE WHEN :errorIncrement = 0 THEN errorCount ELSE MIN(99,
                CASE
                    WHEN lastErrorAt IS NULL OR :checkedAt - lastErrorAt > 2592000000 THEN 1
                    WHEN :checkedAt - lastErrorAt > 1209600000 THEN (errorCount + 3) / 4 + 1
                    WHEN :checkedAt - lastErrorAt > 604800000 THEN (errorCount + 1) / 2 + 1
                    ELSE errorCount + 1
                END
            ) END,
            lastErrorAt = CASE WHEN :errorIncrement = 0 THEN lastErrorAt ELSE :checkedAt END,
            videoWidth = COALESCE(:videoWidth, videoWidth),
            videoHeight = COALESCE(:videoHeight, videoHeight),
            videoFrameRate = COALESCE(:videoFrameRate, videoFrameRate),
            videoCodec = CASE WHEN :videoCodec = '' THEN videoCodec ELSE :videoCodec END,
            videoTrackBitrate = COALESCE(:videoTrackBitrate, videoTrackBitrate)
            WHERE channelId = :channelId AND url = :sourceUrl""",
    )
    suspend fun setSourceHealth(
        channelId: String,
        sourceUrl: String,
        status: String,
        startupMs: Long?,
        bitrateBps: Long?,
        checkedAt: Long,
        errorIncrement: Int,
        videoWidth: Int?,
        videoHeight: Int?,
        videoFrameRate: Float?,
        videoCodec: String,
        videoTrackBitrate: Long?,
    )

    @Query(
        """UPDATE stream_sources SET
            videoWidth = COALESCE(:videoWidth, videoWidth),
            videoHeight = COALESCE(:videoHeight, videoHeight),
            videoFrameRate = COALESCE(:videoFrameRate, videoFrameRate),
            videoCodec = CASE WHEN :videoCodec = '' THEN videoCodec ELSE :videoCodec END,
            videoTrackBitrate = COALESCE(:videoTrackBitrate, videoTrackBitrate)
            WHERE channelId = :channelId AND url = :sourceUrl""",
    )
    suspend fun setSourceFormat(
        channelId: String,
        sourceUrl: String,
        videoWidth: Int?,
        videoHeight: Int?,
        videoFrameRate: Float?,
        videoCodec: String,
        videoTrackBitrate: Long?,
    )

    @Query(
        """UPDATE stream_sources SET
            fluctuationCount = MIN(99,
                CASE
                    WHEN lastFluctuationAt IS NULL OR :checkedAt - lastFluctuationAt > 2592000000 THEN 1
                    WHEN :checkedAt - lastFluctuationAt > 1209600000 THEN (fluctuationCount + 3) / 4 + 1
                    WHEN :checkedAt - lastFluctuationAt > 604800000 THEN (fluctuationCount + 1) / 2 + 1
                    ELSE fluctuationCount + 1
                END
            ),
            lastFluctuationAt = :checkedAt
            WHERE channelId = :channelId AND url = :sourceUrl""",
    )
    suspend fun incrementSourceFluctuation(
        channelId: String,
        sourceUrl: String,
        checkedAt: Long,
    )

    @Transaction
    suspend fun replaceAll(
        groups: List<ChannelGroupEntity>,
        channels: List<ChannelEntity>,
        sources: List<StreamSourceEntity>,
    ) {
        clearGroups()
        insertGroups(groups)
        insertChannels(channels)
        insertSources(sources)
    }
}
