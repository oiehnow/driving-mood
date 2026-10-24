package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

data class RecordedRequestTimeouts(val permissionMs: Long = 110_000, val environmentMs: Long = 35_000,
    val totalMs: Long = 420_000)

/** Weather and video belong to one request; networking never delays the recording start. */
class RecordedRequestRunner(private val environment: EnvironmentProvider,
    private val timeouts: RecordedRequestTimeouts = RecordedRequestTimeouts(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(id: Long, permissions: suspend () -> Boolean, capture: suspend () -> CapturedScene,
        recommend: suspend (AnalysisSnapshot) -> RecommendationResult,
        recommending: () -> Unit = {},
    ): CompletedAnalysis = withTimeout(timeouts.totalMs) {
        require(id > 0)
        try {
            val allowed = withTimeout(timeouts.permissionMs) { permissions() }
            coroutineScope {
                environment.requestOnce(id)
                val environmental = async(start = CoroutineStart.UNDISPATCHED) {
                    val terminal = withTimeoutOrNull(timeouts.environmentMs) {
                        environment.requestState.first { it.requestId == id && it.isTerminal }
                    }
                    if (terminal?.phase == EnvironmentRequestPhase.CANCELLED) throw CancellationException()
                    val matched = terminal ?: environment.requestState.value.takeIf { it.requestId == id }
                    if (terminal == null) environment.cancelRequest(id)
                    val result = matched?.snapshot ?: EnvironmentSnapshot(generatedAtEpochMs = now(),
                        location = LocationSnapshot(Availability.UNAVAILABLE), weather = WeatherSnapshot(Availability.UNAVAILABLE))
                    result.copy(notices = result.notices + if (terminal == null) listOf("환경 확인 시간이 지나 사용 가능한 정보만 반영했습니다.")
                        else listOfNotNull(terminal.message))
                }
                // Recording/decoding failures are failures, not a fake successful video result.
                val captured = if (allowed) capture() else CapturedScene(VisionResult(now(), AnalysisStatus.FAILED,
                    reasons = listOf("카메라 권한이 없어 영상은 반영하지 않았습니다.")))
                val envSnapshot = environmental.await()
                val snapshot = AnalysisSnapshot(id, now(), envSnapshot, captured.vision, captured.window,
                    captured.observationEndEpochMs, captured.captureStartedAtEpochMs)
                recommending()
                CompletedAnalysis(snapshot, recommend(snapshot))
            }
        } finally {
            val state = environment.requestState.value
            if (state.requestId == id && !state.isTerminal) environment.cancelRequest(id)
        }
    }
}
