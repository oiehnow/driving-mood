package kr.urbanmood.audio

import android.content.*
import android.media.*
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import kr.urbanmood.contracts.*
import kotlinx.coroutines.flow.*

/** Android's installed media framework decodes user-selected local files.
 * One looper owns all MediaPlayer operations, including URI opening and callbacks.
 * No streaming URL, YouTube decoder, background service or bundled catalog.
 */
class LocalAudioPlayer(context: Context) : AudioPlayer {
    private val app = context.applicationContext
    private val thread = HandlerThread("UrbanMood-local-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    @Volatile private var closed = false
    private var player: MediaPlayer? = null
    private var prepared = false
    private var queue = emptyList<LocalTrack>()
    private val policy = QueuePolicy()
    private val intent = PlaybackIntent()
    private var hasFocus = false
    private var receiverRegistered = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, handler).build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, received: Intent?) {
            if (received?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private val positionTick = object : Runnable {
        override fun run() {
            if (closed || !prepared) return
            try {
                val p = player ?: return
                mutableState.value = state.value.copy(positionMs = p.currentPosition.toLong())
                if (state.value.status == PlaybackStatus.PLAYING) handler.postDelayed(this, 1000)
            } catch (_: RuntimeException) { fail("재생 위치를 확인하지 못했습니다. 다시 재생해 주세요.") }
        }
    }
    private val prepareTimeout = Runnable { if (!prepared && player != null) fail("음악을 불러오는 데 시간이 오래 걸립니다. 음악 파일을 다시 선택해 주세요.") }

    private fun dispatch(action: () -> Unit) {
        if (!closed) handler.post { if (!closed) action() }
    }

    override fun setQueue(tracks: List<LocalTrack>) {
        val snapshot = tracks.toList()
        dispatch {
            queue = snapshot.filter { it.uri.scheme in setOf("content", "file", "android.resource") }.distinctBy { it.id }
            if (player == null && state.value.currentTrack == null) mutableState.value = PlaybackState(
                if (queue.isEmpty()) PlaybackStatus.UNAVAILABLE else PlaybackStatus.IDLE,
                error = if (queue.isEmpty()) "재생할 음악이 없습니다. 음악 파일을 선택해 주세요." else null)
        }
    }

    override fun play() = dispatch {
        intent.play()
        if (prepared && player != null) beginPlayback()
        else if (player == null) {
            val current = state.value.currentTrack
            val next = queue.firstOrNull { it.id == current?.id } ?: chooseNext()
            if (next == null) empty() else load(next)
        }
    }

    override fun pause() = dispatch { intent.pause(); pauseActual(); abandonFocus() }

    override fun next() = dispatch {
        intent.play()
        val next = chooseNext()
        if (next == null) { releasePlayer(); empty() } else load(next)
    }

    private fun chooseNext(): LocalTrack? {
        val id = policy.choose(queue.map { it.id }, state.value.currentTrack?.id)
        return queue.firstOrNull { it.id == id }
    }

    private fun load(track: LocalTrack) {
        releasePlayer()
        mutableState.value = PlaybackState(PlaybackStatus.LOADING, track)
        try {
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(attributes)
            p.setOnPreparedListener {
                if (player !== p || closed) return@setOnPreparedListener
                handler.removeCallbacks(prepareTimeout)
                prepared = true
                mutableState.value = state.value.copy(durationMs = p.duration.toLong().takeIf { it >= 0 })
                if (intent.wantsPlay) beginPlayback() else mutableState.value = state.value.copy(status = PlaybackStatus.PAUSED)
            }
            p.setOnCompletionListener {
                if (player !== p || closed) return@setOnCompletionListener
                mutableState.value = state.value.copy(status = PlaybackStatus.ENDED, positionMs = state.value.durationMs ?: state.value.positionMs)
                if (intent.wantsPlay) {
                    val next = chooseNext()
                    if (next != null) load(next) else { releasePlayer(); intent.pause(); abandonFocus() }
                }
            }
            p.setOnErrorListener { _, _, _ -> if (player === p) fail("음악을 재생할 수 없습니다. 음악 파일을 다시 선택하거나 다른 파일을 선택해 주세요."); true }
            p.setDataSource(app, track.uri)
            p.prepareAsync()
            handler.postDelayed(prepareTimeout, 15_000)
        } catch (_: Exception) { fail("음악 파일을 열 수 없습니다. 파일이 있는지 확인한 뒤 다시 선택해 주세요.") }
    }

    private fun beginPlayback() {
        val p = player ?: return
        if (!prepared || !intent.wantsPlay) return
        try {
            if (!hasFocus) hasFocus = audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) {
                intent.pause()
                mutableState.value = state.value.copy(status = PlaybackStatus.PAUSED, error = "지금은 음악을 재생할 수 없습니다. 이 앱을 화면에 열고 다시 재생해 주세요.")
                return
            }
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            p.start()
            policy.played(state.value.currentTrack!!.id)
            mutableState.value = state.value.copy(status = PlaybackStatus.PLAYING, error = null)
            handler.removeCallbacks(positionTick); handler.post(positionTick)
        } catch (_: Exception) { fail("음악 재생을 시작하지 못했습니다. 다시 재생해 주세요.") }
    }

    private fun pauseActual() {
        handler.removeCallbacks(positionTick)
        if (prepared && player != null) {
            try {
                if (player!!.isPlaying) player!!.pause()
                mutableState.value = state.value.copy(status = PlaybackStatus.PAUSED, positionMs = player!!.currentPosition.toLong())
            } catch (_: RuntimeException) { fail("음악을 일시정지하지 못했습니다. 다시 재생해 주세요.") }
        }
    }

    private fun focusChanged(change: Int) {
        if (closed) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (intent.gainFocus()) { hasFocus = true; beginPlayback() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false; intent.loseFocus(transient = true); pauseActual()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false; intent.loseFocus(transient = false); pauseActual(); abandonFocus()
            }
        }
    }

    private fun abandonFocus() { runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }; hasFocus = false }
    private fun releasePlayer() {
        handler.removeCallbacks(positionTick); handler.removeCallbacks(prepareTimeout)
        val old = player; player = null; prepared = false
        runCatching { old?.release() }
    }
    private fun empty() { intent.pause(); abandonFocus(); mutableState.value = PlaybackState(error = "재생할 음악이 없습니다. 음악 파일을 선택해 주세요.") }
    private fun fail(message: String) {
        releasePlayer(); intent.pause(); abandonFocus()
        mutableState.value = state.value.copy(status = PlaybackStatus.ERROR, error = message)
    }
    override fun close() {
        if (closed) return
        closed = true
        handler.post {
            releasePlayer(); intent.pause(); abandonFocus()
            if (receiverRegistered) runCatching { app.unregisterReceiver(noisyReceiver) }
            receiverRegistered = false
            mutableState.value = PlaybackState(error = "음악 재생을 종료했습니다.")
            thread.quitSafely()
        }
    }
}
