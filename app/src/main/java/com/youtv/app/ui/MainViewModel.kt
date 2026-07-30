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
import com.youtv.app.domain.model.BlockedSource
import com.youtv.app.domain.model.EpgChannelLookup
import com.youtv.app.domain.model.Program
import com.youtv.app.domain.model.SourcePlaybackResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.youtv.app.requests.HttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

enum class Overlay { NONE, CHANNELS, FAVORITES, PROGRAM, SETTINGS }

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
    val favoriteSnapshotIds: List<String> = emptyList(),
    val blockedSources: List<BlockedSource> = emptyList(),
) {
    val currentChannel: Channel? get() = channels.getOrNull(currentIndex)
    val menuGroups: List<ChannelGroup> get() = buildList {
        if (settings.showAllChannels) {
            add(ChannelGroup("全部频道", channels))
        } else {
            addAll(groups)
        }
    }
    val favoriteSnapshotChannels: List<Channel> get() =
        favoriteSnapshotIds.mapNotNull { id -> channels.firstOrNull { it.id == id } }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as com.youtv.app.YouTvApplication).container
    private val repository = container.channelRepository
    private val overlay = MutableStateFlow(Overlay.NONE)
    private val infoVisible = MutableStateFlow(false)
    private val favoriteSnapshotIds = MutableStateFlow<List<String>>(emptyList())
    private val selectedChannelId = MutableStateFlow<String?>(null)
    private val loading = MutableStateFlow(true)
    private val message = MutableStateFlow<String?>(null)
    private var infoHideJob: Job? = null
    private var overlayTimeoutJob: Job? = null
    private var playlistJob: Job? = null
    private var backgroundRefreshJob: Job? = null

    private val chrome = combine(overlay, infoVisible, favoriteSnapshotIds) { activeOverlay, showInfo, favorites ->
        Triple(activeOverlay, showInfo, favorites)
    }

    private val catalogWithEpg = combine(
        repository.observeGroups(),
        container.epgRepository.guide,
    ) { groups, guide ->
        val lookup = EpgChannelLookup(guide)
        CatalogData(groups.withEpgLogos(lookup), lookup)
    }.flowOn(Dispatchers.Default)

    private val baseData = combine(
        catalogWithEpg,
        container.settingsRepository.settings,
        repository.observeBlockedSources(),
    ) { catalog, settings, blocked ->
        MainData(catalog.groups, settings, catalog.epgLookup, blocked)
    }

    private val selectedQuality = selectedChannelId.flatMapLatest { channelId ->
        if (channelId == null) flowOf(emptyMap()) else repository.observeSourceQuality(channelId)
    }

    private val data = combine(baseData, selectedChannelId, selectedQuality) { base, selectedId, quality ->
        if (selectedId == null || quality.isEmpty()) base else base.copy(
            groups = base.groups.map { group ->
                val selectedIndex = group.channels.indexOfFirst { it.id == selectedId }
                if (selectedIndex < 0) group else group.copy(
                    channels = group.channels.toMutableList().apply {
                        this[selectedIndex] = repository.applyQuality(this[selectedIndex], quality)
                    },
                )
            },
        )
    }

    val state: StateFlow<MainUiState> = combine(
        data,
        chrome,
        selectedChannelId,
        loading,
        message,
    ) { data, (activeOverlay, showInfo, favorites), selectedId, isLoading, currentMessage ->
        val (groups, settings, epgLookup, blockedSources) = data
        val allChannels = groups.flatMap { it.channels }
        val safeIndex = selectedId?.let { id -> allChannels.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val channelPrograms = allChannels.getOrNull(safeIndex)?.let { channel ->
            epgLookup.programsFor(channel.name.ifEmpty { channel.title })
        }.orEmpty()
        MainUiState(
            groups, allChannels, safeIndex, activeOverlay, settings, isLoading,
            currentMessage, channelPrograms, showInfo, favorites, blockedSources,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        launchPlaylistTask(showGenericError = false) { initializeChannels() }
        viewModelScope.launch {
            combine(repository.observeGroups(), selectedChannelId, loading) { groups, selectedId, isLoading ->
                Triple(groups.flatMap { it.channels }, selectedId, isLoading)
            }.collect { (channels, selectedId, isLoading) ->
                if (!isLoading && channels.isNotEmpty() && channels.none { it.id == selectedId }) {
                    selectChannelId(channels.first().id, remember = true)
                }
            }
        }
        viewModelScope.launch {
            container.epgRepository.loadCache()
        }
        viewModelScope.launch {
            delay(BACKGROUND_REFRESH_FALLBACK_MILLIS)
            refreshBackgroundIfDue()
        }
    }

    fun showOverlay(value: Overlay) {
        infoVisible.value = false
        if (value == Overlay.FAVORITES && overlay.value != Overlay.FAVORITES) {
            favoriteSnapshotIds.value = state.value.channels.filter { it.favorite }.map { it.id }
        }
        setOverlay(if (overlay.value == value) Overlay.NONE else value)
    }

    fun notifyOverlayInteraction() {
        if (overlay.value != Overlay.NONE && overlay.value != Overlay.SETTINGS) {
            scheduleOverlayTimeout()
        }
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
        if (overlay.value != Overlay.NONE) {
            setOverlay(Overlay.NONE)
            infoVisible.value = false
            return true
        }
        if (infoVisible.value) {
            infoVisible.value = false
            return true
        }
        return false
    }

    fun toggleInfo() {
        if (infoVisible.value) {
            infoHideJob?.cancel()
            infoVisible.value = false
        } else {
            showInfo()
        }
    }

    fun selectChannel(channel: Channel) {
        if (state.value.channels.any { it.id == channel.id }) {
            selectChannelId(channel.id, remember = true)
            setOverlay(Overlay.NONE)
        }
    }

    fun nextChannel(direction: Int) {
        val channels = state.value.channels
        if (channels.isEmpty()) return
        val adjusted = if (state.value.settings.channelReversal) -direction else direction
        val current = channels.indexOfFirst { it.id == selectedChannelId.value }.takeIf { it >= 0 } ?: 0
        val next = (current + adjusted).mod(channels.size)
        selectChannelId(channels[next].id, remember = true)
    }

    fun selectChannelNumber(number: Int) {
        val channels = state.value.channels
        if (number !in 1..channels.size) {
            message.value = "频道号 $number 不存在"
            return
        }
        selectChannelId(channels[number - 1].id, remember = true)
        setOverlay(Overlay.NONE)
        showInfo()
    }

    fun setFavorite(channel: Channel, favorite: Boolean) {
        viewModelScope.launch {
            repository.setFavorite(channel.id, favorite)
            message.value = if (favorite) "已收藏：${channel.title}" else "已取消收藏：${channel.title}"
        }
    }

    fun rememberSourceResult(result: SourcePlaybackResult) {
        viewModelScope.launch { repository.rememberSourceResult(result) }
    }

    fun blockSource(channel: Channel, source: com.youtv.app.domain.model.StreamSource) {
        viewModelScope.launch {
            repository.blockSource(channel, source)
            message.value = "已屏蔽 ${channel.title} 的源${source.order + 1}"
        }
    }

    fun restoreBlockedSource(source: BlockedSource) {
        viewModelScope.launch {
            repository.restoreBlockedSource(source)
            message.value = "已恢复 ${source.channelName} 的源${source.sourceNumber}"
        }
    }

    fun clearBlockedSources() {
        viewModelScope.launch {
            repository.clearBlockedSources()
            message.value = "已恢复全部屏蔽来源"
        }
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

    fun onPlaybackStable() {
        refreshBackgroundIfDue()
    }

    private fun refreshBackgroundIfDue() {
        if (backgroundRefreshJob?.isActive == true) return
        backgroundRefreshJob = viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            val now = System.currentTimeMillis()
            if (settings.sourceMode == PlaylistSourceMode.URL && settings.configUrl.isNotBlank() &&
                now - settings.lastSubscriptionRefreshAt >= BACKGROUND_REFRESH_INTERVAL_MILLIS
            ) {
                if (updateFromUrl(settings.configUrl, showResult = false)) {
                    container.settingsRepository.setLastSubscriptionRefreshAt(System.currentTimeMillis())
                }
            }
            val latest = container.settingsRepository.settings.first()
            if (latest.epgUrl.isNotBlank() &&
                now - latest.lastEpgRefreshAt >= BACKGROUND_REFRESH_INTERVAL_MILLIS
            ) {
                if (container.epgRepository.refresh(latest.epgUrl)) {
                    container.settingsRepository.setLastEpgRefreshAt(System.currentTimeMillis())
                }
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
        val settings = container.settingsRepository.settings.first()
        val shouldOpenSetup = settings.configUrl.isBlank() && !hasSavedTextSource()
        migrateLegacyChannels()
        if (shouldOpenSetup) {
            setOverlay(Overlay.SETTINGS)
        }
        val initializedSettings = container.settingsRepository.settings.first()
        val channels = repository.observeGroups().first().flatMap { it.channels }
        val initialId = resolveInitialChannelId(
            channelIds = channels.map { it.id },
            defaultChannel = initializedSettings.defaultChannel,
            lastChannelId = initializedSettings.lastChannelId,
            legacyPosition = container.legacyPreferences.position,
        )
        selectedChannelId.value = initialId
        if (initialId != null) container.settingsRepository.setLastChannelId(initialId)
    }

    private fun selectChannelId(channelId: String, remember: Boolean) {
        selectedChannelId.value = channelId
        if (remember) viewModelScope.launch {
            container.settingsRepository.setLastChannelId(channelId)
        }
    }

    private fun setOverlay(value: Overlay) {
        overlay.value = value
        if (value != Overlay.NONE && value != Overlay.SETTINGS) {
            scheduleOverlayTimeout()
        } else {
            overlayTimeoutJob?.cancel()
            overlayTimeoutJob = null
        }
    }

    private fun scheduleOverlayTimeout() {
        overlayTimeoutJob?.cancel()
        overlayTimeoutJob = viewModelScope.launch {
            delay(OVERLAY_TIMEOUT_MILLIS)
            if (overlay.value != Overlay.NONE && overlay.value != Overlay.SETTINGS) {
                setOverlay(Overlay.NONE)
            }
        }
    }

    private suspend fun updateFromUrl(url: String, showResult: Boolean): Boolean {
        val content = withContext(Dispatchers.IO) {
            runCatching {
                HttpClient.getClientWithProxy().newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.byteStream()?.use { input ->
                        PlaylistTextDecoder.decode(input.readLimited(MAX_PLAYLIST_BYTES))
                    }
                }
            }.getOrNull()
        }
        if (content.isNullOrBlank()) {
            if (showResult) message.value = "订阅下载失败，继续使用上次频道"
            return false
        }
        val report = withContext(Dispatchers.IO) { repository.importPlaylist(content) }
        if (report.isSuccess) {
            withContext(Dispatchers.IO) {
                File(getApplication<Application>().filesDir, URL_PLAYLIST_FILE).writeText(content)
                File(getApplication<Application>().filesDir, ACTIVE_PLAYLIST_FILE).writeText(content)
            }
            container.settingsRepository.setConfigUrl(url)
            container.settingsRepository.setSourceMode(PlaylistSourceMode.URL)
            container.settingsRepository.setLastSubscriptionRefreshAt(System.currentTimeMillis())
            saveMetadata(report.updatedAt)
            if (showResult) message.value = "订阅已更新：${report.imported} 个频道"
            return true
        } else if (showResult) {
            message.value = report.issues.firstOrNull()?.message ?: "订阅解析失败"
        }
        return false
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
        val hasLegacyFile = legacyFile.exists() && legacyFile.length() > 0
        val content = withContext(Dispatchers.IO) {
            if (hasLegacyFile) {
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
            if (hasLegacyFile && !textFile.exists()) withContext(Dispatchers.IO) { textFile.writeText(content) }
            saveMetadata(report.updatedAt)
        }
        message.value = if (!report.isSuccess) "旧频道数据迁移失败，已保留原文件" else null
    }

    private suspend fun hasSavedTextSource(): Boolean = withContext(Dispatchers.IO) {
        listOf(TEXT_PLAYLIST_FILE, ACTIVE_PLAYLIST_FILE).any { name ->
            File(getApplication<Application>().filesDir, name).let { it.exists() && it.length() > 0 }
        }
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

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "订阅文件超过大小限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun List<ChannelGroup>.withEpgLogos(lookup: EpgChannelLookup): List<ChannelGroup> =
        map { group ->
            group.copy(channels = group.channels.map { channel ->
                if (channel.logo.isNotBlank()) {
                    channel
                } else {
                    channel.copy(logo = lookup.logoFor(channel.name.ifEmpty { channel.title }))
                }
            })
        }

    private companion object {
        const val INFO_DISPLAY_MILLIS = 5_000L
        const val OVERLAY_TIMEOUT_MILLIS = 10_000L
        const val BACKGROUND_REFRESH_INTERVAL_MILLIS = 24L * 60 * 60 * 1_000
        const val BACKGROUND_REFRESH_FALLBACK_MILLIS = 30_000L
        const val MAX_PLAYLIST_BYTES = 16 * 1024 * 1024
        const val ACTIVE_PLAYLIST_FILE = "channels.txt"
        const val TEXT_PLAYLIST_FILE = "playlist-text.txt"
        const val URL_PLAYLIST_FILE = "playlist-url.txt"
    }
}

private data class MainData(
    val groups: List<ChannelGroup>,
    val settings: AppSettings,
    val epgLookup: EpgChannelLookup,
    val blockedSources: List<BlockedSource>,
)

private data class CatalogData(
    val groups: List<ChannelGroup>,
    val epgLookup: EpgChannelLookup,
)
