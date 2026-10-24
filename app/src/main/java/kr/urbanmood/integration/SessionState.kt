package kr.urbanmood.integration

import androidx.lifecycle.ViewModel
import kr.urbanmood.contracts.LocalTrack
import kr.urbanmood.contracts.RoadType

/** Configuration-change retention only; no SavedStateHandle, disk writes, logs or backups. */
class SessionState : ViewModel() {
    var kmaKey: String = ""
    var selectedPage: Int = 0
    var genres: String = ""
    var preferredMoods: Set<String> = emptySet()
    val tracks = mutableListOf<LocalTrack>()
    val recentTracks = ArrayDeque<String>()
    var manualWeather: String? = null
    var manualRoad: RoadType? = null
    var completedAnalysis: CompletedAnalysis? = null
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
