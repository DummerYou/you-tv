package com.youtv.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.SourceAddressType
import com.youtv.app.domain.model.StreamSource
import com.youtv.app.requests.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Preparing(val channel: Channel, val sourceIndex: Int) : PlaybackState
    data class Playing(val channel: Channel, val sourceIndex: Int) : PlaybackState
    data class Buffering(val channel: Channel, val sourceIndex: Int) : PlaybackState
    data class Failed(
        val channel: Channel,
        val attemptedSources: Int,
        val addressType: SourceAddressType,
        val message: String,
    ) : PlaybackState
}

@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    softDecode: Boolean,
    private val onSourceSucceeded: (channelId: String, sourceIndex: Int) -> Unit,
) : Player.Listener {
    private val renderersFactory = DefaultRenderersFactory(context).setExtensionRendererMode(
        if (softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
    )

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(renderersFactory)
        .build()
        .also {
            it.playWhenReady = true
            it.addListener(this)
        }

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var currentChannel: Channel? = null
    private var sourceIndex = 0
    private var attemptedSources = 0
    private var sourceTypeIndex = 0
    private var retryRound = 0
    private var released = false

    fun play(channel: Channel, preferredSource: Int = 0) {
        if (released || channel.sources.isEmpty()) {
            if (channel.sources.isEmpty()) {
                _state.value = PlaybackState.Failed(
                    channel, 0, SourceAddressType.UNKNOWN, "频道没有可用播放地址",
                )
            }
            return
        }
        currentChannel = channel
        sourceIndex = preferredSource.coerceIn(channel.sources.indices)
        attemptedSources = 0
        sourceTypeIndex = 0
        retryRound = 0
        prepareCurrentSource()
    }

    fun selectSource(index: Int) {
        val channel = currentChannel ?: return
        sourceIndex = index.coerceIn(channel.sources.indices)
        attemptedSources = 0
        sourceTypeIndex = 0
        retryRound = 0
        prepareCurrentSource()
    }

    fun retry() {
        attemptedSources = 0
        sourceTypeIndex = 0
        retryRound = 0
        prepareCurrentSource()
    }

    fun pause() = player.pause()
    fun resume() = player.play()

    fun stop() {
        player.stop()
        _state.value = PlaybackState.Idle
    }

    fun release() {
        if (released) return
        released = true
        player.removeListener(this)
        player.release()
    }

    private fun prepareCurrentSource() {
        val channel = currentChannel ?: return
        if (attemptedSources >= channel.sources.size) {
            val last = channel.sources[sourceIndex]
            _state.value = PlaybackState.Failed(
                channel = channel,
                attemptedSources = attemptedSources,
                addressType = last.addressType,
                message = "所有播放源均不可用",
            )
            return
        }
        val source = channel.sources[sourceIndex]
        _state.value = PlaybackState.Preparing(channel, sourceIndex)
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(createMediaSource(source, sourceModes(source)[sourceTypeIndex]))
        player.prepare()
    }

    private fun createMediaSource(source: StreamSource, mode: SourceMode): MediaSource {
        val item = MediaItem.fromUri(source.url)
        val uri = Uri.parse(source.url)
        val dataSource = OkHttpDataSource.Factory(HttpClient.getClientWithProxy()).apply {
            setDefaultRequestProperties(source.headers)
        }
        return when (mode) {
            SourceMode.HLS -> HlsMediaSource.Factory(dataSource).createMediaSource(item)
            SourceMode.DASH -> DashMediaSource.Factory(dataSource).createMediaSource(item)
            SourceMode.RTSP -> RtspMediaSource.Factory().apply {
                source.headers.entries.firstOrNull { it.key.equals("user-agent", true) }
                    ?.value?.let(::setUserAgent)
            }.createMediaSource(item)
            SourceMode.RTMP -> ProgressiveMediaSource.Factory(RtmpDataSource.Factory()).createMediaSource(item)
            SourceMode.PROGRESSIVE -> ProgressiveMediaSource.Factory(dataSource).createMediaSource(item)
        }
    }

    private fun sourceModes(source: StreamSource): List<SourceMode> {
        val uri = Uri.parse(source.url)
        val path = uri.path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") -> listOf(SourceMode.HLS)
            path.endsWith(".mpd") -> listOf(SourceMode.DASH)
            uri.scheme.equals("rtsp", true) -> listOf(SourceMode.RTSP)
            uri.scheme.equals("rtmp", true) -> listOf(SourceMode.RTMP)
            else -> listOf(SourceMode.HLS, SourceMode.PROGRESSIVE)
        }
    }

    private fun tryNextSource(error: PlaybackException) {
        val channel = currentChannel ?: return
        val modes = sourceModes(channel.sources[sourceIndex])
        if (sourceTypeIndex < modes.lastIndex) {
            sourceTypeIndex++
            prepareCurrentSource()
            return
        }
        sourceTypeIndex = 0
        retryRound++
        if (retryRound < MAX_RETRY_ROUNDS) {
            prepareCurrentSource()
            return
        }
        retryRound = 0
        attemptedSources++
        if (attemptedSources >= channel.sources.size) {
            val source = channel.sources[sourceIndex]
            _state.value = PlaybackState.Failed(
                channel,
                attemptedSources,
                source.addressType,
                error.errorCodeName,
            )
            return
        }
        sourceIndex = (sourceIndex + 1) % channel.sources.size
        prepareCurrentSource()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val channel = currentChannel ?: return
        when (playbackState) {
            Player.STATE_BUFFERING -> _state.value = PlaybackState.Buffering(channel, sourceIndex)
            Player.STATE_READY -> if (player.playWhenReady) {
                _state.value = PlaybackState.Playing(channel, sourceIndex)
                onSourceSucceeded(channel.id, sourceIndex)
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) = tryNextSource(error)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            retryRound = 0
            attemptedSources = 0
        }
    }

    private enum class SourceMode { HLS, DASH, RTSP, RTMP, PROGRESSIVE }

    private companion object {
        const val MAX_RETRY_ROUNDS = 10
    }
}
