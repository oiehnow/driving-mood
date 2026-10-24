package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class AnalysisStep { PERMISSIONS, ENVIRONMENT, CAMERA, RECOMMENDING }
data class AnalysisTimeouts(val environmentMs: Long = 35_000, val cameraMs: Long = 8_000, val totalMs: Long = 60_000)
data class AnalysisSnapshot(
    val requestId: Long,
    val analyzedAtEpochMs: Long,
    val environment: EnvironmentSnapshot,
    val vision: VisionResult,
)
data class CompletedAnalysis(val snapshot: AnalysisSnapshot, val recommendation: RecommendationResult)

/** One explicit request. No background refresh or subscription after completion. */
class OneShotAnalysisRunner(
    private val environment: EnvironmentProvider,
    private val timeouts: AnalysisTimeouts = AnalysisTimeouts(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        requestId: Long,
        permissions: suspend () -> Boolean,
        capture: suspend () -> VisionResult,
        recommend: suspend (AnalysisSnapshot) -> RecommendationResult,
        progress: (AnalysisStep) -> Unit = {},
    ): CompletedAnalysis = withTimeout(timeouts.totalMs) {
        require(requestId > 0)
        try {
            progress(AnalysisStep.PERMISSIONS)
            val cameraAllowed = permissions()
            progress(AnalysisStep.ENVIRONMENT)
            environment.requestOnce(requestId)
            val terminal = withTimeoutOrNull(timeouts.environmentMs) {
                environment.requestState.first { it.requestId == requestId && it.isTerminal }
            }
            if (terminal?.phase == EnvironmentRequestPhase.CANCELLED) throw CancellationException("Environment request cancelled")
            val current = terminal ?: environment.requestState.value.takeIf { it.requestId == requestId }
            val env = current?.snapshot ?: EnvironmentSnapshot(generatedAtEpochMs = now(),
                location = LocationSnapshot(Availability.UNAVAILABLE, source = SourceInfo("위치를 확인하지 못했습니다.")),
                weather = WeatherSnapshot(Availability.UNAVAILABLE, source = SourceInfo("날씨를 확인하지 못했습니다.")))
            val envSnapshot = if (terminal == null) env.copy(notices = env.notices + "환경 확인 시간이 지나 사용 가능한 정보만 반영했습니다.")
                else env.copy(notices = env.notices + listOfNotNull(terminal.message))
            if (terminal == null) environment.cancelRequest(requestId)
            progress(AnalysisStep.CAMERA)
            val vision = if (!cameraAllowed) unavailableVision("카메라 권한이 없어 영상은 반영하지 않았습니다.")
                else try {
                    withTimeoutOrNull(timeouts.cameraMs) { capture() }
                        ?: unavailableVision("카메라 확인 시간이 지나 영상은 반영하지 않았습니다.")
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { unavailableVision("카메라를 사용할 수 없어 영상은 반영하지 않았습니다.") }
            val snapshot = AnalysisSnapshot(requestId, now(), envSnapshot, vision)
            progress(AnalysisStep.RECOMMENDING)
            CompletedAnalysis(snapshot, recommend(snapshot))
        } finally {
            val state = environment.requestState.value
            if (state.requestId != requestId || !state.isTerminal) environment.cancelRequest(requestId)
        }
    }

    private fun unavailableVision(reason: String) = VisionResult(now(), AnalysisStatus.FAILED, reasons = listOf(reason))
}
