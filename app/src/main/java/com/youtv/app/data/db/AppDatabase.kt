package com.youtv.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChannelGroupEntity::class, ChannelEntity::class, StreamSourceEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE channels ADD COLUMN lastSuccessfulSourceUrl TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL(
                    "ALTER TABLE stream_sources ADD COLUMN healthStatus TEXT NOT NULL DEFAULT 'UNKNOWN'",
                )
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN startupMs INTEGER")
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN bitrateBps INTEGER")
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN lastCheckedAt INTEGER")
                database.execSQL(
                    "ALTER TABLE stream_sources ADD COLUMN firstSeenAt INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN lastAttemptAt INTEGER")
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN lastErrorAt INTEGER")
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN lastFluctuationAt INTEGER")
                database.execSQL(
                    "ALTER TABLE stream_sources ADD COLUMN errorCount INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE stream_sources ADD COLUMN fluctuationCount INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN videoWidth INTEGER")
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN videoHeight INTEGER")
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN videoFrameRate REAL")
                database.execSQL(
                    "ALTER TABLE stream_sources ADD COLUMN videoCodec TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL("ALTER TABLE stream_sources ADD COLUMN videoTrackBitrate INTEGER")
                database.execSQL(
                    "UPDATE stream_sources SET firstSeenAt = CAST(strftime('%s', 'now') AS INTEGER) * 1000",
                )
                database.execSQL(
                    """UPDATE channels
                        SET lastSuccessfulSourceUrl = COALESCE((
                            SELECT url FROM stream_sources
                            WHERE stream_sources.channelId = channels.id
                              AND stream_sources.sortOrder = channels.lastSuccessfulSource
                            LIMIT 1
                        ), '')""",
                )
            }
        }
    }
}
