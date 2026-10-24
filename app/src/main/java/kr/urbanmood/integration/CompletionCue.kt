package kr.urbanmood.integration

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kr.urbanmood.R

/** Short local UI sound. No music queue changes, microphone, network, or forced volume. */
class CompletionCue(context: Context) : AutoCloseable {
    private var loaded = false
    private var closed = false
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val sound: Int
    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        sound = pool.load(context, R.raw.mood_complete, 1)
    }
    fun play() { if (!closed && loaded) pool.play(sound, .6f, .6f, 1, 0, 1f) }
    override fun close() { if (!closed) { closed = true; pool.release() } }
}
