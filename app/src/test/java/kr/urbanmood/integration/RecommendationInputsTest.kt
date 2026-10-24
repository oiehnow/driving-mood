package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import kr.urbanmood.ui.EvidencePresenter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationInputsTest {
    private val oldInputs = UserPreferences(preferredMoods = setOf("차분한"))
    private val nextInputs = UserPreferences(preferredMoods = setOf("활기 넘침"))
    private fun result(word: String) = RecommendationResult(Availability.AVAILABLE, moodKeywords = listOf(word))
    private fun state() = SessionState().apply {
        completedAnalysis = CompletedAnalysis(AnalysisSnapshot(1, 1000, EnvironmentSnapshot(),
            VisionResult(1000, AnalysisStatus.OK)), result("차분한"), oldInputs)
        preferredMoods = nextInputs.preferredMoods
    }

    @Test fun delayedUpdatePublishesInputsAndOutputOnlyTogether() = runBlocking {
        val state = state()
        val previous = state.completedAnalysis
        val gate = CompletableDeferred<RecommendationResult>()
        val mutableMoods = nextInputs.preferredMoods.toMutableSet()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            state.refreshRecommendation(nextInputs.copy(preferredMoods = mutableMoods)) { _, inputs ->
                assertEquals(nextInputs, inputs)
                gate.await()
            }
        }
        mutableMoods.clear()
        assertSame(previous, state.completedAnalysis)
        assertEquals(listOf("차분한"), EvidencePresenter.presentCompleted(state.completedAnalysis, nextInputs).desiredMoods)
        gate.complete(result("활기 넘침"))
        val completed = job.await()!!
        assertSame(previous!!.snapshot, completed.snapshot)
        assertEquals(nextInputs, completed.recommendationPreferences)
        assertEquals(listOf("활기 넘침"), completed.recommendation.moodKeywords)
        assertNull(EvidencePresenter.presentCompleted(completed, nextInputs).preferenceNotice)
    }

    @Test fun failedAndCancelledUpdatesRetainOldInputsAndMarkNewSelectionUnapplied() = runBlocking {
        val state = state()
        val previous = state.completedAnalysis
        val failure = runCatching { state.refreshRecommendation(nextInputs) { _, _ -> error("controlled failure") } }
        assertTrue(failure.isFailure)
        assertSame(previous, state.completedAnalysis)
        val gate = CompletableDeferred<RecommendationResult>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { state.refreshRecommendation(nextInputs) { _, _ -> gate.await() } }
        job.cancelAndJoin()
        gate.complete(result("활기 넘침"))
        assertSame(previous, state.completedAnalysis)
        assertNotNull(EvidencePresenter.presentCompleted(state.completedAnalysis, nextInputs).preferenceNotice)
        assertEquals(oldInputs, state.completedAnalysis!!.recommendationPreferences)
    }

    @Test fun olderCompletionCannotOverwriteANewerPreferenceResult() = runBlocking {
        val state = state()
        val gate = CompletableDeferred<RecommendationResult>()
        val old = async(start = CoroutineStart.UNDISPATCHED) { state.refreshRecommendation(oldInputs) { _, _ -> gate.await() } }
        val latest = state.refreshRecommendation(nextInputs) { _, _ -> result("활기 넘침") }
        gate.complete(result("차분한"))
        assertNull(old.await())
        assertSame(latest, state.completedAnalysis)
    }
}
