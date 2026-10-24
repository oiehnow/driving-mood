package kr.urbanmood.camera

import java.io.File

/** A private finalized file. Its owner deletes it after decoding, failure, or cancellation. */
class RecordedClip(val file: File, val timing: RecordedTiming) : AutoCloseable {
    override fun close() { file.delete() }
}

/** Recorder duration is media time; host start time is an explicit wall-clock anchor. */
data class RecordedTiming(val requestedMs: Long, val durationMs: Long, val startedAtEpochMs: Long) {
    val endedAtEpochMs get() = startedAtEpochMs + durationMs
    init { require(requestedMs in setOf(30_000L, 60_000L) && durationMs in 1..requestedMs && startedAtEpochMs > 0) }
}

/** One first decoded presentation timestamp per second. Never invent a seek timestamp. */
class PresentationSampler(private val durationMs: Long) {
    init { require(durationMs in 1..60_000) }
    private var firstPtsUs: Long? = null
    private var lastPtsUs = -1L
    private var lastBucket = -1L
    fun accept(ptsUs: Long): Long? {
        if (ptsUs < 0 || ptsUs <= lastPtsUs) return null
        lastPtsUs = ptsUs
        val origin = firstPtsUs ?: ptsUs.also { firstPtsUs = it }
        val elapsedMs = (ptsUs - origin) / 1000
        if (elapsedMs !in 0 until durationMs) return null
        val bucket = elapsedMs / 1000
        if (bucket <= lastBucket) return null
        lastBucket = bucket
        return elapsedMs
    }
}

internal fun validRecordingDuration(requestedMs: Long, actualMs: Long) =
    requestedMs in setOf(30_000L, 60_000L) && actualMs in (requestedMs - 1_000)..(requestedMs + 2_000)
