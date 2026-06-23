package com.youtv.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ChannelGroupEntity::class, ChannelEntity::class, StreamSourceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
}
