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
import androidx.media3.common.Timeline
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
import com.youtv.app.domain.model.SourcePlaybackEventType
import com.youtv.app.domain.model.SourcePlaybackResult
import com.youtv.app.domain.model.SourceSelectionPolicy
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
) : AnalyticsListener {
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
    private var playbackSegmentStartedAt = 0L
    private var bufferingSegmentStartedAt = 0L
    private var sessionStarted = false
    private var pausedFirstFrameRemainingMs = 0L
    private var hasBandwidthSample = false
    private var latestBitrateEstimate = 0L
    private var actualVideoWidth: Int? = null
    private var actualVideoHeight: Int? = null
    private var lastPersistedFormat: VideoFormatSnapshot? = null
    private var released = false
    private val attemptGate = PlaybackAttemptGate()
    private var activeAttempt: PlaybackAttempt? = null
    private val controllerJob = SupervisorJob()
    private val scope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)
    private var firstFrameTimeoutJob: Job? = null
    private var rebufferTimeoutJob: Job? = null
    private var recoveryJob: Job? = null
    private var recoveryAttempt = 0

    fun play(channel: Channel, preferredSource: Int = 0) {
        if (released) return
        cancelRecovery(reset = true)
        if (channel.sources.isEmpty()) {
            cancelTimeouts()
            attemptGate.invalidate()
            activeAttempt = null
            player.stop()
            _state.value = PlaybackState.Failed(
                channel, 0, SourceAddressType.UNKNOWN, "频道没有可用播放地址",
            )
            return
        }
        flushSessionStats()
        currentChannel = channel
        val preferred = preferredSource.coerceIn(channel.sources.indices)
        attemptOrder = buildAttemptOrder(channel, preferred, forced = null)
        attemptPosition = 0
        startCurrentSourceAttempt()
    }

    fun selectSource(index: Int) {
        val channel = currentChannel ?: return
        if (channel.sources.isEmpty()) return
        cancelRecovery(reset = true)
        val selected = index.coerceIn(channel.sources.indices)
        flushSessionStats()
        attemptOrder = buildAttemptOrder(channel, selected, forced = selected)
        attemptPosition = 0
        startCurrentSourceAttempt()
    }

    fun retry() {
        val channel = currentChannel ?: return
        if (channel.sources.isEmpty()) return
        cancelRecovery(reset = true)
        flushSessionStats()
        attemptOrder = buildAttemptOrder(
            channel,
            sourceIndex.coerceIn(channel.sources.indices),
            forced = null,
        )
        attemptPosition = 0
        startCurrentSourceAttempt()
    }

    fun updateChannelSnapshot(channel: Channel) {
        val previous = currentChannel ?: return
        if (previous.id != channel.id) return
        val activeUrl = previous.sources.getOrNull(sourceIndex)?.url
        val replacementIndex = activeUrl?.let { url -> channel.sources.indexOfFirst { it.url == url } } ?: -1
        if (replacementIndex < 0) {
            flushSessionStats()
            currentChannel = channel
            if (channel.sources.isEmpty()) {
                cancelTimeouts()
                attemptGate.invalidate()
                activeAttempt = null
                player.stop()
                _state.value = PlaybackState.Failed(
                    channel, 0, SourceAddressType.UNKNOWN, "当前频道的来源已全部屏蔽",
                )
                return
            }
            attemptOrder = buildAttemptOrder(channel, channel.preferredSource, forced = null)
            attemptPosition = 0
            startCurrentSourceAttempt()
            return
        }
        currentChannel = channel
        sourceIndex = replacementIndex
        activeAttempt = activeAttempt?.copy(channel = channel, sourceIndex = replacementIndex)
        attemptOrder = buildAttemptOrder(channel, channel.preferredSource, forced = replacementIndex)
        attemptPosition = 0
    }

    fun pause() {
        cancelRecovery(reset = false)
        if (!renderedFirstFrame && sourceAttemptStartedAt > 0L) {
            pausedFirstFrameRemainingMs = (FIRST_FRAME_TIMEOUT_MILLIS -
                (SystemClock.elapsedRealtime() - sourceAttemptStartedAt)).coerceAtLeast(1L)
        }
        flushSessionStats()
        cancelTimeouts()
        player.pause()
    }

    fun resume() {
        player.play()
        if (_state.value is PlaybackState.Failed) {
            scheduleRecovery()
            return
        }
        val attempt = activeAttempt ?: return
        val channel = attempt.channel
        if (!renderedFirstFrame) {
            val remaining = pausedFirstFrameRemainingMs.takeIf { it > 0L }
                ?: (FIRST_FRAME_TIMEOUT_MILLIS -
                    (SystemClock.elapsedRealtime() - sourceAttemptStartedAt)).coerceAtLeast(1L)
            sourceAttemptStartedAt = SystemClock.elapsedRealtime() - (FIRST_FRAME_TIMEOUT_MILLIS - remaining)
            pausedFirstFrameRemainingMs = 0L
            scheduleFirstFrameTimeout(channel, attempt.sourceIndex, attempt.token, remaining)
        } else if (_state.value is PlaybackState.Buffering) {
            startBufferingSegment()
            scheduleRebufferTimeout(channel, attempt.sourceIndex, attempt.token)
        } else if (renderedFirstFrame) {
            startPlaybackSegment()
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying || player.playWhenReady) pause() else resume()
    }

    fun stop() {
        cancelRecovery(reset = true)
        flushSessionStats()
        cancelTimeouts()
        attemptGate.invalidate()
        activeAttempt = null
        player.stop()
        _state.value = PlaybackState.Idle
    }

    fun release() {
        if (released) return
        cancelRecovery(reset = true)
        flushSessionStats()
        released = true
        cancelTimeouts()
        attemptGate.invalidate()
        activeAttempt = null
        controllerJob.cancel()
        probeCoordinator.release()
        player.removeAnalyticsListener(this)
        player.release()
    }

    fun onNetworkAvailable() {
        if (released || _state.value !is PlaybackState.Failed) return
        cancelRecovery(reset = true)
        retryFailedChannel()
    }

    private fun buildAttemptOrder(channel: Channel, preferred: Int, forced: Int?): List<Int> {
        val ranked = SourceSelectionPolicy.rankedIndices(
            sources = channel.sources,
            preferredIndex = preferred,
            forcedIndex = forced,
        )
        return ranked.sortedWith(compareBy<Int> {
            if (it == forced) -1 else if (probeCoordinator.isHostCoolingDown(channel.sources[it])) 1 else 0
        }.thenBy { ranked.indexOf(it) })
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
        playbackSegmentStartedAt = 0L
        bufferingSegmentStartedAt = 0L
        sessionStarted = false
        pausedFirstFrameRemainingMs = 0L
        hasBandwidthSample = false
        latestBitrateEstimate = 0L
        actualVideoWidth = null
        actualVideoHeight = null
        lastPersistedFormat = null
        _state.value = PlaybackState.Preparing(channel, sourceIndex)
        reportSourceResult(
            channel = channel,
            source = channel.sources[sourceIndex],
            event = SourcePlaybackEventType.ATTEMPT_STARTED,
        )
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
        val remainingBudget = PlaybackTimeBudget.remaining(
            sourceAttemptStartedAt,
            SystemClock.elapsedRealtime(),
            FIRST_FRAME_TIMEOUT_MILLIS,
        )
        if (remainingBudget <= 0L) {
            finishSourceFailure(SourcePlaybackEventType.TIMEOUT, "播放源加载超时")
            return
        }
        firstFrameTimeoutJob?.cancel()
        val attempt = PlaybackAttempt(
            token = attemptGate.next(),
            channel = channel,
            sourceIndex = sourceIndex,
        )
        activeAttempt = attempt
        scheduleFirstFrameTimeout(
            channel = channel,
            scheduledSourceIndex = sourceIndex,
            token = attempt.token,
            timeoutMillis = remainingBudget,
        )
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(createMediaSource(source, modes[sourceTypeIndex], attempt.token))
        player.prepare()
    }

    private fun createMediaSource(source: StreamSource, mode: SourceMode, token: Long): MediaSource {
        val item = MediaItem.Builder()
            .setUri(source.url)
            .setMediaId(mediaIdFor(token))
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
        flushSessionStats()
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
        attemptGate.invalidate()
        activeAttempt = null
        player.stop()
        _state.value = PlaybackState.Failed(
            channel = channel,
            attemptedSources = attemptOrder.size,
            addressType = source?.addressType ?: SourceAddressType.UNKNOWN,
            message = message,
        )
        scheduleRecovery()
    }

    private fun scheduleRecovery() {
        if (recoveryAttempt >= RECOVERY_DELAYS_MILLIS.size || released) return
        recoveryJob?.cancel()
        val delayMillis = RECOVERY_DELAYS_MILLIS[recoveryAttempt++]
        recoveryJob = scope.launch {
            delay(delayMillis)
            recoveryJob = null
            retryFailedChannel()
        }
    }

    private fun retryFailedChannel() {
        val channel = currentChannel ?: return
        if (channel.sources.isEmpty() || released) return
        attemptOrder = buildAttemptOrder(channel, channel.preferredSource, forced = null)
        attemptPosition = 0
        startCurrentSourceAttempt()
    }

    private fun cancelRecovery(reset: Boolean) {
        recoveryJob?.cancel()
        recoveryJob = null
        if (reset) recoveryAttempt = 0
    }

    private fun reportSourceResult(
        channel: Channel,
        source: StreamSource,
        event: SourcePlaybackEventType,
        startupMs: Long? = null,
        bitrateBps: Long? = null,
        videoFormat: VideoFormatSnapshot? = null,
        resultSourceIndex: Int = sourceIndex,
        playbackMs: Long = 0L,
        bufferingMs: Long = 0L,
        sessionIncrement: Int = 0,
    ) {
        onSourceResult(
            SourcePlaybackResult(
                channelId = channel.id,
                sourceUrl = source.url,
                sourceIndex = resultSourceIndex,
                event = event,
                startupMs = startupMs,
                bitrateBps = bitrateBps,
                videoWidth = videoFormat?.width,
                videoHeight = videoFormat?.height,
                videoFrameRate = videoFormat?.frameRate,
                videoCodec = videoFormat?.codec.orEmpty(),
                videoTrackBitrate = videoFormat?.trackBitrate,
                playbackMs = playbackMs,
                bufferingMs = bufferingMs,
                sessionIncrement = sessionIncrement,
            ),
        )
    }

    private fun startPlaybackSegment() {
        if (!sessionStarted || playbackSegmentStartedAt != 0L) return
        playbackSegmentStartedAt = SystemClock.elapsedRealtime()
    }

    private fun startBufferingSegment() {
        if (!sessionStarted || bufferingSegmentStartedAt != 0L) return
        bufferingSegmentStartedAt = SystemClock.elapsedRealtime()
    }

    private fun flushSessionStats() {
        if (!sessionStarted) return
        val now = SystemClock.elapsedRealtime()
        val playbackMs = playbackSegmentStartedAt.takeIf { it > 0L }
            ?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        val bufferingMs = bufferingSegmentStartedAt.takeIf { it > 0L }
            ?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        playbackSegmentStartedAt = 0L
        bufferingSegmentStartedAt = 0L
        if (playbackMs == 0L && bufferingMs == 0L) return
        val channel = currentChannel ?: return
        val source = channel.sources.getOrNull(sourceIndex) ?: return
        reportSourceResult(
            channel = channel,
            source = source,
            event = SourcePlaybackEventType.SESSION_STATS,
            playbackMs = playbackMs,
            bufferingMs = bufferingMs,
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
            if (!released && attemptGate.isCurrent(token) && !renderedFirstFrame &&
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
            if (!released && attemptGate.isCurrent(token) && rebufferEpisodeActive &&
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

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, playbackState: Int) {
        val attempt = activeAttemptFor(eventTime) ?: return
        val channel = attempt.channel
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                _state.value = PlaybackState.Buffering(channel, attempt.sourceIndex)
                if (renderedFirstFrame && player.playWhenReady && !rebufferEpisodeActive) {
                    flushSessionStats()
                    startBufferingSegment()
                    rebufferEpisodeActive = true
                    reportSourceResult(
                        channel,
                        channel.sources[attempt.sourceIndex],
                        SourcePlaybackEventType.FLUCTUATION,
                        resultSourceIndex = attempt.sourceIndex,
                    )
                    scheduleRebufferTimeout(channel, attempt.sourceIndex, attempt.token)
                    probeCoordinator.schedule(
                        channel = channel,
                        currentSourceIndex = attempt.sourceIndex,
                        orderedCandidates = attemptOrder.drop(attemptPosition + 1),
                        delayMillis = PROBE_TRIGGER_MILLIS,
                    )
                }
            }
            Player.STATE_READY -> if (renderedFirstFrame && player.playWhenReady) {
                flushSessionStats()
                startPlaybackSegment()
                rebufferEpisodeActive = false
                rebufferTimeoutJob?.cancel()
                rebufferTimeoutJob = null
                probeCoordinator.cancelActive()
                _state.value = PlaybackState.Playing(channel, attempt.sourceIndex)
            }
        }
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        val attempt = activeAttemptFor(eventTime) ?: return
        if (renderedFirstFrame) return
        val channel = attempt.channel
        val source = channel.sources.getOrNull(attempt.sourceIndex) ?: return
        renderedFirstFrame = true
        cancelRecovery(reset = true)
        sessionStarted = true
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
            event = SourcePlaybackEventType.SESSION_STATS,
            sessionIncrement = 1,
            resultSourceIndex = attempt.sourceIndex,
        )
        startPlaybackSegment()
        reportSourceResult(
            channel = channel,
            source = source,
            event = SourcePlaybackEventType.SUCCESS,
            startupMs = startupMs,
            bitrateBps = bitrate,
            videoFormat = videoFormat,
            resultSourceIndex = attempt.sourceIndex,
        )
    }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        if (activeAttemptFor(eventTime) == null) return
        actualVideoWidth = videoSize.width.takeIf { it > 0 }
        actualVideoHeight = videoSize.height.takeIf { it > 0 }
        persistVideoFormatIfChanged()
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        if (activeAttemptFor(eventTime) == null) return
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

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        if (activeAttemptFor(eventTime) == null) return
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
        if (activeAttemptFor(eventTime) == null) return
        if (totalBytesLoaded > 0L && bitrateEstimate > 0L) {
            hasBandwidthSample = true
            latestBitrateEstimate = bitrateEstimate
        }
    }

    private fun activeAttemptFor(eventTime: AnalyticsListener.EventTime): PlaybackAttempt? {
        val token = eventToken(eventTime) ?: return null
        val attempt = activeAttempt ?: return null
        return attempt.takeIf { it.token == token && attemptGate.isCurrent(token) }
    }

    private fun eventToken(eventTime: AnalyticsListener.EventTime): Long? {
        if (eventTime.timeline.isEmpty || eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) {
            return null
        }
        return runCatching {
            val mediaId = eventTime.timeline
                .getWindow(eventTime.windowIndex, Timeline.Window())
                .mediaItem.mediaId
            mediaId.removePrefix(ATTEMPT_MEDIA_ID_PREFIX).toLongOrNull()
                ?.takeIf { mediaId.startsWith(ATTEMPT_MEDIA_ID_PREFIX) }
        }.getOrNull()
    }

    private fun mediaIdFor(token: Long): String = "$ATTEMPT_MEDIA_ID_PREFIX$token"

    private enum class SourceMode { HLS, DASH, RTSP, RTMP, PROGRESSIVE }

    private data class VideoFormatSnapshot(
        val width: Int,
        val height: Int,
        val frameRate: Float?,
        val codec: String,
        val trackBitrate: Long?,
    )

    private data class PlaybackAttempt(
        val token: Long,
        val channel: Channel,
        val sourceIndex: Int,
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
        const val ATTEMPT_MEDIA_ID_PREFIX = "you-tv-attempt:"
        val RECOVERY_DELAYS_MILLIS = longArrayOf(15_000L, 30_000L, 60_000L)
    }
}
