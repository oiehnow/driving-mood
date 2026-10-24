package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*

/** One request, explicit elapsed time, no clock/IO. Each bucket retains its first frame. */
class SceneAccumulator(private val windowDurationMs: Long = 50_000, private val bucketDurationMs: Long = 1_000) {
    init {
        require(windowDurationMs in 1..50_000 && bucketDurationMs > 0)
        require((windowDurationMs - 1) / bucketDurationMs + 1 <= 50)
    }
    private val buckets = linkedMapOf<Long,SceneSample>()
    private var received = 0
    private var lastElapsed = -1L
    private var lastFrameTime: Long? = null
    private var epochOrigin: Long? = null
    fun add(elapsedMs: Long, vision: VisionResult): Boolean {
        if (elapsedMs !in 0 until windowDurationMs || elapsedMs <= lastElapsed || vision.frameTimeEpochMs < 0) return false
        if (vision.frameTimeEpochMs > 0) {
            if (lastFrameTime?.let { vision.frameTimeEpochMs <= it } == true) return false
            val origin = epochOrigin
            if (origin != null && kotlin.math.abs((vision.frameTimeEpochMs - origin) - elapsedMs) > 10_000) return false
            if (origin == null) epochOrigin = vision.frameTimeEpochMs - elapsedMs
            lastFrameTime = vision.frameTimeEpochMs
        }
        lastElapsed = elapsedMs
        if (received < Int.MAX_VALUE) received++
        val bucket = elapsedMs / bucketDurationMs
        if (bucket !in buckets) buckets[bucket] = SceneSample(elapsedMs, vision.copy(
            reasons=vision.reasons.toList(),debugRegions=vision.debugRegions.toList(),
            sceneObservations=vision.sceneObservations?.let { it.copy(notes=it.notes.toList(),regions=it.regions.toList(),
                semantic=it.semantic?.let { s -> s.copy(signLightRegions=s.signLightRegions?.toList()) }) }))
        return true
    }
    fun snapshot() = SceneWindow(windowDurationMs,bucketDurationMs,received,buckets.values.map { sample ->
        sample.copy(vision=sample.vision.copy(sceneObservations=sample.vision.sceneObservations?.let { o ->
            o.copy(notes=o.notes.toList(),regions=o.regions.toList(),semantic=o.semantic?.let { s ->
                s.copy(signLightRegions=s.signLightRegions?.toList()) }) }))
    })
    fun clear() { buckets.clear(); received=0; lastElapsed=-1; lastFrameTime=null; epochOrigin=null }
}
