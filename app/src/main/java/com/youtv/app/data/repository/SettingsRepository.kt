package com.youtv.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DEFAULT_EPG_URL =
    "https://live.fanmingming.cn/e.xml,https://raw.githubusercontent.com/fanmingming/live/main/e.xml"

enum class PlaylistSourceMode { TEXT, URL }

data class AppSettings(
    val channelReversal: Boolean = false,
    val channelNumber: Boolean = false,
    val showTime: Boolean = false,
    val bootStartup: Boolean = false,
    val defaultChannel: Int = 0,
    val defaultFavorite: Boolean = false,
    val displaySeconds: Boolean = false,
    val repeatInfo: Boolean = true,
    val showAllChannels: Boolean = false,
    val compactMenu: Boolean = true,
    val softDecode: Boolean = false,
    val configUrl: String = "",
    val proxy: String = "",
    val epgUrl: String = DEFAULT_EPG_URL,
    val sourceMode: PlaylistSourceMode = PlaylistSourceMode.TEXT,
    val playlistUpdatedAt: String = "",
)

class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = store.data.map { preferences ->
        val configUrl = preferences[Keys.CONFIG_URL].orEmpty()
        AppSettings(
            channelReversal = preferences[Keys.CHANNEL_REVERSAL] ?: false,
            channelNumber = preferences[Keys.CHANNEL_NUM] ?: false,
            showTime = preferences[Keys.TIME] ?: false,
            bootStartup = preferences[Keys.BOOT_STARTUP] ?: false,
            defaultChannel = preferences[Keys.CHANNEL] ?: 0,
            defaultFavorite = preferences[Keys.DEFAULT_LIKE] ?: false,
            displaySeconds = preferences[Keys.DISPLAY_SECONDS] ?: false,
            repeatInfo = preferences[Keys.REPEAT_INFO] ?: true,
            showAllChannels = preferences[Keys.SHOW_ALL_CHANNELS] ?: false,
            compactMenu = preferences[Keys.COMPACT_MENU] ?: true,
            softDecode = preferences[Keys.SOFT_DECODE] ?: false,
            configUrl = configUrl,
            proxy = preferences[Keys.PROXY].orEmpty(),
            epgUrl = preferences[Keys.EPG] ?: DEFAULT_EPG_URL,
            sourceMode = preferences[Keys.SOURCE_MODE]?.let {
                runCatching { PlaylistSourceMode.valueOf(it) }.getOrNull()
            } ?: if (configUrl.isNotBlank() && preferences[Keys.CONFIG_AUTO_LOAD] == true) {
                PlaylistSourceMode.URL
            } else {
                PlaylistSourceMode.TEXT
            },
            playlistUpdatedAt = preferences[Keys.PLAYLIST_UPDATED_AT].orEmpty(),
        )
    }

    suspend fun setChannelReversal(value: Boolean) = set(Keys.CHANNEL_REVERSAL, value)
    suspend fun setChannelNumber(value: Boolean) = set(Keys.CHANNEL_NUM, value)
    suspend fun setShowTime(value: Boolean) = set(Keys.TIME, value)
    suspend fun setDisplaySeconds(value: Boolean) = set(Keys.DISPLAY_SECONDS, value)
    suspend fun setRepeatInfo(value: Boolean) = set(Keys.REPEAT_INFO, value)
    suspend fun setDefaultFavorite(value: Boolean) = set(Keys.DEFAULT_LIKE, value)
    suspend fun setSoftDecode(value: Boolean) = set(Keys.SOFT_DECODE, value)
    suspend fun setShowAllChannels(value: Boolean) = set(Keys.SHOW_ALL_CHANNELS, value)
    suspend fun setCompactMenu(value: Boolean) = set(Keys.COMPACT_MENU, value)
    suspend fun setBootStartup(value: Boolean) = set(Keys.BOOT_STARTUP, value)
    suspend fun setDefaultChannel(value: Int) = set(Keys.CHANNEL, value)
    suspend fun setConfigUrl(value: String) = set(Keys.CONFIG_URL, value)
    suspend fun setProxy(value: String) = set(Keys.PROXY, value)
    suspend fun setEpgUrl(value: String) = set(Keys.EPG, value)
    suspend fun setSourceMode(value: PlaylistSourceMode) = set(Keys.SOURCE_MODE, value.name)
    suspend fun setPlaylistUpdatedAt(value: String) = set(Keys.PLAYLIST_UPDATED_AT, value)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private object Keys {
        val CHANNEL_REVERSAL = booleanPreferencesKey("channel_reversal")
        val CHANNEL_NUM = booleanPreferencesKey("channel_num")
        val TIME = booleanPreferencesKey("time")
        val BOOT_STARTUP = booleanPreferencesKey("boot_startup")
        val CHANNEL = intPreferencesKey("channel")
        val DEFAULT_LIKE = booleanPreferencesKey("default_like")
        val DISPLAY_SECONDS = booleanPreferencesKey("display_seconds")
        val REPEAT_INFO = booleanPreferencesKey("repeat_info")
        val CONFIG_AUTO_LOAD = booleanPreferencesKey("config_auto_load")
        val SHOW_ALL_CHANNELS = booleanPreferencesKey("show_all_channels")
        val COMPACT_MENU = booleanPreferencesKey("compact_menu")
        val SOFT_DECODE = booleanPreferencesKey("soft_decode")
        val CONFIG_URL = stringPreferencesKey("config")
        val PROXY = stringPreferencesKey("proxy")
        val EPG = stringPreferencesKey("epg")
        val SOURCE_MODE = stringPreferencesKey("playlist_source_mode")
        val PLAYLIST_UPDATED_AT = stringPreferencesKey("playlist_updated_at")
    }

    companion object {
        const val DEFAULT_EPG = DEFAULT_EPG_URL
    }
}
