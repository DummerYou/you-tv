package com.youtv.app.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.youtv.app.domain.model.Channel
import com.youtv.app.player.PlaybackState
import com.youtv.app.player.PlayerController
import com.youtv.app.domain.model.SourceAddressType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TvApp(
    viewModel: MainViewModel,
    playerController: PlayerController,
    remoteAddress: String?,
    onImportFile: () -> Unit,
    onExit: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val playback by playerController.state.collectAsState()
    var channelDigits by remember { mutableStateOf("") }
    var volumeUi by remember { mutableStateOf<VolumeUi?>(null) }

    LaunchedEffect(channelDigits) {
        if (channelDigits.isNotEmpty()) {
            delay(CHANNEL_NUMBER_DELAY_MILLIS)
            val channelNumber = channelDigits.toIntOrNull()
            channelDigits = ""
            channelNumber?.let(viewModel::selectChannelNumber)
        }
    }

    LaunchedEffect(volumeUi?.eventId) {
        val eventId = volumeUi?.eventId ?: return@LaunchedEffect
        delay(VOLUME_DISPLAY_MILLIS)
        if (volumeUi?.eventId == eventId) volumeUi = null
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(MESSAGE_DISPLAY_MILLIS)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.currentChannel?.id) {
        state.currentChannel?.let { playerController.play(it, it.preferredSource) }
    }

    BackHandler {
        if (!viewModel.closeOverlay()) onExit()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .playbackGestures(
                enabled = state.overlay == Overlay.NONE,
                onPreviousChannel = { viewModel.nextChannel(-1) },
                onNextChannel = { viewModel.nextChannel(1) },
                onShowChannels = { viewModel.showOverlay(Overlay.CHANNELS) },
                onShowSettings = { viewModel.showOverlay(Overlay.SETTINGS) },
                onShowProgram = { viewModel.showOverlay(Overlay.PROGRAM) },
                onShowInfo = viewModel::showInfo,
                onVolumeChanged = { current, maximum ->
                    volumeUi = VolumeUi(current, maximum, System.nanoTime())
                },
            )
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (!viewModel.closeOverlay()) onExit()
                    return@onPreviewKeyEvent true
                }
                if (state.overlay != Overlay.NONE) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        viewModel.nextChannel(-1); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        viewModel.nextChannel(1); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        viewModel.showOverlay(Overlay.CHANNELS); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { viewModel.showOverlay(Overlay.PROGRAM); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { viewModel.showOverlay(Overlay.SETTINGS); true }
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_SETTINGS,
                    KeyEvent.KEYCODE_BOOKMARK,
                    KeyEvent.KEYCODE_HELP -> {
                        viewModel.showOverlay(Overlay.SETTINGS); true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (!state.settings.channelNumber) return@onPreviewKeyEvent false
                        val digit = event.nativeKeyEvent.keyCode - KeyEvent.KEYCODE_0
                        channelDigits = (channelDigits + digit).takeLast(3)
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> PlayerView(context).apply {
                useController = false
                player = playerController.player
                keepScreenOn = true
            } },
            update = { it.player = playerController.player },
        )

        PlaybackStatus(playback, playerController::retry)

        if (state.settings.showTime) {
            TimeDisplay(
                showSeconds = state.settings.displaySeconds,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        if (state.infoVisible) {
            ChannelInfoPanel(state, playback)
        }

        volumeUi?.let { VolumeIndicator(it, Modifier.align(Alignment.Center)) }

        if (channelDigits.isNotEmpty()) {
            Text(
                channelDigits,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(36.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                fontSize = 34.sp,
            )
        }

        AnimatedVisibility(state.overlay == Overlay.CHANNELS) {
            ChannelDrawer(state, viewModel::selectChannel, viewModel::setFavorite)
        }
        AnimatedVisibility(state.overlay == Overlay.PROGRAM) {
            ProgramPanel(
                state = state,
                playback = playback,
                onSelectSource = playerController::selectSource,
            )
        }
        AnimatedVisibility(
            visible = state.overlay == Overlay.SETTINGS,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SettingsPanel(
                state = state,
                remoteAddress = remoteAddress,
                onImportFile = onImportFile,
                onUseTextSource = viewModel::useTextSource,
                onUseUrlSource = viewModel::refreshSubscription,
                onChannelReversal = viewModel::setChannelReversal,
                onChannelNumber = viewModel::setChannelNumber,
                onShowTime = viewModel::setShowTime,
                onDisplaySeconds = viewModel::setDisplaySeconds,
                onRepeatInfo = viewModel::setRepeatInfo,
                onDefaultFavorite = viewModel::setDefaultFavorite,
                onShowAllChannels = viewModel::setShowAllChannels,
                onSoftDecode = viewModel::setSoftDecode,
                onCompactMenu = viewModel::setCompactMenu,
                onBootStartup = viewModel::setBootStartup,
            )
        }
        state.message?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                color = Color(0xE61A1F24),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color.White,
                    fontSize = 15.sp,
                )
            }
        }
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ChannelDrawer(
    state: MainUiState,
    onSelect: (Channel) -> Unit,
    onFavorite: (Channel, Boolean) -> Unit,
) {
    val maxChars = state.menuGroups.flatMap { it.channels }
        .maxOfOrNull {
            val sourceChars = if (it.sources.size > 1) "${it.sources.size}源".length + 1 else 0
            it.title.length + sourceChars
        }
        ?.coerceIn(8, 20) ?: 8
    val panelWidth = (maxChars * 14 + 34).dp
    Surface(
        modifier = Modifier.fillMaxHeight().width(panelWidth),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                state.menuGroups.forEach { group ->
                    val visibleChannels = group.channels
                    item(group.name) {
                        Text(
                            group.name,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 5.dp),
                        )
                    }
                    items(visibleChannels, key = { "${group.name}:${it.id}" }) { channel ->
                        FocusableRow(
                            channel = channel,
                            playing = channel.id == state.currentChannel?.id,
                            requestInitialFocus = channel.id == state.currentChannel?.id,
                            onFavorite = { onFavorite(channel, !channel.favorite) },
                        ) { onSelect(channel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusableRow(
    channel: Channel,
    playing: Boolean,
    requestInitialFocus: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val metaText = listOfNotNull(
        "${channel.sources.size}源".takeIf { channel.sources.size > 1 },
        "★".takeIf { channel.favorite },
    ).joinToString(" ")
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) runCatching { requester.requestFocus() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(requester)
            .focusable()
            .border(if (focused) 1.5.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
            .background(if (playing) Color(0x5534CDB8) else Color.Transparent, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onClick(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onFavorite(); true }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel.title,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (metaText.isNotEmpty()) {
            Spacer(Modifier.width(7.dp))
            Text(
                metaText,
                color = if (channel.favorite) MaterialTheme.colorScheme.primary else Color.LightGray,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PlaybackStatus(state: PlaybackState, retry: () -> Unit) {
    when (state) {
        PlaybackState.Idle -> Unit
        is PlaybackState.Preparing, is PlaybackState.Buffering -> CircularProgressIndicator(Modifier.padding(40.dp))
        is PlaybackState.Playing -> Unit
        is PlaybackState.Failed -> {
            InfoPanel(
                "播放失败",
                "${state.addressType} · ${state.message}\n按确认键打开频道列表，切换其他来源或重试",
                Alignment.Center,
            )
        }
    }
}

@Composable
private fun ChannelInfoPanel(state: MainUiState, playback: PlaybackState) {
    val channel = state.currentChannel ?: return
    val sourceText = when (playback) {
        is PlaybackState.Playing -> "来源 ${playback.sourceIndex + 1}/${channel.sources.size}"
        is PlaybackState.Preparing -> "正在连接来源 ${playback.sourceIndex + 1}"
        is PlaybackState.Buffering -> "正在缓冲来源 ${playback.sourceIndex + 1}"
        else -> "${channel.sources.size} 个播放源"
    }
    val program = state.programs.firstOrNull()?.title ?: "暂无节目单"
    Surface(
        modifier = Modifier.padding(32.dp).width(560.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(channel.title, fontSize = 30.sp)
            Spacer(Modifier.height(8.dp))
            Text(program, fontSize = 20.sp, color = Color.LightGray)
            Text(sourceText, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VolumeIndicator(volume: VolumeUi, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(360.dp),
        color = Color(0xDD101418),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("音量 ${volume.current}/${volume.maximum}", fontSize = 22.sp)
            LinearProgressIndicator(
                progress = { volume.current.toFloat() / volume.maximum.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TimeDisplay(showSeconds: Boolean, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showSeconds) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (showSeconds) 1_000 else 30_000)
        }
    }
    Text(
        SimpleDateFormat(if (showSeconds) "HH:mm:ss" else "HH:mm", Locale.getDefault()).format(Date(now)),
        modifier = modifier
            .padding(16.dp)
            .background(Color(0x99000000), RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        fontSize = 13.sp,
    )
}

@Composable
private fun SettingsPanel(
    state: MainUiState,
    remoteAddress: String?,
    onImportFile: () -> Unit,
    onUseTextSource: () -> Unit,
    onUseUrlSource: () -> Unit,
    onChannelReversal: (Boolean) -> Unit,
    onChannelNumber: (Boolean) -> Unit,
    onShowTime: (Boolean) -> Unit,
    onDisplaySeconds: (Boolean) -> Unit,
    onRepeatInfo: (Boolean) -> Unit,
    onDefaultFavorite: (Boolean) -> Unit,
    onShowAllChannels: (Boolean) -> Unit,
    onSoftDecode: (Boolean) -> Unit,
    onCompactMenu: (Boolean) -> Unit,
    onBootStartup: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxHeight().width(292.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
            Text("设置", fontSize = 18.sp, modifier = Modifier.padding(start = 5.dp, bottom = 4.dp))
            }
            item {
            SettingLine("换台反转", state.settings.channelReversal) { onChannelReversal(!state.settings.channelReversal) }
            }
            item {
            SettingLine("数字键选台", state.settings.channelNumber) { onChannelNumber(!state.settings.channelNumber) }
            }
            item {
            SettingLine("显示时间", state.settings.showTime) { onShowTime(!state.settings.showTime) }
            }
            item {
            SettingLine("显示秒数", state.settings.displaySeconds) { onDisplaySeconds(!state.settings.displaySeconds) }
            }
            item {
            SettingLine("重复显示频道信息", state.settings.repeatInfo) { onRepeatInfo(!state.settings.repeatInfo) }
            }
            item {
            SettingLine("默认进入收藏", state.settings.defaultFavorite) { onDefaultFavorite(!state.settings.defaultFavorite) }
            }
            item {
            SettingLine("显示全部频道", state.settings.showAllChannels) { onShowAllChannels(!state.settings.showAllChannels) }
            }
            item {
            SettingLine("软解优先（重启生效）", state.settings.softDecode) { onSoftDecode(!state.settings.softDecode) }
            }
            item {
            SettingLine("紧凑频道菜单", state.settings.compactMenu) { onCompactMenu(!state.settings.compactMenu) }
            }
            item {
            SettingLine("开机自动启动", state.settings.bootStartup) { onBootStartup(!state.settings.bootStartup) }
            }
            item {
            Text(
                "当前源：${if (state.settings.sourceMode.name == "URL") "地址源" else "文本源"}",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            )
            }
            item {
            Text(
                "更新时间：${state.settings.playlistUpdatedAt.ifEmpty { "未提供" }}",
                color = Color.LightGray, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
            }
            item {
            DetailText("订阅：${state.settings.configUrl.ifEmpty { "未设置" }}")
            }
            item {
            DetailText("EPG：${state.settings.epgUrl}")
            }
            item {
            DetailText("代理：${state.settings.proxy.ifEmpty { "未设置" }}")
            }
            item {
            ActionRow("导入文件并使用文本源", onImportFile)
            }
            item {
            ActionRow("使用已保存的文本源", onUseTextSource)
            }
            item {
            ActionRow("使用地址源并立即更新", onUseUrlSource)
            }
            item {
            Text(
                remoteAddress?.let { "远程配置：$it（10 分钟后关闭）" }
                    ?: "远程配置已关闭",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            )
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(if (focused) 1.5.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    it.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
                ) { onClick(); true } else false
            },
        fontSize = 14.sp,
    )
}

@Composable
private fun SettingLine(label: String, checked: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(if (focused) 1.5.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    it.nativeKeyEvent.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
                ) { onClick(); true } else false
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.height(30.dp).width(42.dp),
        )
    }
}

@Composable
private fun ProgramPanel(
    state: MainUiState,
    playback: PlaybackState,
    onSelectSource: (Int) -> Unit,
) {
    val channel = state.currentChannel
    val sources = channel?.sources.orEmpty()
    val currentSource = when (playback) {
        is PlaybackState.Preparing -> playback.sourceIndex
        is PlaybackState.Buffering -> playback.sourceIndex
        is PlaybackState.Playing -> playback.sourceIndex
        else -> channel?.preferredSource ?: 0
    }.coerceIn(0, (sources.size - 1).coerceAtLeast(0))
    val requester = remember { FocusRequester() }
    val programListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedProgram by remember(channel?.id) { mutableStateOf(0) }
    LaunchedEffect(channel?.id) { runCatching { requester.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 260.dp, max = 390.dp)
                .focusRequester(requester)
                .focusable()
                .onPreviewKeyEvent {
                    if (it.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    when (it.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (sources.size <= 1) return@onPreviewKeyEvent false
                            onSelectSource((currentSource - 1).mod(sources.size)); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (sources.size <= 1) return@onPreviewKeyEvent false
                            onSelectSource((currentSource + 1).mod(sources.size)); true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            selectedProgram = (selectedProgram - 1).coerceAtLeast(0)
                            scope.launch { programListState.animateScrollToItem(selectedProgram) }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            selectedProgram = (selectedProgram + 1).coerceAtMost((state.programs.size - 1).coerceAtLeast(0))
                            scope.launch { programListState.animateScrollToItem(selectedProgram) }
                            true
                        }
                        else -> false
                    }
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(channel?.title.orEmpty(), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sources.size > 1) {
                    Row(
                        modifier = Modifier
                            .padding(top = 7.dp, bottom = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        sources.forEachIndexed { index, source ->
                            SourceChip(
                                label = "源${index + 1} ${sourceTypeLabel(source.addressType)}",
                                selected = index == currentSource,
                                onClick = { onSelectSource(index) },
                                onPrevious = { onSelectSource((currentSource - 1).mod(sources.size)) },
                                onNext = { onSelectSource((currentSource + 1).mod(sources.size)) },
                            )
                        }
                    }
                }
                LazyColumn(state = programListState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (state.programs.isEmpty()) {
                        item { Text("暂无节目单", color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(6.dp)) }
                    } else {
                        items(state.programs.take(30)) { program ->
                            ProgramRow(program, selected = state.programs.indexOf(program) == selectedProgram)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .widthIn(min = 58.dp)
            .height(32.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color(0xFF343B42),
                RoundedCornerShape(7.dp),
            )
            .border(if (focused) 1.5.dp else 0.dp, Color.White, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onPrevious(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onNext(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onClick(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 11.sp,
            color = if (selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProgramRow(program: com.youtv.app.domain.model.Program, selected: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused || selected) Color(0x3334CDB8) else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(formatProgramTime(program.beginTime), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Text(program.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun sourceTypeLabel(type: SourceAddressType): String = when (type) {
    SourceAddressType.IPV4 -> "IPv4"
    SourceAddressType.IPV6 -> "IPv6"
    SourceAddressType.HOSTNAME -> "域名"
    SourceAddressType.UNKNOWN -> "其他"
}

private fun formatProgramTime(value: Int): String {
    val millis = if (value < 10_000_000_000L) value.toLong() * 1000 else value.toLong()
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}

@Composable
private fun DetailText(value: String) {
    Text(
        value,
        color = Color.LightGray,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun InfoPanel(title: String, body: String, alignment: Alignment) {
    Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
        Surface(
            modifier = Modifier.width(520.dp).padding(32.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(title, fontSize = 30.sp)
                Spacer(Modifier.height(12.dp))
                Text(body, fontSize = 19.sp, color = Color.LightGray)
            }
        }
    }
}

private data class VolumeUi(val current: Int, val maximum: Int, val eventId: Long)

private const val CHANNEL_NUMBER_DELAY_MILLIS = 1_500L
private const val VOLUME_DISPLAY_MILLIS = 2_000L
private const val MESSAGE_DISPLAY_MILLIS = 3_000L
