package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*

/** One request, explicit elapsed time, no clock/IO. Each bucket retains its first frame. */
class SceneAccumulator(private val windowDurationMs: Long = 50_000, private val bucketDurationMs: Long = 1_000) {
    init {
        require(windowDurationMs in 1..60_000 && bucketDurationMs > 0)
        require((windowDurationMs - 1) / bucketDurationMs + 1 <= 60)
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
            sceneObservations=vision.sceneObservations?.detached() ))
        return true
    }
    fun snapshot() = SceneWindow(windowDurationMs,bucketDurationMs,received,buckets.values.map { sample ->
        sample.copy(vision=sample.vision.copy(reasons=sample.vision.reasons.toList(),debugRegions=sample.vision.debugRegions.toList(),
            sceneObservations=sample.vision.sceneObservations?.detached()))
    })
    fun clear() { buckets.clear(); received=0; lastElapsed=-1; lastFrameTime=null; epochOrigin=null }

    private fun LightPixelStats.detached() = copy(categoryCounts=categoryCounts.toList(),upperChannelCounts=upperChannelCounts.toList())
    private fun SceneObservations.detached() = copy(notes=notes.toList(),regions=regions.toList(),
        semantic=semantic?.let { it.copy(signLightRegions=it.signLightRegions?.toList()) },
        lightFeatures=lightFeatures?.let { f -> f.copy(summary=f.summary.detached(),
            groups=f.groups?.map { it.copy(pixels=it.pixels.detached()) },
            quality=f.quality.copy(flags=f.quality.flags.toList(),reasons=f.quality.reasons.toList())) })
}
