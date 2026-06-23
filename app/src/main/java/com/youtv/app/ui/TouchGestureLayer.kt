package com.youtv.app.ui

import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.playbackGestures(
    enabled: Boolean,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onShowChannels: () -> Unit,
    onShowSettings: () -> Unit,
    onShowProgram: () -> Unit,
    onShowInfo: () -> Unit,
    onVolumeChanged: (current: Int, maximum: Int) -> Unit,
): Modifier {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maximumVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    val touchSlop = remember { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    val doubleTapSlop = remember { ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat() }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tracking = remember { GestureTracking() }

    val currentEnabled by rememberUpdatedState(enabled)
    val currentPrevious by rememberUpdatedState(onPreviousChannel)
    val currentNext by rememberUpdatedState(onNextChannel)
    val currentChannels by rememberUpdatedState(onShowChannels)
    val currentSettings by rememberUpdatedState(onShowSettings)
    val currentProgram by rememberUpdatedState(onShowProgram)
    val currentInfo by rememberUpdatedState(onShowInfo)
    val currentVolumeChanged by rememberUpdatedState(onVolumeChanged)

    return this
        .onSizeChanged { size = it }
        .pointerInteropFilter { event ->
            if (!currentEnabled) {
                tracking.cancelAll()
                return@pointerInteropFilter false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking.start = Offset(event.x, event.y)
                    tracking.startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    tracking.adjustingVolume = false
                    tracking.moved = false
                    tracking.longPressTriggered = false
                    tracking.active = true
                    tracking.longPressJob?.cancel()
                    tracking.longPressJob = scope.launch {
                        delay(LONG_PRESS_MILLIS)
                        if (tracking.active && !tracking.moved) {
                            tracking.longPressTriggered = true
                            tracking.pendingSingleTap?.cancel()
                            tracking.lastTapUpTime = 0L
                            currentProgram()
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> if (tracking.active && size.height > 0) {
                    val deltaX = event.x - tracking.start.x
                    val deltaY = event.y - tracking.start.y
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) {
                        tracking.moved = true
                        tracking.longPressJob?.cancel()
                    }
                    if (abs(deltaY) > touchSlop && abs(deltaY) > abs(deltaX) * DIRECTION_RATIO) {
                        tracking.adjustingVolume = true
                        val delta = (-deltaY / size.height * maximumVolume).roundToInt()
                        val target = (tracking.startVolume + delta).coerceIn(0, maximumVolume)
                        if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                            currentVolumeChanged(target, maximumVolume)
                        }
                    }
                }

                MotionEvent.ACTION_UP -> if (tracking.active) {
                    tracking.longPressJob?.cancel()
                    val deltaX = event.x - tracking.start.x
                    val deltaY = event.y - tracking.start.y
                    val horizontalThreshold = size.width * CHANNEL_SWIPE_FRACTION
                    if (tracking.longPressTriggered) {
                        tracking.finishGesture()
                    } else if (!tracking.adjustingVolume && abs(deltaX) >= horizontalThreshold &&
                        abs(deltaX) > abs(deltaY) * DIRECTION_RATIO
                    ) {
                        if (deltaX < 0) currentNext() else currentPrevious()
                        tracking.finishGesture()
                    } else if (!tracking.moved) {
                        val tap = Offset(event.x, event.y)
                        val elapsed = event.eventTime - tracking.lastTapUpTime
                        val closeToFirstTap = (tap - tracking.lastTapPosition).getDistance() <= doubleTapSlop
                        if (tracking.lastTapUpTime > 0 && elapsed <= DOUBLE_TAP_MILLIS && closeToFirstTap) {
                            tracking.pendingSingleTap?.cancel()
                            val firstTapX = tracking.lastTapPosition.x
                            tracking.lastTapUpTime = 0L
                            if (size.width > 0 && firstTapX < size.width * CHANNEL_ZONE_FRACTION) {
                                currentChannels()
                            } else {
                                currentSettings()
                            }
                        } else {
                            tracking.pendingSingleTap?.cancel()
                            tracking.lastTapUpTime = event.eventTime
                            tracking.lastTapPosition = tap
                            val scheduledTapTime = tracking.lastTapUpTime
                            tracking.pendingSingleTap = scope.launch {
                                delay(DOUBLE_TAP_MILLIS)
                                if (tracking.lastTapUpTime == scheduledTapTime) {
                                    tracking.lastTapUpTime = 0L
                                    currentInfo()
                                }
                            }
                        }
                        tracking.finishGesture()
                    } else {
                        tracking.finishGesture()
                    }
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tracking.cancelActiveGesture()
            }
            true
        }
}

private class GestureTracking {
    var start: Offset = Offset.Zero
    var startVolume: Int = 0
    var adjustingVolume: Boolean = false
    var moved: Boolean = false
    var longPressTriggered: Boolean = false
    var active: Boolean = false
    var lastTapUpTime: Long = 0L
    var lastTapPosition: Offset = Offset.Unspecified
    var pendingSingleTap: Job? = null
    var longPressJob: Job? = null

    fun finishGesture() {
        adjustingVolume = false
        moved = false
        longPressTriggered = false
        active = false
    }

    fun cancelActiveGesture() {
        longPressJob?.cancel()
        finishGesture()
    }

    fun cancelAll() {
        longPressJob?.cancel()
        pendingSingleTap?.cancel()
        lastTapUpTime = 0L
        lastTapPosition = Offset.Unspecified
        finishGesture()
    }
}

private const val CHANNEL_ZONE_FRACTION = 0.75f
private const val CHANNEL_SWIPE_FRACTION = 0.15f
private const val DIRECTION_RATIO = 1.2f
private const val DOUBLE_TAP_MILLIS = 320L
private const val LONG_PRESS_MILLIS = 650L
