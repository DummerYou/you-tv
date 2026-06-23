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

    @Query("UPDATE channels SET lastSuccessfulSource = :sourceIndex WHERE id = :channelId")
    suspend fun setLastSuccessfulSource(channelId: String, sourceIndex: Int)

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
