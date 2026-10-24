package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class RecordedRequestRunnerTest {
    private class Provider : EnvironmentProvider {
        override val state = MutableStateFlow(EnvironmentSnapshot())
        override val requestState = MutableStateFlow(EnvironmentRequestState())
        val calls = mutableListOf<Long>()
        var cancelled = false
        override fun requestOnce(requestId: Long) { calls += requestId }
        override fun cancelRequest(requestId: Long) { cancelled = true }
        override fun start() = error("implicit request")
        override fun refresh() = error("implicit request")
        override fun stop() = Unit
        override fun close() = Unit
    }
    private val vision = VisionResult(1_000, AnalysisStatus.OK)
    @Test fun captureStartsWhileWeatherWaitsAndBothUseSameRequest() = runBlocking {
        val provider = Provider()
        val capturing = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val task = async {
            RecordedRequestRunner(provider, now = { 999_000 }).run(4, { true }, {
                assertEquals(listOf(4L), provider.calls)
                capturing.complete(Unit); releaseCapture.await()
                CapturedScene(vision, SceneWindow(30_000), 31_000, 1_000)
            }, { snapshot ->
                assertEquals(31_000L, snapshot.observationEndEpochMs)
                assertEquals(1_000L, snapshot.captureStartedAtEpochMs)
                assertEquals(999_000L, snapshot.analyzedAtEpochMs)
                RecommendationResult(Availability.AVAILABLE)
            })
        }
        capturing.await()
        assertFalse(task.isCompleted)
        provider.requestState.value = EnvironmentRequestState(4, EnvironmentRequestPhase.COMPLETED, EnvironmentSnapshot())
        releaseCapture.complete(Unit)
        assertEquals(4L, task.await().snapshot.requestId)
    }
    @Test fun recordingFailureCannotBecomeSuccessfulPartialVideoResult() = runBlocking {
        val provider = Provider()
        var recommended = false
        try {
            RecordedRequestRunner(provider).run(1, { true }, { throw IllegalStateException("storage") }, {
                recommended = true; RecommendationResult(Availability.AVAILABLE)
            })
            fail("failure must propagate")
        } catch (_: IllegalStateException) { }
        assertFalse(recommended)
    }
    @Test fun cancellationReleasesCaptureAndDoesNotRecommend() = runBlocking {
        val provider = Provider()
        val started = CompletableDeferred<Unit>()
        var released = false
        val task = launch {
            RecordedRequestRunner(provider).run(1, { true }, {
                try { started.complete(Unit); awaitCancellation() } finally { released = true }
            }, { error("must not recommend") })
        }
        started.await(); task.cancelAndJoin()
        assertTrue(released)
    }
    @Test fun permissionDenialKeepsEnvironmentOnlyResultWithoutStartingRecorder() = runBlocking {
        val provider = Provider().apply {
            requestState.value = EnvironmentRequestState(3, EnvironmentRequestPhase.COMPLETED, EnvironmentSnapshot())
        }
        val result = RecordedRequestRunner(provider).run(3, { false }, { error("must not record") }, {
            RecommendationResult(Availability.UNAVAILABLE)
        })
        assertNull(result.snapshot.sceneWindow)
        assertNull(result.snapshot.observationEndEpochMs)
        assertEquals(AnalysisStatus.FAILED, result.snapshot.vision.status)
    }
    @Test fun completionSoundIsClaimedOnceAndOnlyForCommittedVideoResult() {
        val session = SessionState()
        assertFalse(session.claimCompletionSound(1))
        session.completedAnalysis = CompletedAnalysis(AnalysisSnapshot(1, 99, EnvironmentSnapshot(), vision),
            RecommendationResult(Availability.AVAILABLE))
        assertFalse(session.claimCompletionSound(1))
        session.completedAnalysis = session.completedAnalysis!!.copy(snapshot = session.completedAnalysis!!.snapshot.copy(sceneWindow = SceneWindow(30_000)))
        assertTrue(session.claimCompletionSound(1))
        assertFalse(session.claimCompletionSound(1))
        assertFalse(session.claimCompletionSound(2))
        assertEquals(LightingTarget.PREVIEW_ONLY, LightingPreview.presets.first().target)
    }
}
