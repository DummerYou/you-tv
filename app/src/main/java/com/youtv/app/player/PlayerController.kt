package com.youtv.app.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.youtv.app.domain.model.Channel
import com.youtv.app.domain.model.SourceAddressType
import com.youtv.app.domain.model.SourceHealthStatus
import com.youtv.app.domain.model.SourcePlaybackEventType
import com.youtv.app.domain.model.SourcePlaybackResult
import com.youtv.app.domain.model.SourceQualityPolicy
import com.youtv.app.domain.model.StreamSource
import com.youtv.app.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val onSourceResult: (SourcePlaybackResult) -> Unit,
) : Player.Listener, AnalyticsListener {
    private val renderersFactory = DefaultRenderersFactory(context).setExtensionRendererMode(
        if (softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
    )
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MILLIS,
            MAX_BUFFER_MILLIS,
            BUFFER_FOR_PLAYBACK_MILLIS,
            BUFFER_AFTER_REBUFFER_MILLIS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
    private val probeCoordinator = SourceProbeCoordinator()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(renderersFactory)
        .setLoadControl(loadControl)
        .setBandwidthMeter(bandwidthMeter)
        .build()
        .also {
            it.playWhenReady = true
            it.addListener(this)
            it.addAnalyticsListener(this)
        }

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var currentChannel: Channel? = null
    private var sourceIndex = 0
    private var sourceTypeIndex = 0
    private var attemptOrder: List<Int> = emptyList()
    private var attemptPosition = 0
    private var sourceAttemptStartedAt = 0L
    private var renderedFirstFrame = false
    private var rebufferEpisodeActive = false
    private var hasBandwidthSample = false
    private var latestBitrateEstimate = 0L
    private var actualVideoWidth: Int? = null
    private var actualVideoHeight: Int? = null
    private var lastPersistedFormat: VideoFormatSnapshot? = null
    private var released = false
    private var attemptToken = 0L
    private val controllerJob = SupervisorJob()
    private val scope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)
    private var firstFrameTimeoutJob: Job? = null
    private var rebufferTimeoutJob: Job? = null

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
        val preferred = preferredSource.coerceIn(channel.sources.indices)
        attemptOrder = buildAttemptOrder(channel, preferred)
        attemptPosition = 0
        startCurrentSourceAttempt()
    }

    fun selectSource(index: Int) {
        val channel = currentChannel ?: return
        if (channel.sources.isEmpty()) return
        val selected = index.coerceIn(channel.sources.indices)
        attemptOrder = buildAttemptOrder(channel, selected)
        attemptPosition = 0
        startCurrentSourceAttempt()
    }

    fun retry() {
        val channel = currentChannel ?: return
        if (channel.sources.isEmpty()) return
        attemptOrder = buildAttemptOrder(channel, sourceIndex.coerceIn(channel.sources.indices))
        attemptPosition = 0
        startCurrentSourceAttempt()
    }

    fun pause() {
        cancelTimeouts()
        player.pause()
    }

    fun resume() {
        player.play()
        val channel = currentChannel ?: return
        if (!renderedFirstFrame) {
            scheduleFirstFrameTimeout(channel, sourceIndex, attemptToken, FIRST_FRAME_TIMEOUT_MILLIS)
        } else if (_state.value is PlaybackState.Buffering) {
            scheduleRebufferTimeout(channel, sourceIndex, attemptToken)
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying || player.playWhenReady) pause() else resume()
    }

    fun stop() {
        cancelTimeouts()
        player.stop()
        _state.value = PlaybackState.Idle
    }

    fun release() {
        if (released) return
        released = true
        cancelTimeouts()
        controllerJob.cancel()
        probeCoordinator.release()
        player.removeAnalyticsListener(this)
        player.removeListener(this)
        player.release()
    }

    private fun buildAttemptOrder(channel: Channel, preferred: Int): List<Int> {
        val quality = channel.sources.map { SourceQualityPolicy.evaluate(it) }
        return channel.sources.indices.sortedWith(
            compareBy<Int> {
                val status = quality[it].healthStatus
                if (probeCoordinator.isHostCoolingDown(channel.sources[it])) {
                    10 + sourceHealthRank(status)
                } else if (it == preferred && status != SourceHealthStatus.TIMEOUT && status != SourceHealthStatus.ERROR) {
                    -1
                } else {
                    sourceHealthRank(status)
                }
            }
                .thenBy {
                    channel.sources[it].startupMs
                        ?.takeIf { _ -> quality[it].healthStatus == SourceHealthStatus.SUCCESS }
                        ?: Long.MAX_VALUE
                }
                .thenByDescending {
                    channel.sources[it].bitrateBps
                        ?.takeIf { _ -> quality[it].healthStatus == SourceHealthStatus.SUCCESS }
                        ?: Long.MIN_VALUE
                }
                .thenBy { quality[it].errorCount }
                .thenBy { quality[it].fluctuationCount }
                .thenBy { it },
        )
    }

    private fun sourceHealthRank(status: SourceHealthStatus): Int = when (status) {
        SourceHealthStatus.SUCCESS -> 0
        SourceHealthStatus.UNKNOWN -> 1
        SourceHealthStatus.TIMEOUT, SourceHealthStatus.ERROR -> 2
    }

    private fun startCurrentSourceAttempt() {
        val channel = currentChannel ?: return
        if (attemptPosition !in attemptOrder.indices) {
            failAllSources(channel, "所有播放源均不可用")
            return
        }
        cancelTimeouts()
        sourceIndex = attemptOrder[attemptPosition]
        sourceTypeIndex = 0
        sourceAttemptStartedAt = SystemClock.elapsedRealtime()
        renderedFirstFrame = false
        rebufferEpisodeActive = false
        hasBandwidthSample = false
        latestBitrateEstimate = 0L
        actualVideoWidth = null
        actualVideoHeight = null
        lastPersistedFormat = null
        val token = ++attemptToken
        _state.value = PlaybackState.Preparing(channel, sourceIndex)
        reportSourceResult(
            channel = channel,
            source = channel.sources[sourceIndex],
            event = SourcePlaybackEventType.ATTEMPT_STARTED,
        )
        scheduleFirstFrameTimeout(channel, sourceIndex, token, FIRST_FRAME_TIMEOUT_MILLIS)
        scheduleCandidateProbes(channel)
        prepareCurrentMode()
    }

    private fun prepareCurrentMode() {
        val channel = currentChannel ?: return
        val source = channel.sources[sourceIndex]
        val modes = sourceModes(source)
        if (sourceTypeIndex !in modes.indices) {
            finishSourceFailure(SourcePlaybackEventType.ERROR, "播放格式不支持")
            return
        }
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(createMediaSource(source, modes[sourceTypeIndex]))
        player.prepare()
    }

    private fun createMediaSource(source: StreamSource, mode: SourceMode): MediaSource {
        val item = MediaItem.Builder()
            .setUri(source.url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MILLIS)
                    .setMinPlaybackSpeed(MIN_LIVE_PLAYBACK_SPEED)
                    .setMaxPlaybackSpeed(MAX_LIVE_PLAYBACK_SPEED)
                    .build(),
            )
            .build()
        val dataSource = OkHttpDataSource.Factory(HttpClient.getStreamingClientWithProxy()).apply {
            setDefaultRequestProperties(source.headers)
        }
        return when (mode) {
            SourceMode.HLS -> HlsMediaSource.Factory(dataSource)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
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

    private fun finishSourceFailure(event: SourcePlaybackEventType, message: String) {
        val channel = currentChannel ?: return
        val source = channel.sources[sourceIndex]
        reportSourceResult(channel, source, event)
        promoteBestProbedCandidate(channel)
        cancelTimeouts()
        attemptPosition++
        if (attemptPosition >= attemptOrder.size) {
            failAllSources(channel, message)
        } else {
            startCurrentSourceAttempt()
        }
    }

    private fun promoteBestProbedCandidate(channel: Channel) {
        val remaining = attemptOrder.drop(attemptPosition + 1)
        if (remaining.isEmpty()) return
        val best = probeCoordinator.bestSuccessfulCandidate(channel, remaining)
        val healthyHostsFirst = remaining
            .filterNot { it == best }
            .sortedBy { if (probeCoordinator.isHostCoolingDown(channel.sources[it])) 1 else 0 }
        attemptOrder = attemptOrder.take(attemptPosition + 1) +
            listOfNotNull(best) + healthyHostsFirst
    }

    private fun scheduleCandidateProbes(channel: Channel) {
        probeCoordinator.schedule(
            channel = channel,
            currentSourceIndex = sourceIndex,
            orderedCandidates = attemptOrder.drop(attemptPosition + 1),
            delayMillis = PROBE_TRIGGER_MILLIS,
        )
    }

    private fun failAllSources(channel: Channel, message: String) {
        cancelTimeouts()
        val source = channel.sources.getOrNull(sourceIndex)
        _state.value = PlaybackState.Failed(
            channel = channel,
            attemptedSources = attemptOrder.size,
            addressType = source?.addressType ?: SourceAddressType.UNKNOWN,
            message = message,
        )
    }

    private fun reportSourceResult(
        channel: Channel,
        source: StreamSource,
        event: SourcePlaybackEventType,
        startupMs: Long? = null,
        bitrateBps: Long? = null,
        videoFormat: VideoFormatSnapshot? = null,
    ) {
        onSourceResult(
            SourcePlaybackResult(
                channelId = channel.id,
                sourceUrl = source.url,
                sourceIndex = sourceIndex,
                event = event,
                startupMs = startupMs,
                bitrateBps = bitrateBps,
                videoWidth = videoFormat?.width,
                videoHeight = videoFormat?.height,
                videoFrameRate = videoFormat?.frameRate,
                videoCodec = videoFormat?.codec.orEmpty(),
                videoTrackBitrate = videoFormat?.trackBitrate,
            ),
        )
    }

    private fun scheduleFirstFrameTimeout(
        channel: Channel,
        scheduledSourceIndex: Int,
        token: Long,
        timeoutMillis: Long,
    ) {
        firstFrameTimeoutJob?.cancel()
        firstFrameTimeoutJob = scope.launch {
            delay(timeoutMillis)
            if (!released && token == attemptToken && !renderedFirstFrame &&
                currentChannel?.id == channel.id && sourceIndex == scheduledSourceIndex
            ) {
                finishSourceFailure(SourcePlaybackEventType.TIMEOUT, "播放源加载超时")
            }
        }
    }

    private fun scheduleRebufferTimeout(channel: Channel, scheduledSourceIndex: Int, token: Long) {
        rebufferTimeoutJob?.cancel()
        rebufferTimeoutJob = scope.launch {
            delay(REBUFFER_TIMEOUT_MILLIS)
            if (!released && token == attemptToken && rebufferEpisodeActive &&
                currentChannel?.id == channel.id && sourceIndex == scheduledSourceIndex
            ) {
                finishSourceFailure(SourcePlaybackEventType.TIMEOUT, "播放持续缓冲，已尝试下一源")
            }
        }
    }

    private fun cancelTimeouts() {
        firstFrameTimeoutJob?.cancel()
        firstFrameTimeoutJob = null
        rebufferTimeoutJob?.cancel()
        rebufferTimeoutJob = null
        probeCoordinator.cancelActive()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val channel = currentChannel ?: return
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                _state.value = PlaybackState.Buffering(channel, sourceIndex)
                if (renderedFirstFrame && player.playWhenReady && !rebufferEpisodeActive) {
                    rebufferEpisodeActive = true
                    reportSourceResult(
                        channel,
                        channel.sources[sourceIndex],
                        SourcePlaybackEventType.FLUCTUATION,
                    )
                    scheduleRebufferTimeout(channel, sourceIndex, attemptToken)
                    probeCoordinator.schedule(
                        channel = channel,
                        currentSourceIndex = sourceIndex,
                        orderedCandidates = attemptOrder.drop(attemptPosition + 1),
                        delayMillis = PROBE_TRIGGER_MILLIS,
                    )
                }
            }
            Player.STATE_READY -> if (renderedFirstFrame && player.playWhenReady) {
                rebufferEpisodeActive = false
                rebufferTimeoutJob?.cancel()
                rebufferTimeoutJob = null
                probeCoordinator.cancelActive()
                _state.value = PlaybackState.Playing(channel, sourceIndex)
            }
        }
    }

    override fun onRenderedFirstFrame() {
        if (renderedFirstFrame) return
        val channel = currentChannel ?: return
        val source = channel.sources.getOrNull(sourceIndex) ?: return
        renderedFirstFrame = true
        rebufferEpisodeActive = false
        cancelTimeouts()
        val startupMs = (SystemClock.elapsedRealtime() - sourceAttemptStartedAt).coerceAtLeast(0L)
        val bitrate = latestBitrateEstimate.takeIf {
            hasBandwidthSample && it > 0L && it != C.RATE_UNSET.toLong()
        }
        val videoFormat = currentVideoFormat()
        lastPersistedFormat = videoFormat
        _state.value = PlaybackState.Playing(channel, sourceIndex)
        reportSourceResult(
            channel = channel,
            source = source,
            event = SourcePlaybackEventType.SUCCESS,
            startupMs = startupMs,
            bitrateBps = bitrate,
            videoFormat = videoFormat,
        )
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        actualVideoWidth = videoSize.width.takeIf { it > 0 }
        actualVideoHeight = videoSize.height.takeIf { it > 0 }
        persistVideoFormatIfChanged()
    }

    override fun onTracksChanged(tracks: Tracks) {
        persistVideoFormatIfChanged()
    }

    private fun persistVideoFormatIfChanged() {
        if (!renderedFirstFrame) return
        val channel = currentChannel ?: return
        val source = channel.sources.getOrNull(sourceIndex) ?: return
        val format = currentVideoFormat() ?: return
        if (format == lastPersistedFormat) return
        lastPersistedFormat = format
        reportSourceResult(
            channel = channel,
            source = source,
            event = SourcePlaybackEventType.FORMAT_CHANGED,
            videoFormat = format,
        )
    }

    private fun currentVideoFormat(): VideoFormatSnapshot? {
        val format = player.videoFormat ?: return null
        val width = actualVideoWidth ?: format.width.takeIf { it > 0 }
        val height = actualVideoHeight ?: format.height.takeIf { it > 0 }
        if (width == null || height == null) return null
        return VideoFormatSnapshot(
            width = width,
            height = height,
            frameRate = format.frameRate.takeIf { it > 0f },
            codec = codecLabel(format.sampleMimeType, format.codecs),
            trackBitrate = format.averageBitrate.takeIf { it > 0 }
                ?.toLong()
                ?: format.peakBitrate.takeIf { it > 0 }?.toLong(),
        )
    }

    private fun codecLabel(sampleMimeType: String?, codecs: String?): String = when (sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "H.265"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_VP9 -> "VP9"
        else -> codecs.orEmpty().substringBefore(',').ifBlank { sampleMimeType.orEmpty() }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (renderedFirstFrame) {
            finishSourceFailure(SourcePlaybackEventType.ERROR, error.errorCodeName)
            return
        }
        val channel = currentChannel ?: return
        val modes = sourceModes(channel.sources[sourceIndex])
        val elapsed = SystemClock.elapsedRealtime() - sourceAttemptStartedAt
        if (sourceTypeIndex < modes.lastIndex && elapsed < FIRST_FRAME_TIMEOUT_MILLIS) {
            sourceTypeIndex++
            prepareCurrentMode()
        } else {
            finishSourceFailure(SourcePlaybackEventType.ERROR, error.errorCodeName)
        }
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        if (totalBytesLoaded > 0L && bitrateEstimate > 0L) {
            hasBandwidthSample = true
            latestBitrateEstimate = bitrateEstimate
        }
    }

    private enum class SourceMode { HLS, DASH, RTSP, RTMP, PROGRESSIVE }

    private data class VideoFormatSnapshot(
        val width: Int,
        val height: Int,
        val frameRate: Float?,
        val codec: String,
        val trackBitrate: Long?,
    )

    private companion object {
        const val FIRST_FRAME_TIMEOUT_MILLIS = 5_000L
        const val REBUFFER_TIMEOUT_MILLIS = 8_000L
        const val PROBE_TRIGGER_MILLIS = 2_000L
        const val MIN_BUFFER_MILLIS = 8_000
        const val MAX_BUFFER_MILLIS = 30_000
        const val BUFFER_FOR_PLAYBACK_MILLIS = 1_000
        const val BUFFER_AFTER_REBUFFER_MILLIS = 2_500
        const val LIVE_TARGET_OFFSET_MILLIS = 3_000L
        const val MIN_LIVE_PLAYBACK_SPEED = 0.97f
        const val MAX_LIVE_PLAYBACK_SPEED = 1.05f
    }
}
