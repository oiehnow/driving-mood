package kr.urbanmood.integration

import kr.urbanmood.camera.FrameReceipt
import kr.urbanmood.contracts.*
import kr.urbanmood.recommendation.SceneAccumulator
import kotlinx.coroutines.*

/** All callbacks run on the caller's main dispatcher. No images are retained here. */
class TimedSceneCapture(
    private val durationMs: Long = 50_000,
    private val elapsedNow: () -> Long,
    private val epochNow: () -> Long = System::currentTimeMillis,
) {
    suspend fun capture(
        start: ((VisionResult, FrameReceipt) -> Unit, (String) -> Unit) -> Unit,
        stop: () -> Unit,
        progress: (elapsedMs: Long, frames: Int) -> Unit = { _, _ -> },
        observed: (VisionResult, FrameReceipt) -> Unit = { _, _ -> },
    ): CapturedScene = coroutineScope {
        val accumulator = SceneAccumulator(windowDurationMs = durationMs)
        val started = elapsedNow()
        val failure = CompletableDeferred<String>()
        var accepting = true
        var stopped = false
        fun stopOnce() { if (!stopped) { stopped = true; stop() } }
        var latest: VisionResult? = null
        val ticker = launch {
            while (isActive) {
                progress((elapsedNow() - started).coerceIn(0, durationMs), accumulator.snapshot().receivedFrameCount)
                delay(1_000)
            }
        }
        try {
            start({ value, receipt ->
                val delivered = elapsedNow() - started
                val captured = receipt.receivedAtElapsedMs - started
                // Reject both an old frame and an in-window frame delivered after shutdown.
                if (accepting && isActive && delivered in 0 until durationMs && captured in 0 until durationMs) {
                    if (accumulator.add(captured, value)) {
                        latest = value
                        observed(value, receipt)
                    }
                }
            }, { reason -> if (accepting) failure.complete(reason) })
            val failed = withTimeoutOrNull(durationMs) { failure.await() }
            accepting = false
            stopOnce() // Collection ends BEFORE the caller can compute/commit a result.
            val window = accumulator.snapshot().let {
                val covered = maxOf(elapsedNow() - started, (it.samples.lastOrNull()?.elapsedMs ?: -1) + 1)
                if (failed == null) it else it.copy(durationMs = covered.coerceIn(0, durationMs))
            }
            val value = latest ?: VisionResult(epochNow(), AnalysisStatus.FAILED,
                reasons = listOf(failed ?: "관측 시간 동안 사용할 수 있는 영상이 도착하지 않았습니다."))
            CapturedScene(if (failed == null) value else value.copy(reasons = value.reasons + failed),
                if (failed != null && window.samples.isEmpty()) null else window)
        } finally {
            accepting = false
            ticker.cancel()
            stopOnce()
            accumulator.clear()
        }
    }
}
