package kr.urbanmood.integration

import kr.urbanmood.camera.FrameReceipt
import kr.urbanmood.contracts.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TimedSceneCaptureTest {
    private fun vision(epoch: Long = 0) = VisionResult(epoch, AnalysisStatus.LOW_CONFIDENCE)
    private fun receipt(time: Long) = FrameReceipt(1, 640, 480, 5, time)

    @Test fun deadlineRejectsLateAndPreWindowFramesStopsBeforeReturningAndDoesNotDelayCalculation() = runBlocking {
        var clock = 1_000L
        var stopCount = 0
        var observed = 0
        lateinit var receive: (VisionResult, FrameReceipt) -> Unit
        val result = TimedSceneCapture(40, { clock }).capture(start = { frame, _ ->
            receive = frame
            frame(vision(), receipt(999)) // Previous request timestamp.
            frame(vision(), receipt(1_000))
            clock = 1_040
            frame(vision(), receipt(1_039)) // Arrived after the window even though captured inside it.
            frame(vision(), receipt(1_040))
        }, stop = { stopCount++ }, observed = { _, _ -> observed++ })
        assertEquals(1, stopCount)
        assertEquals(1, observed)
        assertEquals(1, result.window!!.receivedFrameCount)
        assertEquals(listOf(0L), result.window.samples.map { it.elapsedMs })
        receive(vision(), receipt(1_001)) // Callback retained by a stale source cannot mutate snapshot.
        assertEquals(1, result.window.receivedFrameCount)
    }

    @Test fun cancellationStopsAndDiscardsPartialAccumulatorThenNextRequestStartsEmpty() = runBlocking {
        var stops = 0
        lateinit var receive: (VisionResult, FrameReceipt) -> Unit
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            TimedSceneCapture(40, { 10 }).capture({ frame, _ ->
                receive = frame; frame(vision(), receipt(10))
            }, { stops++ })
        }
        task.cancelAndJoin()
        assertEquals(1, stops)
        receive(vision(), receipt(11))
        val next = TimedSceneCapture(10, { 20 }).capture({ _, _ -> }, {})
        assertEquals(0, next.window!!.receivedFrameCount)
        assertTrue(next.window.samples.isEmpty())
        assertEquals(AnalysisStatus.FAILED, next.vision.status)
    }

    @Test fun cameraStartFailureStopsImmediatelyAndHasNoInventedObservationWindow() = runBlocking {
        var stops = 0
        val result = TimedSceneCapture(40, { 100 }).capture({ _, fail -> fail("카메라 연결 실패") }, { stops++ })
        assertNull(result.window)
        assertEquals(AnalysisStatus.FAILED, result.vision.status)
        assertTrue(result.vision.reasons.any { it.contains("연결 실패") })
        assertEquals(1, stops)
    }

    @Test fun windowTravelsThroughRunnerAndPermissionDenialNeverReusesIt() = runBlocking {
        val provider = object : EnvironmentProvider {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(EnvironmentSnapshot())
            override val requestState = kotlinx.coroutines.flow.MutableStateFlow(EnvironmentRequestState())
            var calls = 0
            override fun requestOnce(requestId: Long) { calls++; requestState.value = EnvironmentRequestState(requestId,
                EnvironmentRequestPhase.COMPLETED, state.value) }
            override fun cancelRequest(requestId: Long) = Unit
            override fun start() = Unit
            override fun stop() = Unit
            override fun refresh() = Unit
            override fun close() = Unit
        }
        val window = SceneWindow(50_000, samples = listOf(SceneSample(0, vision())))
        val runner = OneShotAnalysisRunner(provider)
        val done = runner.runWindow(1, { true }, { CapturedScene(vision(), window) }, {
            assertSame(window, it.sceneWindow); RecommendationResult(Availability.AVAILABLE)
        })
        assertSame(window, done.snapshot.sceneWindow)
        runner.runWindow(2, { false }, { error("Must not capture") }, {
            assertNull(it.sceneWindow); RecommendationResult(Availability.UNAVAILABLE)
        })
        assertEquals(2, provider.calls)
    }
}
