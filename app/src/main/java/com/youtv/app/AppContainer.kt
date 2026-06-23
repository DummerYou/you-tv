package com.youtv.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.google.gson.Gson
import com.youtv.app.data.db.AppDatabase
import com.youtv.app.data.LegacyPreferences
import com.youtv.app.data.repository.ChannelRepository
import com.youtv.app.data.repository.SettingsRepository
import com.youtv.app.data.repository.EpgRepository
import com.youtv.app.domain.playlist.PlaylistParser

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    val legacyPreferences = LegacyPreferences(appContext)
    private val database = Room.databaseBuilder(appContext, AppDatabase::class.java, "my-tv.db").build()
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(appContext, appContext.getString(R.string.app_name))),
        produceFile = { appContext.preferencesDataStoreFile("settings") },
    )

    val channelRepository = ChannelRepository(
        database.channelDao(), PlaylistParser(gson), gson, legacyPreferences.favoriteIndexes,
    )
    val settingsRepository = SettingsRepository(dataStore)
    val epgRepository = EpgRepository(appContext)
}
