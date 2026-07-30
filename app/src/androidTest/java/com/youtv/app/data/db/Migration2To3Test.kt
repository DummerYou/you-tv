package com.youtv.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun sourceQualityIsPreservedWhenMigratingFromVersion2() {
        createVersion2Database().use { helper ->
            helper.writableDatabase.apply {
                execSQL("INSERT INTO channel_groups VALUES ('央视', 0)")
                execSQL(
                    "INSERT INTO channels VALUES " +
                        "('cctv5', 'CCTV5', 'CCTV5', '央视', '', 5, 0, 0, 0, 'http://test/live')",
                )
                execSQL(
                    """INSERT INTO stream_sources VALUES (
                        'cctv5', 0, 'http://test/live', '{}', 'HOSTNAME', 'SUCCESS',
                        1200, 8400000, 1000, 500, 900, 800, 2, 3,
                        1920, 1080, 50.0, 'H.264', 8000000
                    )""",
                )
            }
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val quality = runBlocking { database.channelDao().qualitiesSnapshot().single() }
            assertEquals("cctv5", quality.channelId)
            assertEquals("http://test/live", quality.sourceUrl)
            assertEquals("SUCCESS", quality.healthStatus)
            assertEquals(1080, quality.videoHeight)
            assertEquals(0L, quality.totalPlaybackMs)
            assertEquals(1000L, quality.formatCheckedAt)
            assertTrue(runBlocking { database.channelDao().blockedSourcesSnapshot() }.isEmpty())
        } finally {
            database.close()
        }
    }

    private fun createVersion2Database(): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DATABASE)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE channel_groups (
                            name TEXT NOT NULL PRIMARY KEY,
                            sortOrder INTEGER NOT NULL
                        )""",
                    )
                    db.execSQL(
                        """CREATE TABLE channels (
                            id TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            title TEXT NOT NULL,
                            groupName TEXT NOT NULL,
                            logo TEXT NOT NULL,
                            number INTEGER NOT NULL,
                            sortOrder INTEGER NOT NULL,
                            favorite INTEGER NOT NULL,
                            lastSuccessfulSource INTEGER NOT NULL,
                            lastSuccessfulSourceUrl TEXT NOT NULL,
                            FOREIGN KEY(groupName) REFERENCES channel_groups(name) ON DELETE CASCADE
                        )""",
                    )
                    db.execSQL("CREATE INDEX index_channels_groupName ON channels(groupName)")
                    db.execSQL(
                        """CREATE TABLE stream_sources (
                            channelId TEXT NOT NULL,
                            sortOrder INTEGER NOT NULL,
                            url TEXT NOT NULL,
                            headersJson TEXT NOT NULL,
                            addressType TEXT NOT NULL,
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
                            PRIMARY KEY(channelId, sortOrder),
                            FOREIGN KEY(channelId) REFERENCES channels(id) ON DELETE CASCADE
                        )""",
                    )
                    db.execSQL("CREATE INDEX index_stream_sources_channelId ON stream_sources(channelId)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    private companion object {
        const val TEST_DATABASE = "migration-2-3-test.db"
    }
}
