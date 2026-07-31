package com.youtv.app.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.ChannelGroup
import com.youtv.app.domain.model.BlockedSource
import com.youtv.app.player.PlaybackState
import com.youtv.app.player.PlayerController
import com.youtv.app.player.SourceTestResult
import com.youtv.app.player.SourceTestState
import com.youtv.app.player.SourceTestStatus
import com.youtv.app.domain.model.SourceAddressType
import com.youtv.app.domain.model.SourceHealthStatus
import com.youtv.app.domain.model.SourceQualityAge
import com.youtv.app.domain.model.SourceQualityPolicy
import com.youtv.app.domain.model.SourceSelectionPolicy
import com.youtv.app.domain.model.StreamSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
    val sourceTest by playerController.sourceTestState.collectAsState()
    val playbackFocusRequester = remember { FocusRequester() }
    var channelDigits by remember { mutableStateOf("") }
    var volumeUi by remember { mutableStateOf<VolumeUi?>(null) }

    LaunchedEffect(state.overlay) {
        if (state.overlay == Overlay.NONE) {
            runCatching { playbackFocusRequester.requestFocus() }
        }
        if (state.overlay != Overlay.PROGRAM && sourceTest !is SourceTestState.Idle) {
            playerController.clearSourceTest()
        }
    }

    LaunchedEffect(sourceTest, state.overlay) {
        if (state.overlay != Overlay.PROGRAM || sourceTest is SourceTestState.Idle) {
            return@LaunchedEffect
        }
        viewModel.notifyOverlayInteraction()
        while (sourceTest is SourceTestState.Running) {
            delay(SOURCE_TEST_OVERLAY_KEEP_ALIVE_MILLIS)
            viewModel.notifyOverlayInteraction()
        }
    }

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

    LaunchedEffect(state.loading, state.currentChannel?.id) {
        if (!state.loading) state.currentChannel?.let { playerController.play(it, it.preferredSource) }
    }
    LaunchedEffect(state.currentChannel) {
        if (!state.loading) state.currentChannel?.let(playerController::updateChannelSnapshot)
    }
    LaunchedEffect((playback as? PlaybackState.Playing)?.channel?.id) {
        if (playback is PlaybackState.Playing) {
            delay(5_000L)
            viewModel.onPlaybackStable()
        }
    }

    BackHandler {
        if (!viewModel.closeOverlay()) onExit()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state.overlay) {
                if (state.overlay != Overlay.NONE && state.overlay != Overlay.SETTINGS) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && !it.previousPressed }) {
                                viewModel.notifyOverlayInteraction()
                            }
                        }
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    state.overlay != Overlay.NONE
                ) {
                    viewModel.notifyOverlayInteraction()
                }
                false
            }
            .focusRequester(playbackFocusRequester)
            .focusable()
            .background(Color.Black)
            .playbackGestures(
                enabled = state.overlay == Overlay.NONE,
                onPreviousChannel = { viewModel.nextChannel(-1) },
                onNextChannel = { viewModel.nextChannel(1) },
                onShowChannels = { viewModel.showOverlay(Overlay.CHANNELS) },
                onShowFavorites = { viewModel.showOverlay(Overlay.FAVORITES) },
                onShowSettings = { viewModel.showOverlay(Overlay.SETTINGS) },
                onShowProgram = { viewModel.showOverlay(Overlay.PROGRAM) },
                onShowInfo = viewModel::toggleInfo,
                onVolumeChanged = { current, maximum ->
                    volumeUi = VolumeUi(current, maximum, System.nanoTime())
                },
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.keyCode == KeyEvent.KEYCODE_ESCAPE && native.action == KeyEvent.ACTION_DOWN) {
                    if (native.repeatCount == 0 && !viewModel.closeOverlay()) onExit()
                    return@onPreviewKeyEvent true
                }
                if (state.overlay != Overlay.NONE) return@onPreviewKeyEvent false
                if (native.action != KeyEvent.ACTION_DOWN) {
                    false
                } else when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (native.repeatCount > 0) return@onPreviewKeyEvent true
                        viewModel.nextChannel(-1); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (native.repeatCount > 0) return@onPreviewKeyEvent true
                        viewModel.nextChannel(1); true
                    }
                    in CONFIRM_KEY_CODES -> {
                        if (native.repeatCount > 0) return@onPreviewKeyEvent true
                        viewModel.showOverlay(Overlay.CHANNELS); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (native.repeatCount == 0) viewModel.showOverlay(Overlay.PROGRAM)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (native.repeatCount == 0) viewModel.showOverlay(Overlay.FAVORITES)
                        true
                    }
                    KeyEvent.KEYCODE_INFO -> {
                        if (native.repeatCount == 0) viewModel.toggleInfo()
                        true
                    }
                    KeyEvent.KEYCODE_GUIDE -> {
                        if (native.repeatCount == 0) viewModel.showOverlay(Overlay.PROGRAM)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        if (native.repeatCount == 0) playerController.resume()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (native.repeatCount == 0) playerController.pause()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (native.repeatCount == 0) playerController.togglePlayPause()
                        true
                    }
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_SETTINGS,
                    KeyEvent.KEYCODE_BOOKMARK,
                    KeyEvent.KEYCODE_HELP -> {
                        if (native.repeatCount == 0) viewModel.showOverlay(Overlay.SETTINGS)
                        true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                        if (!state.settings.channelNumber) return@onPreviewKeyEvent false
                        if (native.repeatCount > 0) return@onPreviewKeyEvent true
                        val digit = if (native.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                            native.keyCode - KeyEvent.KEYCODE_0
                        } else {
                            native.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                        }
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
                    .background(TvGlassStrong, RoundedCornerShape(10.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                fontSize = 34.sp,
            )
        }

        AnimatedVisibility(state.overlay == Overlay.CHANNELS) {
            ChannelDrawer(
                title = null,
                groups = state.menuGroups,
                currentChannel = state.currentChannel,
                emptyText = "暂无频道",
                onSelect = viewModel::selectChannel,
                onFavorite = viewModel::setFavorite,
            )
        }
        AnimatedVisibility(state.overlay == Overlay.FAVORITES) {
            ChannelDrawer(
                title = "收藏",
                groups = listOf(ChannelGroup("收藏频道", state.favoriteSnapshotChannels)),
                currentChannel = state.currentChannel,
                emptyText = "暂无收藏频道",
                onSelect = viewModel::selectChannel,
                onFavorite = viewModel::setFavorite,
            )
        }
        AnimatedVisibility(state.overlay == Overlay.PROGRAM) {
            SourceSelectorPanel(
                state = state,
                playback = playback,
                sourceTest = sourceTest,
                onSelectSource = playerController::selectSource,
                onStartSourceTest = playerController::startSourceTest,
                onStopSourceTest = { playerController.stopSourceTest() },
                onBlockSource = viewModel::blockSource,
                onClose = {
                    playerController.clearSourceTest()
                    viewModel.closeOverlay()
                },
            )
        }
        AnimatedVisibility(
            visible = state.overlay == Overlay.SETTINGS,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SettingsPanel(
                state = state,
                remoteAddress = remoteAddress,
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
                onRestoreBlockedSource = viewModel::restoreBlockedSource,
                onClearBlockedSources = viewModel::clearBlockedSources,
            )
        }
        state.message?.let {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                color = TvGlassPanel,
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
    title: String?,
    groups: List<ChannelGroup>,
    currentChannel: Channel?,
    emptyText: String,
    onSelect: (Channel) -> Unit,
    onFavorite: (Channel, Boolean) -> Unit,
) {
    val visibleGroups = groups.filter { it.channels.isNotEmpty() }
    val focusTarget = visibleGroups.asSequence()
        .flatMap { group -> group.channels.asSequence().map { group.name to it } }
        .firstOrNull { (_, channel) -> channel.id == currentChannel?.id }
        ?: visibleGroups.firstOrNull()?.channels?.firstOrNull()?.let { visibleGroups.first().name to it }
    val maxChars = visibleGroups.flatMap { it.channels }
        .maxOfOrNull {
            val sourceChars = if (it.sources.size > 1) "${it.sources.size}源".length + 1 else 0
            it.title.length + sourceChars
        }
        ?.coerceIn(8, 20) ?: 8
    val panelWidth = (maxChars * 14 + 34).dp
    val listState = rememberLazyListState()
    var initialTargetId by remember { mutableStateOf<String?>(null) }
    var initialFocusHandled by remember { mutableStateOf(false) }
    var focusedChannelId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusTarget?.second?.id) {
        if (initialTargetId == null) initialTargetId = focusTarget?.second?.id
    }
    val targetIndex = remember(visibleGroups, initialTargetId) {
        val targetId = initialTargetId
        var index = 0
        visibleGroups.forEach { group ->
            val channelIndex = group.channels.indexOfFirst { it.id == targetId }
            if (channelIndex >= 0) {
                return@remember index + 1 + channelIndex
            }
            index += 1 + group.channels.size
        }
        0
    }
    LaunchedEffect(initialTargetId) {
        if (initialTargetId != null && !initialFocusHandled) {
            runCatching { listState.scrollToItem(targetIndex) }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(panelWidth),
        color = TvGlassPanel,
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            title?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 5.dp, bottom = 5.dp),
                )
            }
            if (visibleGroups.isEmpty()) {
                Text(
                    emptyText,
                    color = TvMutedText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    visibleGroups.forEach { group ->
                    val visibleChannels = group.channels
                    item(group.name) {
                        Text(
                            group.name,
                            color = TvMutedText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 5.dp),
                        )
                    }
                    items(visibleChannels, key = { "${group.name}:${it.id}" }) { channel ->
                        FocusableRow(
                            channel = channel,
                            playing = channel.id == currentChannel?.id,
                            requestInitialFocus = !initialFocusHandled && channel.id == initialTargetId,
                            focusedFromDrawer = channel.id == focusedChannelId,
                            onFocused = {
                                focusedChannelId = channel.id
                                if (channel.id == initialTargetId) initialFocusHandled = true
                            },
                            onFavorite = { onFavorite(channel, !channel.favorite) },
                        ) { onSelect(channel) }
                    }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusableRow(
    channel: Channel,
    playing: Boolean,
    requestInitialFocus: Boolean,
    focusedFromDrawer: Boolean,
    onFocused: () -> Unit,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var confirmPressed by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val metaText = listOfNotNull(
        "${channel.sources.size}源".takeIf { channel.sources.size > 1 },
        "★".takeIf { channel.favorite },
    ).joinToString(" ")
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) runCatching { requester.requestFocus() }
    }
    val visuallyFocused = focused || focusedFromDrawer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
                if (!it.isFocused) {
                    confirmPressed = false
                    longPressHandled = false
                }
            }
            .focusRequester(requester)
            .onPreviewKeyEvent {
                val native = it.nativeKeyEvent
                when {
                    native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) onFavorite()
                        true
                    }
                    native.keyCode in CONFIRM_KEY_CODES -> {
                        when (native.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (native.repeatCount == 0) {
                                    confirmPressed = true
                                    longPressHandled = false
                                } else if (confirmPressed && !longPressHandled) {
                                    longPressHandled = true
                                    onFavorite()
                                }
                            }
                            KeyEvent.ACTION_UP -> {
                                if (confirmPressed && !longPressHandled) onClick()
                                confirmPressed = false
                                longPressHandled = false
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .graphicsLayer {
                scaleX = if (visuallyFocused) 1.015f else 1f
                scaleY = if (visuallyFocused) 1.015f else 1f
                shadowElevation = if (visuallyFocused) 18f else 0f
                shape = RoundedCornerShape(5.dp)
            }
            .background(
                when {
                    visuallyFocused -> TvFocusFill
                    playing -> TvPlayingFill
                    else -> TvRowFill
                },
                RoundedCornerShape(5.dp),
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onFavorite,
            )
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(
            name = channel.name.ifBlank { channel.title },
            url = channel.logo,
            modifier = Modifier.size(18.dp),
            cornerRadius = 4,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            channel.title,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 14.sp,
            color = when {
                visuallyFocused -> TvAccentText
                playing -> TvAccent
                else -> Color.White
            },
            fontWeight = if (visuallyFocused || playing) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (metaText.isNotEmpty()) {
            Spacer(Modifier.width(7.dp))
            Text(
                metaText,
                color = when {
                    visuallyFocused -> TvAccentText.copy(alpha = 0.72f)
                    channel.favorite -> TvAccent
                    else -> TvMutedText
                },
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PlaybackStatus(state: PlaybackState, retry: () -> Unit) {
    when (state) {
        PlaybackState.Idle -> Unit
        is PlaybackState.Preparing -> LoadingChannelPanel(
            channel = state.channel,
            sourceIndex = state.sourceIndex,
            status = "正在连接",
        )
        is PlaybackState.Buffering -> LoadingChannelPanel(
            channel = state.channel,
            sourceIndex = state.sourceIndex,
            status = "正在缓冲",
        )
        is PlaybackState.Playing -> Unit
        is PlaybackState.Failed -> {
            val address = when (state.addressType) {
                SourceAddressType.IPV4 -> "IPv4"
                SourceAddressType.IPV6 -> "IPv6"
                SourceAddressType.HOSTNAME -> "域名"
                SourceAddressType.UNKNOWN -> "播放源"
            }
            val attempted = if (state.attemptedSources > 1) {
                "\n已尝试 ${state.attemptedSources} 个来源"
            } else {
                ""
            }
            InfoPanel(
                "播放失败",
                "$address · ${state.message}$attempted\n按左键选择来源，按确认键打开频道列表",
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
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth(0.86f)
            .padding(24.dp),
        color = TvGlassPanel,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(channel.title, fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(program, fontSize = 20.sp, color = TvSoftText)
            Text(sourceText, fontSize = 16.sp, color = TvAccent)
        }
    }
}

@Composable
private fun LoadingChannelPanel(
    channel: Channel,
    sourceIndex: Int,
    status: String,
) {
    val sourceText = if (channel.sources.size > 1) {
        "$status · 来源 ${sourceIndex + 1}/${channel.sources.size}"
    } else {
        status
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.82f),
            color = TvGlassPanel,
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                modifier = Modifier.padding(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(
                    name = channel.name.ifBlank { channel.title },
                    url = channel.logo,
                    modifier = Modifier.size(52.dp),
                    cornerRadius = 10,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        channel.title,
                        fontSize = 20.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        sourceText,
                        color = TvAccent,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = TvAccent,
                    trackColor = TvProgressTrack,
                )
            }
        }
    }
}

@Composable
private fun VolumeIndicator(volume: VolumeUi, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth(0.82f),
        color = TvGlassPanel,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("音量 ${volume.current}/${volume.maximum}", fontSize = 22.sp, color = Color.White)
            LinearProgressIndicator(
                progress = { volume.current.toFloat() / volume.maximum.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = TvAccent,
                trackColor = TvProgressTrack,
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
            .background(TvGlassStrong, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        fontSize = 13.sp,
    )
}

@Composable
private fun SettingsPanel(
    state: MainUiState,
    remoteAddress: String?,
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
    onRestoreBlockedSource: (BlockedSource) -> Unit,
    onClearBlockedSources: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(min = 280.dp, max = 340.dp)
            .fillMaxWidth(),
        color = TvGlassPanel,
    ) {
        LazyColumn(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
            Text("设置", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp, bottom = 4.dp))
            }
            item {
            Text(
                "当前源：${if (state.settings.sourceMode.name == "URL") "地址源" else "文本源"}",
                color = TvAccent,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            )
            }
            item {
            Text(
                "更新时间：${state.settings.playlistUpdatedAt.ifEmpty { "未提供" }}",
                color = TvMutedText, fontSize = 11.sp,
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
            ActionRow("立即更新订阅", requestInitialFocus = true, onClick = onUseUrlSource)
            }
            item {
            Text(
                remoteAddress?.let { "远程配置：$it（10 分钟后关闭）" }
                    ?: "远程配置已关闭",
                color = TvAccent,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            )
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
            if (state.blockedSources.isNotEmpty()) {
                item {
                    Text(
                        "已屏蔽来源（${state.blockedSources.size}）",
                        color = TvAccent,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 6.dp),
                    )
                }
                items(
                    items = state.blockedSources,
                    key = { "${it.channelKey}:${it.sourceFingerprint}" },
                ) { source ->
                    val quality = if (source.videoWidth != null && source.videoHeight != null) {
                        " · ${source.videoWidth}×${source.videoHeight}"
                    } else ""
                    ActionRow("恢复 ${source.channelName} · 源${source.sourceNumber}$quality") {
                        onRestoreBlockedSource(source)
                    }
                }
                item {
                    ActionRow("恢复全部屏蔽来源", onClick = onClearBlockedSources)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, requestInitialFocus: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) runCatching { requester.requestFocus() }
    }
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(requester)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    it.nativeKeyEvent.keyCode in CONFIRM_KEY_CODES
                ) {
                    if (it.nativeKeyEvent.repeatCount == 0) onClick()
                    true
                } else false
            }
            .focusable()
            .graphicsLayer {
                scaleX = if (focused) 1.015f else 1f
                scaleY = if (focused) 1.015f else 1f
                shadowElevation = if (focused) 16f else 0f
                shape = RoundedCornerShape(5.dp)
            }
            .background(if (focused) TvFocusFill else TvRowFill, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        fontSize = 14.sp,
        color = if (focused) TvAccentText else Color.White,
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun SettingLine(label: String, checked: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    it.nativeKeyEvent.keyCode in CONFIRM_KEY_CODES
                ) {
                    if (it.nativeKeyEvent.repeatCount == 0) onClick()
                    true
                } else false
            }
            .focusable()
            .graphicsLayer {
                scaleX = if (focused) 1.015f else 1f
                scaleY = if (focused) 1.015f else 1f
                shadowElevation = if (focused) 16f else 0f
                shape = RoundedCornerShape(5.dp)
            }
            .background(if (focused) TvFocusFill else TvRowFill, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (focused) TvAccentText else Color.White,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TvSwitch(checked = checked, modifier = Modifier.height(30.dp).width(42.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceSelectorPanel(
    state: MainUiState,
    playback: PlaybackState,
    sourceTest: SourceTestState,
    onSelectSource: (Int) -> Unit,
    onStartSourceTest: () -> Unit,
    onStopSourceTest: () -> Unit,
    onBlockSource: (Channel, StreamSource) -> Unit,
    onClose: () -> Unit,
) {
    val channel = state.currentChannel
    val sources = channel?.sources.orEmpty()
    val currentSource = when (playback) {
        is PlaybackState.Preparing -> playback.sourceIndex
        is PlaybackState.Buffering -> playback.sourceIndex
        is PlaybackState.Playing -> playback.sourceIndex
        else -> channel?.preferredSource ?: 0
    }.coerceIn(0, (sources.size - 1).coerceAtLeast(0))
    val sourceIdentity = sources.map { it.url to it.headers }
    val initialCurrentSource = remember(channel?.id, sourceIdentity) { currentSource }
    val runningTest = sourceTest as? SourceTestState.Running
    val isTestRunning = runningTest?.channelId == channel?.id
    val testResults = when (sourceTest) {
        is SourceTestState.Running ->
            sourceTest.results.takeIf { sourceTest.channelId == channel?.id }.orEmpty()
        is SourceTestState.Completed ->
            sourceTest.results.takeIf { sourceTest.channelId == channel?.id }.orEmpty()
        SourceTestState.Idle -> emptyMap()
    }
    val completedResults = (sourceTest as? SourceTestState.Completed)
        ?.takeIf { it.channelId == channel?.id }
        ?.results
        .orEmpty()
    val frozenOrder = remember(channel?.id, sourceIdentity, completedResults) {
        val rankedSources = sources.mapIndexed { index, source ->
            source.withSourceTestResult(completedResults[index])
        }
        rankedSourceIndices(rankedSources, currentSource).take(MAX_VISIBLE_SOURCES)
    }
    val displayOrder = remember(frozenOrder, currentSource, isTestRunning, sourceIdentity) {
        if (isTestRunning) {
            sources.indices.toList()
        } else {
            (listOf(currentSource) + frozenOrder.filterNot { it == currentSource })
                .filter { it in sources.indices }
                .take(MAX_VISIBLE_SOURCES)
        }
    }
    val recommendedSource = frozenOrder.firstOrNull { it != currentSource }
    var focusedSource by remember(channel?.id, sourceIdentity) { mutableStateOf(currentSource) }
    var pendingBlockIndex by remember(channel?.id, sourceIdentity) { mutableStateOf<Int?>(null) }
    val columns = displayOrder.size.coerceIn(1, MAX_SOURCE_COLUMNS)

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val rows = ((displayOrder.size + columns - 1) / columns).coerceAtLeast(1)
        val layoutRows = if (isTestRunning) rows.coerceAtMost(4) else rows
        val gapsWidth = SOURCE_CARD_GAP * (columns - 1)
        val gapsHeight = SOURCE_CARD_GAP * (layoutRows - 1)
        val availableWidth = (maxWidth - 72.dp).coerceAtLeast(1.dp)
        val cardWidth = minOf(SOURCE_CARD_WIDTH, (availableWidth - gapsWidth) / columns)
            .coerceAtLeast(100.dp)
        val compactHeight = maxHeight < 500.dp
        val panelBottomPadding = if (compactHeight) 8.dp else 20.dp
        val reservedHeight = if (compactHeight) 126.dp else 140.dp
        val cardHeight = minOf(
            SOURCE_CARD_HEIGHT,
            ((maxHeight - reservedHeight - gapsHeight) / layoutRows).coerceAtLeast(62.dp),
        )
        val flowWidth = cardWidth * columns + gapsWidth
        val panelWidth = flowWidth + 32.dp + SOURCE_LAYOUT_TOLERANCE
        Surface(
            modifier = Modifier
                .width(panelWidth)
                .padding(bottom = panelBottomPadding),
            color = TvGlassPanel,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(
                        name = (channel?.name?.takeIf { it.isNotBlank() } ?: channel?.title).orEmpty(),
                        url = channel?.logo.orEmpty(),
                        modifier = Modifier.size(34.dp),
                        cornerRadius = 7,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        channel?.title.orEmpty(),
                        modifier = Modifier.weight(1f),
                        fontSize = 17.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (!isTestRunning && sources.size > MAX_VISIBLE_SOURCES) {
                            "当前源 ${currentSource + 1}/${sources.size} · 显示前 $MAX_VISIBLE_SOURCES 个"
                        } else {
                            "当前源 ${currentSource + 1}/${sources.size.coerceAtLeast(1)}"
                        },
                        color = TvAccent,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    "短按播放 · 长按确认标记错误源",
                    color = TvMutedText,
                    fontSize = 11.sp,
                )
                if (sources.isEmpty()) {
                    Text("当前频道没有可用播放源", color = TvMutedText, fontSize = 14.sp)
                } else {
                    SourceTestButton(
                        label = if (isTestRunning) {
                            "停止（${runningTest!!.currentIndex + 1}/${runningTest.total}）"
                        } else {
                            "测源"
                        },
                        onClick = if (isTestRunning) onStopSourceTest else onStartSourceTest,
                    )
                    FlowRow(
                        modifier = Modifier
                            .width(flowWidth)
                            .then(
                                if (isTestRunning && rows > layoutRows) {
                                    Modifier
                                        .height(
                                            cardHeight * layoutRows +
                                                SOURCE_CARD_GAP * (layoutRows - 1)
                                        )
                                        .verticalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                },
                            ),
                        maxItemsInEachRow = columns,
                        horizontalArrangement = Arrangement.spacedBy(SOURCE_CARD_GAP),
                        verticalArrangement = Arrangement.spacedBy(SOURCE_CARD_GAP),
                    ) {
                        displayOrder.forEach { index ->
                            val source = sources[index]
                            SourceChip(
                                source = source,
                                sourceNumber = index + 1,
                                width = cardWidth,
                                height = cardHeight,
                                selected = index == currentSource,
                                recommended = index == recommendedSource,
                                focusedAsSource = index == focusedSource,
                                requestInitialFocus = index == initialCurrentSource,
                                testResult = testResults[index],
                                onFocused = {
                                    focusedSource = index
                                },
                                onConfirm = {
                                    if (isTestRunning) onStopSourceTest()
                                    if (isTestRunning || index != currentSource) onSelectSource(index)
                                    onClose()
                                },
                                onLongConfirm = {
                                    if (!isTestRunning) pendingBlockIndex = index
                                },
                            )
                        }
                    }
                }
            }
        }
        pendingBlockIndex?.let { index ->
            val source = sources.getOrNull(index)
            if (channel != null && source != null) {
                BlockSourceConfirmation(
                    channel = channel,
                    source = source,
                    isLastSource = sources.size == 1,
                    onCancel = { pendingBlockIndex = null },
                    onConfirm = {
                        pendingBlockIndex = null
                        onBlockSource(channel, source)
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceTestButton(
    label: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .widthIn(min = 112.dp)
            .height(38.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                val native = it.nativeKeyEvent
                if (native.keyCode !in CONFIRM_KEY_CODES) return@onPreviewKeyEvent false
                if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) onClick()
                true
            }
            .focusable()
            .background(
                if (focused) TvAccent else TvChipFill,
                RoundedCornerShape(7.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) TvAccentText else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun BlockSourceConfirmation(
    channel: Channel,
    source: StreamSource,
    isLastSource: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Surface(
        modifier = Modifier.widthIn(min = 300.dp, max = 420.dp),
        color = TvGlassStrong,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("标记错误来源？", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${channel.title} · 源${source.order + 1} 将不再参与播放和自动切源。",
                color = TvSoftText,
                fontSize = 13.sp,
            )
            if (isLastSource) {
                Text("这是最后一个可用来源，屏蔽后该频道将暂时无法播放。", color = TvError, fontSize = 12.sp)
            }
            ActionRow("取消", requestInitialFocus = true, onClick = onCancel)
            ActionRow("确认屏蔽", onClick = onConfirm)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceChip(
    source: StreamSource,
    sourceNumber: Int,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    recommended: Boolean,
    focusedAsSource: Boolean,
    requestInitialFocus: Boolean,
    testResult: SourceTestResult?,
    onFocused: () -> Unit,
    onConfirm: () -> Unit,
    onLongConfirm: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var confirmPressed by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) runCatching { requester.requestFocus() }
    }
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
                if (!it.isFocused) {
                    confirmPressed = false
                    longPressHandled = false
                }
            }
            .focusRequester(requester)
            .onPreviewKeyEvent {
                val native = it.nativeKeyEvent
                if (native.keyCode !in CONFIRM_KEY_CODES) return@onPreviewKeyEvent false
                when (native.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (native.repeatCount == 0) {
                            confirmPressed = true
                            longPressHandled = false
                        } else if (confirmPressed && !longPressHandled) {
                            longPressHandled = true
                            onLongConfirm()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (confirmPressed && !longPressHandled) onConfirm()
                        confirmPressed = false
                        longPressHandled = false
                    }
                }
                true
            }
            .focusable()
            .graphicsLayer {
                scaleX = if (focused) 1.03f else 1f
                scaleY = if (focused) 1.03f else 1f
                shadowElevation = if (focused) 14f else 0f
                shape = RoundedCornerShape(7.dp)
            }
            .background(
                when {
                    focused || focusedAsSource -> TvAccent
                    selected -> TvPlayingFill
                    else -> TvChipFill
                },
                RoundedCornerShape(7.dp),
            )
            .combinedClickable(onClick = onConfirm, onLongClick = onLongConfirm),
        contentAlignment = Alignment.CenterStart,
    ) {
        val active = focused || focusedAsSource
        val quality = SourceQualityPolicy.evaluate(source)
        val formatAge = SourceQualityPolicy.qualityAge(source.formatCheckedAt)
        val compact = height < 72.dp
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = if (compact) 3.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "源$sourceNumber",
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = if (active) TvAccentText else Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (selected) {
                    Text(
                        "当前",
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = if (active) TvAccentText else TvAccent,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else if (recommended) {
                    Text(
                        "推荐",
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        color = if (active) TvAccentText else TvAccent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                sourceTestVideoFormatText(testResult)
                    ?: sourceVideoFormatText(source, formatAge),
                color = if (active) TvAccentText.copy(alpha = 0.82f) else TvSoftText,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sourceTestHealthText(testResult)
                    ?: sourceHealthText(source, quality.age),
                color = if (active) {
                    TvAccentText
                } else {
                    testResult?.let(::sourceTestColor)
                        ?: sourceHealthColor(quality.healthStatus)
                },
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "错误 ${quality.errorCount} · 波动 ${quality.fluctuationCount}",
                color = if (active) TvAccentText.copy(alpha = 0.72f) else TvMutedText,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                maxLines = 1,
            )
        }
    }
}

private fun sourceTestVideoFormatText(result: SourceTestResult?): String? {
    result ?: return null
    val width = result.videoWidth ?: return if (result.status == SourceTestStatus.TESTING) {
        "正在识别画质"
    } else {
        null
    }
    val height = result.videoHeight ?: return null
    return buildList {
        add("${width}×$height")
        result.videoFrameRate?.takeIf { it > 0f }?.let { frameRate ->
            add(
                if (frameRate % 1f < 0.05f) {
                    "${frameRate.toInt()}fps"
                } else {
                    String.format(Locale.US, "%.1ffps", frameRate)
                },
            )
        }
        result.videoCodec.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(" · ")
}

private fun sourceTestHealthText(result: SourceTestResult?): String? {
    result ?: return null
    val detail = buildList {
        result.startupMs?.let { add(formatDuration(it)) }
        result.bitrateBps?.takeIf { it > 0L }?.let {
            add(String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0))
        }
    }.joinToString(" · ")
    val status = when (result.status) {
        SourceTestStatus.PENDING -> "待测"
        SourceTestStatus.TESTING -> "测试中"
        SourceTestStatus.AVAILABLE -> "可用"
        SourceTestStatus.FLUCTUATING -> "可用 · 有波动"
        SourceTestStatus.TIMEOUT -> "加载超时"
        SourceTestStatus.FAILED -> "播放失败"
    }
    return if (detail.isBlank()) status else "$status · $detail"
}

private fun sourceTestColor(result: SourceTestResult): Color = when (result.status) {
    SourceTestStatus.AVAILABLE -> TvAccent
    SourceTestStatus.TESTING -> TvAccent
    SourceTestStatus.FLUCTUATING -> Color(0xFFFFC857)
    SourceTestStatus.TIMEOUT, SourceTestStatus.FAILED -> TvError
    SourceTestStatus.PENDING -> TvMutedText
}

private fun StreamSource.withSourceTestResult(result: SourceTestResult?): StreamSource {
    result ?: return this
    val testedHealth = when (result.status) {
        SourceTestStatus.AVAILABLE, SourceTestStatus.FLUCTUATING -> SourceHealthStatus.SUCCESS
        SourceTestStatus.TIMEOUT -> SourceHealthStatus.TIMEOUT
        SourceTestStatus.FAILED -> SourceHealthStatus.ERROR
        SourceTestStatus.PENDING, SourceTestStatus.TESTING -> healthStatus
    }
    return copy(
        healthStatus = testedHealth,
        startupMs = result.startupMs ?: startupMs,
        bitrateBps = result.bitrateBps ?: bitrateBps,
        lastCheckedAt = if (result.status == SourceTestStatus.PENDING) lastCheckedAt
        else System.currentTimeMillis(),
        fluctuationCount = if (result.status == SourceTestStatus.FLUCTUATING) {
            fluctuationCount + 1
        } else {
            fluctuationCount
        },
        videoWidth = result.videoWidth ?: videoWidth,
        videoHeight = result.videoHeight ?: videoHeight,
        videoFrameRate = result.videoFrameRate ?: videoFrameRate,
        videoCodec = result.videoCodec.ifBlank { videoCodec },
        formatCheckedAt = if (result.videoWidth != null) System.currentTimeMillis()
        else formatCheckedAt,
    )
}

private fun rankedSourceIndices(sources: List<StreamSource>, currentSource: Int): List<Int> =
    SourceSelectionPolicy.rankedIndices(sources, preferredIndex = currentSource)

private fun sourceHealthText(source: StreamSource, age: SourceQualityAge): String {
    if (age == SourceQualityAge.EXPIRED) {
        return if (source.lastCheckedAt == null) "未检测" else "待复检 · 记录已过期"
    }
    if (age == SourceQualityAge.HISTORICAL && source.healthStatus != SourceHealthStatus.SUCCESS) {
        return "待复检"
    }
    return when (source.healthStatus) {
        SourceHealthStatus.UNKNOWN -> "未检测"
        SourceHealthStatus.TIMEOUT -> "加载超时"
        SourceHealthStatus.ERROR -> "播放失败"
        SourceHealthStatus.SUCCESS -> buildString {
            append(if (age == SourceQualityAge.HISTORICAL) "历史可用" else "可用")
            source.startupMs?.let { append(" ${formatDuration(it)}") }
            source.bitrateBps?.takeIf { it > 0L }?.let {
                append(" · ${String.format(Locale.US, "%.1f", it / 1_000_000.0)} Mbps")
            }
        }
    }
}

private fun sourceVideoFormatText(source: StreamSource, age: SourceQualityAge): String {
    val width = source.videoWidth ?: return "画质未识别"
    val height = source.videoHeight ?: return "画质未识别"
    val detail = buildList {
        add("${width}×$height")
        source.videoFrameRate?.takeIf { it > 0f }?.let { frameRate ->
            val value = if (frameRate % 1f < 0.05f) {
                frameRate.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", frameRate)
            }
            add("${value}fps")
        }
        source.videoCodec.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(" · ")
    return if (age == SourceQualityAge.EXPIRED) "历史画质 · $detail" else detail
}

private fun sourceHealthColor(status: SourceHealthStatus): Color = when (status) {
    SourceHealthStatus.SUCCESS -> TvAccent
    SourceHealthStatus.TIMEOUT, SourceHealthStatus.ERROR -> TvError
    SourceHealthStatus.UNKNOWN -> TvMutedText
}

private fun formatDuration(millis: Long): String =
    if (millis < 1_000L) "${millis}ms" else String.format(Locale.US, "%.1f秒", millis / 1_000.0)

@Composable
private fun ProgramRow(program: com.youtv.app.domain.model.Program, selected: Boolean, nowMillis: Long) {
    var focused by remember { mutableStateOf(false) }
    val nowSeconds = nowMillis / 1000
    val current = nowSeconds in program.beginTime.toLong() until program.endTime.toLong()
    val past = nowSeconds >= program.endTime.toLong()
    val progress = ((nowSeconds - program.beginTime).toFloat() /
        (program.endTime - program.beginTime).coerceAtLeast(1)).coerceIn(0f, 1f)
    val active = focused || selected
    val rowBackground = when {
        active -> TvFocusFill
        current -> TvCurrentProgramFill
        else -> TvRowFill
    }
    val timeColor = when {
        active -> TvAccentText
        current -> TvAccent
        past -> TvFadedText
        else -> TvMutedText
    }
    val titleColor = when {
        active -> TvAccentText
        current -> Color.White
        past -> TvFadedText
        else -> TvSoftText
    }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .graphicsLayer {
                scaleX = if (active) 1.01f else 1f
                scaleY = if (active) 1.01f else 1f
                shadowElevation = if (active) 12f else 0f
                shape = RoundedCornerShape(4.dp)
            }
            .background(rowBackground, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(formatProgramTime(program.beginTime), color = timeColor, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                program.title,
                color = titleColor,
                fontSize = 13.sp,
                fontWeight = if (active || current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current && !active) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(4.dp),
                    color = TvAccent,
                    trackColor = TvProgressTrack,
                )
            }
        }
    }
}

@Composable
private fun ChannelLogo(name: String, url: String, modifier: Modifier = Modifier, cornerRadius: Int) {
    val localLogo = remember(name) { ChannelLogoCatalog.drawableFor(name) }
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(TvLogoBackdrop, RoundedCornerShape(cornerRadius.dp))
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            localLogo != null -> Image(
                painter = painterResource(localLogo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            url.isNotBlank() && !failed -> AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
            else -> Text(
                text = name.trim().take(2).ifBlank { "TV" }.uppercase(Locale.ROOT),
                color = TvSoftText,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(24.dp)
                .background(if (checked) TvAccent else TvSwitchOffTrack, RoundedCornerShape(12.dp)),
        ) {
            Box(
                modifier = Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 3.dp)
                    .size(18.dp)
                    .background(Color.White, RoundedCornerShape(9.dp)),
            )
        }
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
        color = TvMutedText,
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
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.86f)
                .padding(24.dp),
            color = TvGlassPanel,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(28.dp)) {
                Text(title, fontSize = 30.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(body, fontSize = 19.sp, color = TvSoftText)
            }
        }
    }
}

private data class VolumeUi(val current: Int, val maximum: Int, val eventId: Long)

private val TvAccent = Color(0xFF00FFCC)
private val TvError = Color(0xFFFF8066)
private val TvAccentText = Color(0xFF07120F)
private val TvGlassPanel = Color(0xD92E3337)
private val TvGlassStrong = Color(0xE6262B2F)
private val TvFocusFill = Color(0xFF00FFCC)
private val TvPlayingFill = Color(0x3300FFCC)
private val TvRowFill = Color(0x08FFFFFF)
private val TvChipFill = Color(0x1AFFFFFF)
private val TvCurrentProgramFill = Color(0x1200FFCC)
private val TvSoftText = Color(0xE6FFFFFF)
private val TvMutedText = Color(0xCCFFFFFF)
private val TvFadedText = Color(0x73FFFFFF)
private val TvProgressTrack = Color(0x22FFFFFF)
private val TvSwitchOffTrack = Color(0x26FFFFFF)
private val TvLogoBackdrop = Color(0x1FFFFFFF)

private const val CHANNEL_NUMBER_DELAY_MILLIS = 1_500L
private const val VOLUME_DISPLAY_MILLIS = 2_000L
private const val MESSAGE_DISPLAY_MILLIS = 3_000L
private const val SOURCE_TEST_OVERLAY_KEEP_ALIVE_MILLIS = 5_000L
private val CONFIRM_KEY_CODES = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
)
private const val MAX_VISIBLE_SOURCES = 20
private const val MAX_SOURCE_COLUMNS = 5
private val SOURCE_CARD_WIDTH = 180.dp
private val SOURCE_CARD_HEIGHT = 88.dp
private val SOURCE_CARD_GAP = 8.dp
private val SOURCE_LAYOUT_TOLERANCE = 2.dp
