package kr.urbanmood.integration

import android.graphics.Bitmap
import kr.urbanmood.camera.RecordedClip
import kr.urbanmood.camera.RecordedVideoDecoder
import kr.urbanmood.contracts.*
import kr.urbanmood.recommendation.SceneAccumulator
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/** The decoder and B analyzer share one worker; close is queued after any cancelled frame. */
class RecordedSceneAnalyzer(private val analyzer: VisionAnalyzer) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DrivingMood-recorded") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var closed = false
    suspend fun analyze(clip: RecordedClip,
        observed: suspend (VisionResult, Bitmap, Long, Int) -> Unit,
    ): CapturedScene = withContext(dispatcher) {
        check(!closed)
        val accumulator = SceneAccumulator(clip.timing.durationMs)
        var latest: VisionResult? = null
        var sequence = 0L
        var firstPts: Long? = null
        try {
            RecordedVideoDecoder().decode(clip) { bitmap, pts, elapsed ->
                currentCoroutineContext().ensureActive()
                val origin = firstPts ?: pts.also { firstPts = it }
                val result = analyzer.analyze(CameraFrame(bitmap, clip.timing.startedAtEpochMs + elapsed,
                    (pts - origin) * 1000, ++sequence, FrameInputSource.ANDROID_RECORDED, pts))
                if (accumulator.add(elapsed, result)) {
                    latest = result
                    observed(result, bitmap, elapsed, accumulator.snapshot().samples.size)
                }
            }
            val window = accumulator.snapshot()
            check(window.samples.any { it.vision.status in setOf(AnalysisStatus.OK, AnalysisStatus.LOW_CONFIDENCE) }) {
                "영상에서 사용할 수 있는 관측을 얻지 못했습니다. 다시 촬영해 주세요."
            }
            CapturedScene(requireNotNull(latest), window, clip.timing.endedAtEpochMs, clip.timing.startedAtEpochMs)
        } finally { accumulator.clear() }
    }
    override fun close() {
        if (closed) return
        closed = true
        executor.execute { analyzer.close() }
        dispatcher.close()
    }
}
