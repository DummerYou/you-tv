package com.youtv.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.youtv.app.AppContainer
import com.youtv.app.R
import com.youtv.app.data.PlaylistTextDecoder
import com.youtv.app.data.repository.AppSettings
import com.youtv.app.data.repository.PlaylistSourceMode
import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.ChannelGroup
import com.youtv.app.domain.model.EpgGuide
import com.youtv.app.domain.model.Program
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.youtv.app.requests.HttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

enum class Overlay { NONE, CHANNELS, PROGRAM, SETTINGS }

data class MainUiState(
    val groups: List<ChannelGroup> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val currentIndex: Int = 0,
    val overlay: Overlay = Overlay.NONE,
    val settings: AppSettings = AppSettings(),
    val loading: Boolean = true,
    val message: String? = null,
    val programs: List<Program> = emptyList(),
    val infoVisible: Boolean = false,
) {
    val currentChannel: Channel? get() = channels.getOrNull(currentIndex)
    val menuGroups: List<ChannelGroup> get() = buildList {
        val favorites = channels.filter { it.favorite }
        if (favorites.isNotEmpty()) add(ChannelGroup("我的收藏", favorites))
        if (settings.showAllChannels) {
            add(ChannelGroup("全部频道", channels))
        } else {
            addAll(groups)
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as com.youtv.app.YouTvApplication).container
    private val repository = container.channelRepository
    private val overlay = MutableStateFlow(Overlay.NONE)
    private val infoVisible = MutableStateFlow(false)
    private val currentIndex = MutableStateFlow(container.legacyPreferences.position.coerceAtLeast(0))
    private val loading = MutableStateFlow(true)
    private val message = MutableStateFlow<String?>(null)
    private var infoHideJob: Job? = null
    private var playlistJob: Job? = null

    private val chrome = combine(overlay, infoVisible) { activeOverlay, showInfo ->
        activeOverlay to showInfo
    }

    private val data = combine(
        repository.observeGroups(),
        container.settingsRepository.settings,
        container.epgRepository.guide,
    ) { groups, settings, guide -> Triple(groups.withEpgLogos(guide), settings, guide) }

    val state: StateFlow<MainUiState> = combine(
        data,
        chrome,
        currentIndex,
        loading,
        message,
    ) { (groups, settings, guide), (activeOverlay, showInfo), index, isLoading, currentMessage ->
        val allChannels = groups.flatMap { it.channels }
        val safeIndex = index.coerceIn(0, (allChannels.size - 1).coerceAtLeast(0))
        val channelPrograms = allChannels.getOrNull(safeIndex)?.let { channel ->
            val name = channel.name.ifEmpty { channel.title }.lowercase()
            guide.programs.entries.firstOrNull { (key, _) -> name.contains(key.lowercase()) }?.value
        }.orEmpty()
        MainUiState(
            groups, allChannels, safeIndex, activeOverlay, settings, isLoading,
            currentMessage, channelPrograms, showInfo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        launchPlaylistTask(showGenericError = false) { initializeChannels() }
        viewModelScope.launch {
            container.epgRepository.loadCache()
            val epg = container.settingsRepository.settings.first().epgUrl
            if (epg.isNotBlank()) container.epgRepository.refresh(epg)
        }
    }

    fun showOverlay(value: Overlay) {
        infoVisible.value = false
        overlay.value = if (overlay.value == value) Overlay.NONE else value
    }

    fun showInfo() {
        infoHideJob?.cancel()
        infoVisible.value = true
        infoHideJob = viewModelScope.launch {
            delay(INFO_DISPLAY_MILLIS)
            infoVisible.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun closeOverlay(): Boolean {
        if (infoVisible.value) {
            infoVisible.value = false
            return true
        }
        if (overlay.value == Overlay.NONE) return false
        overlay.value = Overlay.NONE
        return true
    }

    fun selectChannel(channel: Channel) {
        val index = state.value.channels.indexOfFirst { it.id == channel.id }
        if (index >= 0) {
            currentIndex.value = index
            overlay.value = Overlay.NONE
        }
    }

    fun nextChannel(direction: Int) {
        val channels = state.value.channels
        if (channels.isEmpty()) return
        val adjusted = if (state.value.settings.channelReversal) -direction else direction
        val next = (currentIndex.value + adjusted).mod(channels.size)
        currentIndex.value = next
    }

    fun selectChannelNumber(number: Int) {
        val channels = state.value.channels
        if (number !in 1..channels.size) {
            message.value = "频道号 $number 不存在"
            return
        }
        currentIndex.value = number - 1
        overlay.value = Overlay.NONE
        showInfo()
    }

    fun setFavorite(channel: Channel, favorite: Boolean) {
        viewModelScope.launch { repository.setFavorite(channel.id, favorite) }
    }

    fun rememberSuccessfulSource(channelId: String, sourceIndex: Int) {
        viewModelScope.launch { repository.rememberSuccessfulSource(channelId, sourceIndex) }
    }

    fun setChannelReversal(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setChannelReversal(value)
    }

    fun setChannelNumber(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setChannelNumber(value)
    }

    fun setShowTime(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setShowTime(value)
    }

    fun setDisplaySeconds(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setDisplaySeconds(value)
    }

    fun setRepeatInfo(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setRepeatInfo(value)
    }

    fun setDefaultFavorite(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setDefaultFavorite(value)
    }

    fun setShowAllChannels(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setShowAllChannels(value)
    }

    fun setCompactMenu(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setCompactMenu(value)
    }

    fun setBootStartup(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setBootStartup(value)
    }

    fun setSoftDecode(value: Boolean) = viewModelScope.launch {
        container.settingsRepository.setSoftDecode(value)
    }

    fun setProxy(value: String) = viewModelScope.launch {
        container.settingsRepository.setProxy(value)
        com.youtv.app.requests.HttpClient.configureProxy(value)
    }

    fun setEpgUrl(value: String) = viewModelScope.launch {
        container.settingsRepository.setEpgUrl(value)
        container.epgRepository.refresh(value)
    }

    fun setDefaultChannel(value: Int) = viewModelScope.launch {
        container.settingsRepository.setDefaultChannel(value.coerceAtLeast(0))
    }

    fun setPreference(key: String, value: Boolean) {
        when (key) {
            "channelReversal" -> setChannelReversal(value)
            "channelNumber" -> setChannelNumber(value)
            "showTime" -> setShowTime(value)
            "displaySeconds" -> setDisplaySeconds(value)
            "repeatInfo" -> setRepeatInfo(value)
            "defaultFavorite" -> setDefaultFavorite(value)
            "showAllChannels" -> setShowAllChannels(value)
            "compactMenu" -> setCompactMenu(value)
            "softDecode" -> setSoftDecode(value)
            "bootStartup" -> setBootStartup(value)
        }
    }

    fun importFromUrl(url: String) {
        launchPlaylistTask {
            updateFromUrl(url, showResult = true)
        }
    }

    fun refreshSubscription() {
        val url = state.value.settings.configUrl
        if (url.isBlank()) message.value = "请先通过远程配置设置订阅地址" else importFromUrl(url)
    }

    fun useTextSource() {
        launchPlaylistTask {
            val file = File(getApplication<Application>().filesDir, TEXT_PLAYLIST_FILE)
            if (!file.exists() || file.length() == 0L) {
                message.value = "还没有保存的文本源，请先导入文件或文本"
                return@launchPlaylistTask
            }
            val content = withContext(Dispatchers.IO) { file.readPlaylistText() }
            val report = withContext(Dispatchers.IO) { repository.importPlaylist(content) }
            if (report.isSuccess) {
                container.settingsRepository.setSourceMode(PlaylistSourceMode.TEXT)
                saveMetadata(report.updatedAt)
                message.value = "已切换到文本源：${report.imported} 个频道"
            } else {
                message.value = report.issues.firstOrNull()?.message ?: "文本源解析失败"
            }
        }
    }

    fun importPlaylist(content: String) {
        launchPlaylistTask {
            val report = withContext(Dispatchers.IO) { repository.importPlaylist(content) }
            if (report.isSuccess) withContext(Dispatchers.IO) {
                File(getApplication<Application>().filesDir, TEXT_PLAYLIST_FILE).writeText(content)
                File(getApplication<Application>().filesDir, ACTIVE_PLAYLIST_FILE).writeText(content)
            }
            if (report.isSuccess) {
                container.settingsRepository.setSourceMode(PlaylistSourceMode.TEXT)
                saveMetadata(report.updatedAt)
            }
            message.value = if (report.isSuccess) {
                "已导入 ${report.imported} 个频道，合并 ${report.mergedSources} 个来源"
            } else report.issues.firstOrNull()?.message ?: "导入失败"
        }
    }

    private suspend fun initializeChannels() {
        migrateLegacyChannels()
        val settings = container.settingsRepository.settings.first()
        if (settings.sourceMode == PlaylistSourceMode.URL && settings.configUrl.isNotBlank()) {
            updateFromUrl(settings.configUrl, showResult = false)
        }
    }

    private suspend fun updateFromUrl(url: String, showResult: Boolean) {
        val content = withContext(Dispatchers.IO) {
            runCatching {
                HttpClient.getClientWithProxy().newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.bytes()?.let(PlaylistTextDecoder::decode)
                }
            }.getOrNull()
        }
        if (content.isNullOrBlank()) {
            if (showResult) message.value = "订阅下载失败，继续使用上次频道"
            return
        }
        val report = withContext(Dispatchers.IO) { repository.importPlaylist(content) }
        if (report.isSuccess) {
            withContext(Dispatchers.IO) {
                File(getApplication<Application>().filesDir, URL_PLAYLIST_FILE).writeText(content)
                File(getApplication<Application>().filesDir, ACTIVE_PLAYLIST_FILE).writeText(content)
            }
            container.settingsRepository.setConfigUrl(url)
            container.settingsRepository.setSourceMode(PlaylistSourceMode.URL)
            saveMetadata(report.updatedAt)
            if (showResult) message.value = "订阅已更新：${report.imported} 个频道"
        } else if (showResult) {
            message.value = report.issues.firstOrNull()?.message ?: "订阅解析失败"
        }
    }

    private suspend fun saveMetadata(value: String?) {
        container.settingsRepository.setPlaylistUpdatedAt(value.orEmpty())
    }

    private suspend fun migrateLegacyChannels() {
        if (!repository.isEmpty()) {
            return
        }
        val context = getApplication<Application>()
        val legacyFile = File(context.filesDir, ACTIVE_PLAYLIST_FILE)
        val content = withContext(Dispatchers.IO) {
            if (legacyFile.exists() && legacyFile.length() > 0) {
                legacyFile.readPlaylistText()
            } else {
                context.resources.openRawResource(R.raw.channels).use { input ->
                    PlaylistTextDecoder.decode(input.readBytes())
                }
            }
        }
        val report = repository.importPlaylist(content, migrateLegacyFavorites = true)
        if (report.isSuccess) {
            val textFile = File(context.filesDir, TEXT_PLAYLIST_FILE)
            if (!textFile.exists()) withContext(Dispatchers.IO) { textFile.writeText(content) }
            saveMetadata(report.updatedAt)
        }
        message.value = if (!report.isSuccess) "旧频道数据迁移失败，已保留原文件" else null
    }

    private fun launchPlaylistTask(
        showGenericError: Boolean = true,
        block: suspend () -> Unit,
    ) {
        playlistJob?.cancel()
        val job = viewModelScope.launch {
            loading.value = true
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (showGenericError) {
                    message.value = error.message?.takeIf(String::isNotBlank)
                        ?.let { "操作失败：$it" }
                        ?: "操作失败"
                }
            } finally {
                if (playlistJob === coroutineContext[Job]) {
                    loading.value = false
                    playlistJob = null
                }
            }
        }
        playlistJob = job
    }

    private fun File.readPlaylistText(): String = PlaylistTextDecoder.decode(readBytes())

    private fun List<ChannelGroup>.withEpgLogos(guide: EpgGuide): List<ChannelGroup> =
        map { group ->
            group.copy(channels = group.channels.map { channel ->
                if (channel.logo.isNotBlank()) {
                    channel
                } else {
                    channel.copy(logo = guide.logoFor(channel.name.ifEmpty { channel.title }))
                }
            })
        }

    private fun EpgGuide.logoFor(channelName: String): String {
        val name = channelName.lowercase()
        return logos.entries.firstOrNull { (key, _) -> name.contains(key.lowercase()) }?.value.orEmpty()
    }

    private companion object {
        const val INFO_DISPLAY_MILLIS = 5_000L
        const val ACTIVE_PLAYLIST_FILE = "channels.txt"
        const val TEXT_PLAYLIST_FILE = "playlist-text.txt"
        const val URL_PLAYLIST_FILE = "playlist-url.txt"
    }
}
