package kr.urbanmood.integration

import androidx.lifecycle.ViewModel
import kr.urbanmood.contracts.LocalTrack
import kr.urbanmood.contracts.RoadType
import kr.urbanmood.contracts.UserPreferences
import kr.urbanmood.contracts.RecommendationResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Configuration-change retention only; no SavedStateHandle, disk writes, logs or backups. */
class SessionState : ViewModel() {
    var kmaKey: String = ""
    var selectedPage: Int = 0
    var hasEnteredAnalysis: Boolean = false
    var genres: String = ""
    var preferredMoods: Set<String> = emptySet()
    val tracks = mutableListOf<LocalTrack>()
    val recentTracks = ArrayDeque<String>()
    var manualWeather: String? = null
    var manualRoad: RoadType? = null
    var completedAnalysis: CompletedAnalysis? = null
    private var recommendationRevision = 0L

    /** Publish the output and its captured preferences together, only after successful completion. */
    suspend fun refreshRecommendation(preferences: UserPreferences,
        recommend: suspend (AnalysisSnapshot, UserPreferences) -> RecommendationResult): CompletedAnalysis? {
        val previous = completedAnalysis ?: return null
        if (activeRequestId != null) return null
        val revision = ++recommendationRevision
        val inputs = preferences.copy(preferredGenres = preferences.preferredGenres.toSet(),
            preferredMoods = preferences.preferredMoods.toSet(), excludedTrackIds = preferences.excludedTrackIds.toSet())
        val result = recommend(previous.snapshot, inputs)
        currentCoroutineContext().ensureActive()
        if (revision != recommendationRevision || activeRequestId != null ||
            completedAnalysis?.snapshot !== previous.snapshot) return null
        return previous.copy(recommendation = result, recommendationPreferences = inputs).also { completedAnalysis = it }
    }
    var recordingDurationMs: Long = 30_000
    var lightingPreview: kr.urbanmood.contracts.LightingPreviewOutput? = null
    private var lastNotifiedId: Long? = null
    fun claimCompletionSound(id: Long): Boolean {
        if (completedAnalysis?.snapshot?.requestId != id || completedAnalysis?.snapshot?.sceneWindow == null || lastNotifiedId == id) return false
        lastNotifiedId = id
        return true
    }
    var activeRequestId: Long? = null
    private var nextRequestId = 0L
    fun beginAnalysis(): Long? {
        if (activeRequestId != null) return null
        return (++nextRequestId).also { activeRequestId = it }
    }
    fun ownsRequest(id: Long) = activeRequestId == id
    fun endAnalysis(id: Long) { if (ownsRequest(id)) activeRequestId = null }
    override fun onCleared() {
        kmaKey = ""; tracks.clear(); recentTracks.clear()
        completedAnalysis = null; activeRequestId = null
        preferredMoods = emptySet()
    }
}
