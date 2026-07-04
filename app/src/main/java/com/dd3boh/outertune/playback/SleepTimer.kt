package com.dd3boh.outertune.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class SleepTimer(
    private val scope: CoroutineScope,
    val player: Player,
) : Player.Listener {
    private var sleepTimerJob: Job? = null
    var triggerTime by mutableLongStateOf(-1L)
        private set
    var pauseWhenSongEnd by mutableStateOf(false)
        private set
    val isActive: Boolean
        get() = triggerTime != -1L || pauseWhenSongEnd

    private val _fadeFactor = MutableStateFlow(1f)
    val fadeFactor: StateFlow<Float> = _fadeFactor.asStateFlow()

    /** Action taken when the minute-based countdown reaches zero. Defaults to pausing playback. */
    var onCountdownFinish: () -> Unit = { player.pause() }

    fun start(minute: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _fadeFactor.value = 1f
        pauseWhenSongEnd = false
        triggerTime = -1L
        if (minute == -1) {
            pauseWhenSongEnd = true
        } else {
            val endTime = System.currentTimeMillis() + minute.minutes.inWholeMilliseconds
            triggerTime = endTime
            sleepTimerJob = scope.launch {
                val untilFade = endTime - FADE_DURATION_MS - System.currentTimeMillis()
                if (untilFade > 0) delay(untilFade)
                while (true) {
                    val remaining = endTime - System.currentTimeMillis()
                    if (remaining <= 0) break
                    _fadeFactor.value = fadeFactorFor(remaining)
                    delay(FADE_TICK_MS)
                }
                onCountdownFinish()
                triggerTime = -1L
                _fadeFactor.value = 1f
            }
        }
    }

    fun clear() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pauseWhenSongEnd = false
        triggerTime = -1L
        _fadeFactor.value = 1f
    }

    /** Volume multiplier that fades linearly to zero over the final [FADE_DURATION_MS]. */
    private fun fadeFactorFor(remainingMs: Long): Float {
        if (remainingMs >= FADE_DURATION_MS) return 1f
        return remainingMs.toFloat() / FADE_DURATION_MS
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            player.pause()
        }
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            player.pause()
        }
    }

    companion object {
        private const val FADE_DURATION_MS = 30_000L
        private const val FADE_TICK_MS = 50L
    }
}