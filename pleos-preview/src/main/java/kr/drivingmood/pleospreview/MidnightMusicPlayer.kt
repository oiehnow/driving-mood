package kr.drivingmood.pleospreview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Activity bridge to service-owned playback. Main-thread API and callbacks.
 * close() only detaches this UI; stopAndClose() explicitly stops music and the service.
 * Published artwork is shared read-only; clear ImageView references, never recycle it.
 */
class MidnightMusicPlayer(context: Context, onState: (State) -> Unit) : AutoCloseable {
    data class State(
        val title: String = "Nightcall",
        val artist: String = "Kavinsky",
        val art: Bitmap? = null,
        val prepared: Boolean = false,
        val playing: Boolean = false,
        val stopped: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    private val app = context.applicationContext
    private var observer: ((State) -> Unit)? = onState
    private var service: MidnightPlaybackService.LocalBinder? = null
    private var binding = false
    private var closed = false
    private var foreground = true
    var state = State()
        private set
    private val receive: (State) -> Unit = { next -> if (!closed) publish(next) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            service = binder as? MidnightPlaybackService.LocalBinder
            service?.addListener(receive)
            service?.prepare()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            if (!closed) publish(state.copy(prepared = false, playing = false, art = null,
                error = "음악 서비스 연결이 끊겼습니다. 앱에서 다시 불러와 주세요."))
        }
    }

    init { requireMainThread() }

