package com.youtv.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChannelGroupEntity::class,
        ChannelEntity::class,
        StreamSourceEntity::class,
        SourceQualityEntity::class,
        BlockedSourceEntity::class,
        PlaybackLogEntity::class,
    ],
    version = 4,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS source_quality (
                        channelId TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        healthStatus TEXT NOT NULL,
                        startupMs INTEGER,
                        bitrateBps INTEGER,
                        lastCheckedAt INTEGER,
                        firstSeenAt INTEGER NOT NULL,
                        lastAttemptAt INTEGER,
                        lastErrorAt INTEGER,
                        lastFluctuationAt INTEGER,
                        errorCount INTEGER NOT NULL,
                        fluctuationCount INTEGER NOT NULL,
                        videoWidth INTEGER,
                        videoHeight INTEGER,
                        videoFrameRate REAL,
                        videoCodec TEXT NOT NULL,
                        videoTrackBitrate INTEGER,
                        totalPlaybackMs INTEGER NOT NULL,
                        totalBufferingMs INTEGER NOT NULL,
                        sessionCount INTEGER NOT NULL,
                        formatCheckedAt INTEGER,
                        PRIMARY KEY(channelId, sourceUrl)
                    )""",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_source_quality_channelId ON source_quality(channelId)",
                )
                database.execSQL(
                    """INSERT OR REPLACE INTO source_quality (
                        channelId, sourceUrl, healthStatus, startupMs, bitrateBps, lastCheckedAt,
                        firstSeenAt, lastAttemptAt, lastErrorAt, lastFluctuationAt, errorCount,
                        fluctuationCount, videoWidth, videoHeight, videoFrameRate, videoCodec,
                        videoTrackBitrate, totalPlaybackMs, totalBufferingMs, sessionCount,
                        formatCheckedAt
                    ) SELECT channelId, url, healthStatus, startupMs, bitrateBps, lastCheckedAt,
                        firstSeenAt, lastAttemptAt, lastErrorAt, lastFluctuationAt, errorCount,
                        fluctuationCount, videoWidth, videoHeight, videoFrameRate, videoCodec,
                        videoTrackBitrate, 0, 0, 0,
                        CASE WHEN videoWidth IS NULL THEN NULL ELSE lastCheckedAt END
                    FROM stream_sources""",
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS blocked_sources (
                        channelKey TEXT NOT NULL,
                        sourceFingerprint TEXT NOT NULL,
                        channelId TEXT NOT NULL,
                        channelName TEXT NOT NULL,
                        sourceNumber INTEGER NOT NULL,
                        videoWidth INTEGER,
                        videoHeight INTEGER,
                        blockedAt INTEGER NOT NULL,
                        PRIMARY KEY(channelKey, sourceFingerprint)
                    )""",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_blocked_sources_channelId ON blocked_sources(channelId)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE blocked_sources ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS playback_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        event TEXT NOT NULL,
                        channelId TEXT NOT NULL,
                        channelName TEXT NOT NULL,
                        sourceNumber INTEGER NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        reasonCode TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        errorCode INTEGER,
                        startupMs INTEGER,
                        playbackMs INTEGER NOT NULL,
                        bufferingMs INTEGER NOT NULL,
                        videoWidth INTEGER,
                        videoHeight INTEGER,
                        videoFrameRate REAL,
                        videoCodec TEXT NOT NULL,
                        videoTrackBitrate INTEGER
                    )""",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_playback_logs_occurredAt ON playback_logs(occurredAt)",
                )
            }
        }
    }
}
