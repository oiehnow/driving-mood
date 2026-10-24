package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class OneShotAnalysisTest {
    private val snapshot = EnvironmentSnapshot(generatedAtEpochMs = 100,
        weather = WeatherSnapshot(Availability.MANUAL, condition = "비"))
    private val vision = VisionResult(200, AnalysisStatus.LOW_CONFIDENCE)
    private val recommendation = RecommendationResult(Availability.MANUAL, listOf("차분한"))
    private class Provider : EnvironmentProvider {
        override val state = MutableStateFlow(EnvironmentSnapshot())
        override val requestState = MutableStateFlow(EnvironmentRequestState())
        val requests = mutableListOf<Long>()
        val cancellations = mutableListOf<Long>()
        var onRequest: (Long) -> Unit = {}
        override fun requestOnce(requestId: Long) { requests += requestId; onRequest(requestId) }
        override fun cancelRequest(requestId: Long) { cancellations += requestId }
        override fun start() = error("No implicit start")
        override fun refresh() = error("No implicit refresh")
        override fun stop() = Unit
        override fun close() = Unit
    }
    private fun ready() = Provider().apply {
        onRequest = { requestState.value = EnvironmentRequestState(it, EnvironmentRequestPhase.COMPLETED, snapshot) }
    }

    @Test fun environmentFinishesBeforeLatestFrameAndCompletedSnapshotIsFrozen() = runBlocking {
        val provider = Provider()
        val steps = mutableListOf<AnalysisStep>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            OneShotAnalysisRunner(provider, now = { 300 }).run(7, { true }, {
                assertEquals(EnvironmentRequestPhase.COMPLETED, provider.requestState.value.phase)
                assertTrue(provider.cancellations.isEmpty())
                vision
            }, { assertEquals(snapshot, it.environment); recommendation }, steps::add)
        }
        assertEquals(listOf(7L), provider.requests)
        assertFalse(result.isCompleted)
        provider.requestState.value = EnvironmentRequestState(6, EnvironmentRequestPhase.COMPLETED, snapshot)
        yield()
        assertFalse(result.isCompleted)
        provider.requestState.value = EnvironmentRequestState(7, EnvironmentRequestPhase.COMPLETED, snapshot)
        val completed = result.await()
        assertEquals(listOf(AnalysisStep.PERMISSIONS, AnalysisStep.ENVIRONMENT, AnalysisStep.CAMERA, AnalysisStep.RECOMMENDING), steps)
        assertEquals(300L, completed.snapshot.analyzedAtEpochMs)
        provider.state.value = EnvironmentSnapshot()
        provider.requestState.value = EnvironmentRequestState()
        provider.stop()
        assertEquals(snapshot, completed.snapshot.environment)
        assertEquals(vision, completed.snapshot.vision)
    }

    @Test fun deniedCameraStillProducesExplicitEnvironmentOnlyResult() = runBlocking {
        val result = OneShotAnalysisRunner(ready()).run(1, { false }, { error("Must not open camera") }, { recommendation })
        assertEquals(snapshot, result.snapshot.environment)
        assertEquals(AnalysisStatus.FAILED, result.snapshot.vision.status)
        assertTrue(result.snapshot.vision.reasons.single().contains("권한"))
    }

    @Test fun cameraFailureIsPartialAndDoesNotDiscardEnvironment() = runBlocking {
        val result = OneShotAnalysisRunner(ready()).run(1, { true }, { throw IllegalStateException("camera unavailable") }, { recommendation })
        assertEquals(snapshot, result.snapshot.environment)
        assertEquals(AnalysisStatus.FAILED, result.snapshot.vision.status)
        assertTrue(result.snapshot.vision.reasons.single().contains("카메라"))
    }

    @Test fun cameraTimeoutReleasesCaptureAndProducesPartialResult() = runBlocking {
        var released = false
        val result = OneShotAnalysisRunner(ready(), AnalysisTimeouts(cameraMs = 50)).run(1, { true }, {
            try { awaitCancellation() } finally { released = true }
        }, { recommendation })
        assertTrue(released)
        assertEquals(snapshot, result.snapshot.environment)
        assertTrue(result.snapshot.vision.reasons.single().contains("시간"))
    }

    @Test fun environmentTimeoutUsesOnlyMatchingPartialSnapshotAndCancelsProvider() = runBlocking {
        val provider = Provider().apply {
            onRequest = { requestState.value = EnvironmentRequestState(it, EnvironmentRequestPhase.FETCHING_WEATHER, snapshot) }
        }
        val result = OneShotAnalysisRunner(provider, AnalysisTimeouts(environmentMs = 50))
            .run(1, { true }, { vision }, { recommendation })
        assertEquals(snapshot.weather, result.snapshot.environment.weather)
        assertTrue(result.snapshot.environment.notices.any { it.contains("시간") })
        assertTrue(provider.cancellations.contains(1L))
    }

    @Test fun oldEnvironmentCannotBecomeCurrentResultOnTimeout() = runBlocking {
        val provider = Provider().apply {
            state.value = snapshot
            requestState.value = EnvironmentRequestState(1, EnvironmentRequestPhase.COMPLETED, snapshot)
        }
        val result = OneShotAnalysisRunner(provider, AnalysisTimeouts(environmentMs = 50))
            .run(2, { false }, { error("No permission") }, { recommendation })
        assertEquals(Availability.UNAVAILABLE, result.snapshot.environment.weather.availability)
        assertNull(result.snapshot.environment.weather.condition)
    }

    @Test fun permissionTimeoutThenLatePermissionNeverStartsCollection() = runBlocking {
        val provider = ready()
        val permission = CompletableDeferred<Boolean>()
        var captured = false
        var recommended = false
        val result = runCatching {
            OneShotAnalysisRunner(provider, AnalysisTimeouts(totalMs = 50)).run(1,
                { permission.await() }, { captured = true; vision }, { recommended = true; recommendation })
        }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        permission.complete(true)
        yield()
        assertTrue(provider.requests.isEmpty())
        assertFalse(captured)
        assertFalse(recommended)
        assertTrue(provider.cancellations.contains(1L))
    }

    @Test fun explicitCancelReleasesCameraAndCannotPublishNewResult() = runBlocking {
        val provider = ready()
        var released = false
        var recommended = false
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            OneShotAnalysisRunner(provider).run(1, { true }, {
                try { awaitCancellation() } finally { released = true }
            }, { recommended = true; recommendation })
        }
        task.cancelAndJoin()
        assertTrue(released)
        assertFalse(recommended)
        assertTrue(provider.cancellations.isEmpty()) // Environment already completed before camera.
    }

    @Test fun cancelledEnvironmentDoesNotOpenCameraOrRecommend() = runBlocking {
        val provider = Provider().apply {
            onRequest = { requestState.value = EnvironmentRequestState(it, EnvironmentRequestPhase.CANCELLED, snapshot) }
        }
        val result = runCatching {
            OneShotAnalysisRunner(provider).run(1, { true }, { error("Cancelled") }, { error("Cancelled") })
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun duplicateRequestAndLateCompletionCannotReplacePriorSession() {
        val session = SessionState()
        val previous = CompletedAnalysis(AnalysisSnapshot(1, 300, snapshot, vision), recommendation)
        session.completedAnalysis = previous
        val first = session.beginAnalysis()!!
        assertNull(session.beginAnalysis())
        session.endAnalysis(first)
        val next = session.beginAnalysis()!!
        session.endAnalysis(first)
        assertTrue(session.ownsRequest(next))
        assertFalse(session.ownsRequest(first))
        assertSame(previous, session.completedAnalysis)
    }
}
