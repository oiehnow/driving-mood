package kr.drivingmood.pleospreview

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Preloaded user-provided click sound. All methods must run on the main thread.
 * Call setForeground(true/false) from onResume/onPause, cancel() when the action
 * is cancelled, and close() from onDestroy. Foreground defaults to false.
 * No audio focus, music queue, volume override, network, or automatic replay.
 */
class ClickSoundPlayer(context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val pool: SoundPool?
    private var soundId = 0
    private var streamId = 0
    private var loaded = false
    private var failed = false
    private var foreground = false
    private var closed = false
    private var pendingClickAt: Long? = null

    init {
        requireMainThread()
        pool = runCatching {
            SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        }.getOrNull()
        if (pool == null) {
            failed = true
        } else {
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                // Posting also ensures load() assigned soundId before this is processed.
                main.post {
                    if (!closed && sampleId == soundId) {
                        loaded = status == 0
                        failed = status != 0
                        val clickedAt = pendingClickAt
                        pendingClickAt = null
                        if (loaded && foreground && clickedAt != null &&
                            SystemClock.elapsedRealtime() - clickedAt in 0..MAX_PENDING_MS) {
                            playLoaded()
                        }
                    }
                }
            }
            soundId = runCatching { pool?.load(context.applicationContext, R.raw.mood_click, 1) ?: 0 }.getOrDefault(0)
            if (soundId == 0) failed = true
        }
    }

    /** Enabling only allows future clicks; it never resumes an old sound. */
    fun setForeground(enabled: Boolean) {
        requireMainThread()
        if (closed) return
        foreground = enabled
        if (!enabled) cancel()
    }

    /** One user click. At most the latest unloaded click can play within 250 ms. */
    fun play() {
        requireMainThread()
        if (closed || !foreground || failed) return
        if (loaded) {
            pendingClickAt = null
            playLoaded()
        } else {
            pendingClickAt = SystemClock.elapsedRealtime()
        }
    }

    /** Cancels both a pending first click and any currently playing click. */
    fun cancel() {
        requireMainThread()
        pendingClickAt = null
        if (!closed && streamId != 0) runCatching { pool?.stop(streamId) }
        streamId = 0
    }

    private fun playLoaded() {
        if (closed || !foreground || !loaded) return
        if (streamId != 0) runCatching { pool?.stop(streamId) }
        // 0 means playback was not started; do not retry later without another click.
        streamId = runCatching { pool?.play(soundId, .6f, .6f, 1, 0, 1f) ?: 0 }.getOrDefault(0)
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        foreground = false
        cancel()
        closed = true
        loaded = false
        main.removeCallbacksAndMessages(null)
        runCatching { pool?.setOnLoadCompleteListener(null) }
        runCatching { pool?.release() }
    }

    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    private companion object {
        const val MAX_PENDING_MS = 250L
    }
}

