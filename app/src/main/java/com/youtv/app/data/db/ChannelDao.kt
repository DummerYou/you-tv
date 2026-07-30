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

    @Query("SELECT * FROM source_quality WHERE channelId = :channelId")
    fun observeSourceQuality(channelId: String): Flow<List<SourceQualityEntity>>

    @Query("SELECT * FROM blocked_sources ORDER BY blockedAt DESC")
    fun observeBlockedSources(): Flow<List<BlockedSourceEntity>>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun channelCount(): Int

    @Query("SELECT * FROM channels")
    suspend fun channelsSnapshot(): List<ChannelEntity>

    @Query("SELECT * FROM stream_sources")
    suspend fun sourcesSnapshot(): List<StreamSourceEntity>

    @Query("SELECT * FROM source_quality")
    suspend fun qualitiesSnapshot(): List<SourceQualityEntity>

    @Query("SELECT * FROM blocked_sources")
    suspend fun blockedSourcesSnapshot(): List<BlockedSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<ChannelGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<StreamSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQualities(qualities: List<SourceQualityEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQualityIfMissing(quality: SourceQualityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedSource(source: BlockedSourceEntity)

    @Query("DELETE FROM channel_groups")
    suspend fun clearGroups()

    @Query("DELETE FROM source_quality")
    suspend fun clearQualities()

    @Query("DELETE FROM blocked_sources WHERE channelKey = :channelKey AND sourceFingerprint = :fingerprint")
    suspend fun deleteBlockedSource(channelKey: String, fingerprint: String)

    @Query("DELETE FROM blocked_sources")
    suspend fun clearBlockedSources()

    @Query("UPDATE channels SET favorite = :favorite WHERE id = :channelId")
    suspend fun setFavorite(channelId: String, favorite: Boolean)

    @Query("UPDATE channels SET lastSuccessfulSource = :sourceIndex, lastSuccessfulSourceUrl = :sourceUrl WHERE id = :channelId")
    suspend fun setLastSuccessfulSource(channelId: String, sourceIndex: Int, sourceUrl: String)

    @Query(
        """UPDATE source_quality SET sourceUrl = :sourceKey
            WHERE channelId = :channelId AND sourceUrl = :legacyUrl
              AND NOT EXISTS (
                  SELECT 1 FROM source_quality AS existing
                  WHERE existing.channelId = :channelId AND existing.sourceUrl = :sourceKey
              )""",
    )
    suspend fun migrateLegacyQualityKey(channelId: String, legacyUrl: String, sourceKey: String)

    @Query(
        "UPDATE source_quality SET lastAttemptAt = :attemptedAt WHERE channelId = :channelId AND sourceUrl = :sourceUrl",
    )
    suspend fun markSourceAttempted(channelId: String, sourceUrl: String, attemptedAt: Long)

    @Query(
        """UPDATE source_quality SET
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
            videoTrackBitrate = COALESCE(:videoTrackBitrate, videoTrackBitrate),
            formatCheckedAt = CASE WHEN :videoWidth IS NULL THEN formatCheckedAt ELSE :checkedAt END
            WHERE channelId = :channelId AND sourceUrl = :sourceUrl""",
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
        """UPDATE source_quality SET
            videoWidth = COALESCE(:videoWidth, videoWidth),
            videoHeight = COALESCE(:videoHeight, videoHeight),
            videoFrameRate = COALESCE(:videoFrameRate, videoFrameRate),
            videoCodec = CASE WHEN :videoCodec = '' THEN videoCodec ELSE :videoCodec END,
            videoTrackBitrate = COALESCE(:videoTrackBitrate, videoTrackBitrate),
            formatCheckedAt = CASE WHEN :videoWidth IS NULL THEN formatCheckedAt ELSE :checkedAt END
            WHERE channelId = :channelId AND sourceUrl = :sourceUrl""",
    )
    suspend fun setSourceFormat(
        channelId: String,
        sourceUrl: String,
        videoWidth: Int?,
        videoHeight: Int?,
        videoFrameRate: Float?,
        videoCodec: String,
        videoTrackBitrate: Long?,
        checkedAt: Long,
    )

    @Query(
        """UPDATE source_quality SET
            fluctuationCount = MIN(99,
                CASE
                    WHEN lastFluctuationAt IS NULL OR :checkedAt - lastFluctuationAt > 2592000000 THEN 1
                    WHEN :checkedAt - lastFluctuationAt > 1209600000 THEN (fluctuationCount + 3) / 4 + 1
                    WHEN :checkedAt - lastFluctuationAt > 604800000 THEN (fluctuationCount + 1) / 2 + 1
                    ELSE fluctuationCount + 1
                END
            ),
            lastFluctuationAt = :checkedAt
            WHERE channelId = :channelId AND sourceUrl = :sourceUrl""",
    )
    suspend fun incrementSourceFluctuation(
        channelId: String,
        sourceUrl: String,
        checkedAt: Long,
    )

    @Query(
        """UPDATE source_quality SET
            totalPlaybackMs = CASE
                WHEN totalPlaybackMs + totalBufferingMs >= 43200000
                    THEN totalPlaybackMs / 2 + :playbackMs
                ELSE totalPlaybackMs + :playbackMs
            END,
            totalBufferingMs = CASE
                WHEN totalPlaybackMs + totalBufferingMs >= 43200000
                    THEN totalBufferingMs / 2 + :bufferingMs
                ELSE totalBufferingMs + :bufferingMs
            END,
            sessionCount = CASE
                WHEN totalPlaybackMs + totalBufferingMs >= 43200000
                    THEN sessionCount / 2 + :sessionIncrement
                ELSE sessionCount + :sessionIncrement
            END
            WHERE channelId = :channelId AND sourceUrl = :sourceUrl""",
    )
    suspend fun addSourceSessionStats(
        channelId: String,
        sourceUrl: String,
        playbackMs: Long,
        bufferingMs: Long,
        sessionIncrement: Int,
    )

    @Transaction
    suspend fun replaceAll(
        groups: List<ChannelGroupEntity>,
        channels: List<ChannelEntity>,
        sources: List<StreamSourceEntity>,
        qualities: List<SourceQualityEntity>,
    ) {
        clearGroups()
        clearQualities()
        insertGroups(groups)
        insertChannels(channels)
        insertSources(sources)
        insertQualities(qualities)
    }
}