    /** Attach/preload. Reattaching during rotation never resets a service's current track. */
    fun prepare() {
        requireMainThread()
        if (closed) return
        service?.let { it.prepare(); return }
        if (binding) return
        try {
            binding = app.bindService(Intent(app, MidnightPlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!binding) publish(state.copy(error = "음악 서비스에 연결하지 못했습니다."))
        } catch (_: Exception) { publish(state.copy(error = "음악 서비스에 연결하지 못했습니다.")) }
    }

    /** Optional compatibility hook. false blocks new UI play requests, but NEVER pauses music. */
    fun setForeground(enabled: Boolean) { requireMainThread(); foreground = enabled }

    /** Only call from an explicit play button in the foreground Activity. */
    fun play() {
        requireMainThread()
        if (closed) return
        if (!foreground || !state.prepared) {
            publish(state.copy(error = if (!foreground) "앱 화면에서 재생해 주세요." else "음악 준비가 끝난 뒤 재생해 주세요."))
            return
        }
        try {
            app.startForegroundService(Intent(app, MidnightPlaybackService::class.java).setAction(MidnightPlaybackService.ACTION_PLAY))
        } catch (_: Exception) { publish(state.copy(error = "음악 서비스를 시작하지 못했습니다. 앱 화면에서 다시 재생해 주세요.")) }
    }

    fun pause() { requireMainThread(); if (!closed) service?.pause() }
    fun seekTo(positionMs: Long) { requireMainThread(); if (!closed) service?.seekTo(positionMs) }

    /** Explicit app-close button: stop playback/notification first, then detach the UI. */
    fun stopAndClose() {
        requireMainThread()
        if (closed) return
        service?.stopAndRelease()
        app.stopService(Intent(app, MidnightPlaybackService::class.java))
        close()
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        service?.removeListener(receive)
        service = null
        if (binding) runCatching { app.unbindService(connection) }
        binding = false
        publish(state.copy(art = null))
        observer = null
    }

    private fun publish(next: State) { state = next; observer?.invoke(next) }
    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
    companion object { const val FILE_NAME = "Kavinsky - Nightcall.mp3" }
}

/** Media resources belong to MidnightPlaybackService, never an Activity. */
internal class MidnightPlaybackEngine(context: Context, onState: (MidnightMusicPlayer.State) -> Unit) : AutoCloseable {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val metadataWorker = Executors.newSingleThreadExecutor()
    private var metadataTask: Future<*>? = null
    private var observer: ((MidnightMusicPlayer.State) -> Unit)? = onState
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            if (!closed && change in setOf(AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                pause()
            }
            // Focus gain never resumes music. A fresh user play action is required.
        }, main).build()
    private var player: MediaPlayer? = null
    private var foreground = false
    private var receiverRegistered = false
    @Volatile private var closed = false
    private var generation = 0L
    var state = MidnightMusicPlayer.State()
        private set

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!closed && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (closed || !state.playing) return
            updatePosition()
            if (state.playing) main.postDelayed(this, 500)
        }
    }
    private val prepareTimeout = Runnable {
        if (!closed && player != null && !state.prepared) fail("음악을 불러오는 데 시간이 오래 걸립니다. 다시 불러와 주세요.")
    }

    init { requireMainThread() }

    /** Call once after constructing the helper, or explicitly to retry a missing/broken file. */
    fun prepare() {
        requireMainThread()
        if (closed) return
        generation++
        val requestGeneration = generation
        metadataTask?.cancel(true)
        releasePlayback()
        publish(MidnightMusicPlayer.State())
        val directory = app.getExternalFilesDir("music")
        val externalFile = directory?.let { File(it, FILE_NAME) }
        val privateFile = File(File(app.filesDir, "music"), FILE_NAME)
        val file = externalFile?.takeIf { it.isFile && it.canRead() } ?: privateFile
        if (file == null || !file.isFile || !file.canRead()) {
            fail("음악 파일을 찾을 수 없습니다. 앱의 음악 폴더에 파일을 넣고 다시 불러와 주세요.")
            return
        }
        readMetadata(file, requestGeneration)
        try {
            val current = MediaPlayer()
            player = current
            current.setAudioAttributes(attributes)
            current.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            current.setOnPreparedListener {
                if (closed || player !== current) return@setOnPreparedListener
                main.removeCallbacks(prepareTimeout)
                val duration = runCatching { current.duration.toLong().coerceAtLeast(0) }.getOrElse {
                    fail("음악 길이를 확인하지 못했습니다."); return@setOnPreparedListener
                }
                publish(state.copy(prepared = true, playing = false, durationMs = duration, error = null))
            }
            current.setOnCompletionListener {
                if (closed || player !== current) return@setOnCompletionListener
                main.removeCallbacks(ticker)
                abandonFocus()
                unregisterNoisyReceiver()
                publish(state.copy(playing = false, positionMs = state.durationMs))
            }
            current.setOnSeekCompleteListener {
                if (!closed && player === current && state.prepared) updatePosition()
            }
            current.setOnErrorListener { _, _, _ ->
                if (!closed && player === current) fail("음악을 재생할 수 없습니다. 로컬 파일을 확인하고 다시 불러와 주세요.")
                true
            }
            current.setDataSource(file.absolutePath)
            current.prepareAsync()
            main.postDelayed(prepareTimeout, 15_000)
        } catch (_: Exception) {
            fail("음악 파일을 열지 못했습니다. 파일을 확인하고 다시 불러와 주세요.")
        }
    }

    /** Foreground entry does not resume. Foreground exit pauses and cancels focus. */
    fun setForeground(enabled: Boolean) {
        requireMainThread()
        if (closed) return
        foreground = enabled
        if (!enabled) pause()
    }

    /** Invoke only from the user's play button, and enable that button when prepared. */
    fun play() {
        requireMainThread()
        if (closed || state.playing) return
        if (!foreground || !state.prepared) {
            publish(state.copy(error = if (!foreground) "앱 화면에서 재생해 주세요." else "음악 준비가 끝난 뒤 재생해 주세요."))
            return
        }
        val current = player ?: return
        try {
            if (audioManager?.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                publish(state.copy(error = "지금은 음악을 재생할 수 없습니다. 잠시 후 재생을 눌러 주세요."))
                return
            }
            registerNoisyReceiver()
            current.start()
            publish(state.copy(playing = true, error = null))
            main.removeCallbacks(ticker)
            main.post(ticker)
        } catch (_: Exception) { fail("음악 재생을 시작하지 못했습니다. 다시 불러와 주세요.") }
    }

    fun pause() {
        requireMainThread()
        if (closed) return
        main.removeCallbacks(ticker)
        try {
            val current = player
            if (current != null && state.prepared) {
                if (current.isPlaying) current.pause()
                publish(state.copy(playing = false, positionMs = current.currentPosition.toLong().coerceAtLeast(0)))
            }
        } catch (_: Exception) { fail("음악을 일시정지하지 못했습니다. 다시 불러와 주세요.") }
        abandonFocus()
        unregisterNoisyReceiver()
    }

    /** Milliseconds, clamped to actual duration. Seeking while paused never starts playback. */
    fun seekTo(positionMs: Long) {
        requireMainThread()
        if (closed || !state.prepared) return
        try {
            player?.seekTo(positionMs.coerceIn(0, state.durationMs), MediaPlayer.SEEK_CLOSEST)
            // Publish actual position in onSeekComplete, not the requested position as a result.
        } catch (_: Exception) { fail("재생 위치를 변경하지 못했습니다. 다시 불러와 주세요.") }
    }

    private fun updatePosition() {
        try {
            val current = player ?: return
            if (state.prepared) publish(state.copy(positionMs = current.currentPosition.toLong().coerceAtLeast(0)))
        } catch (_: Exception) { fail("재생 위치를 확인하지 못했습니다. 다시 불러와 주세요.") }
    }

    private fun readMetadata(file: File, requestGeneration: Long) {
        metadataTask = metadataWorker.submit {
            val retriever = MediaMetadataRetriever()
            var art: Bitmap? = null
            var title: String? = null
            var artist: String? = null
            try {
                retriever.setDataSource(file.absolutePath)
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                retriever.embeddedPicture?.let { bytes ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                    art = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } catch (_: Exception) {
                // Optional tags/art do not block playback; the explicit filename identifies the track.
            } finally { runCatching { retriever.release() } }
            if (closed || Thread.currentThread().isInterrupted) {
                art?.recycle() // Not published to any View.
                return@submit
            }
            val filename = file.nameWithoutExtension.split(" - ", limit = 2)
            val resolvedTitle = filename.getOrNull(1)?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: "Nightcall"
            val resolvedArtist = filename.takeIf { it.size == 2 }?.first()?.takeIf { it.isNotBlank() }
                ?: artist?.takeIf { it.isNotBlank() } ?: "Kavinsky"
            main.post {
                if (closed || generation != requestGeneration) art?.recycle()
                else publish(state.copy(title = resolvedTitle, artist = resolvedArtist, art = art))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerNoisyReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(noisyReceiver, filter)
        receiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (receiverRegistered) runCatching { app.unregisterReceiver(noisyReceiver) }
        receiverRegistered = false
    }

    private fun abandonFocus() { runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) } }

    private fun releasePlayback() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(prepareTimeout)
        val old = player
        player = null
        runCatching { old?.release() }
        abandonFocus()
        unregisterNoisyReceiver()
    }

    private fun fail(message: String) {
        releasePlayback()
        publish(state.copy(prepared = false, playing = false, error = message))
    }

    private fun publish(next: MidnightMusicPlayer.State) {
        state = next
        observer?.invoke(next)
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        foreground = false
        generation++
        metadataTask?.cancel(true)
        metadataTask = null
        metadataWorker.shutdownNow()
        releasePlayback()
        // Keep metadata result delivery queued: it recycles only unpublished images when closed.
        publish(state.copy(art = null, prepared = false, playing = false))
        observer = null
    }

    private fun requireMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object { const val FILE_NAME = "Kavinsky - Nightcall.mp3" }
}
