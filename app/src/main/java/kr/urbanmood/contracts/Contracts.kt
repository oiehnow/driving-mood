package kr.urbanmood.contracts

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** Contract v2 (source-compatible additions). Missing values are null. */
enum class Availability { AVAILABLE, UNAVAILABLE, PERMISSION_DENIED, STALE, ERROR, MANUAL }

data class SourceInfo(
    val name: String,
    val observedAtEpochMs: Long? = null,
    val fetchedAtEpochMs: Long? = null,
    val cached: Boolean = false,
    val detail: String? = null,
)

enum class FrameInputSource { LIVE_CAMERA, ANDROID_RECORDED }

/** Valid ONLY inside analyze(); do not recycle, mutate, retain or upload.
 * Upright, unmirrored back camera, aspect preserved, longest edge <=640, ARGB_8888.
 * LIVE_CAMERA: capturedAtEpochMs is host receipt wall time; cameraTimestampNs is camera monotonic time.
 * ANDROID_RECORDED: capturedAtEpochMs is the saved host recording-start anchor plus
 * normalized media PTS; cameraTimestampNs is that normalized media time in ns (NOT sensor time).
 * mediaPresentationTimeUs preserves actual decoder PTS. Neither timestampNs is epoch time.
 * Calls are serial; live at most ~4/s, recorded one representative per media-time second.
 */
data class CameraFrame(
    val bitmap: Bitmap,
    val capturedAtEpochMs: Long,
    val cameraTimestampNs: Long,
    val sequence: Long,
    val inputSource: FrameInputSource = FrameInputSource.LIVE_CAMERA,
    val mediaPresentationTimeUs: Long? = null,
)

enum class AnalysisStatus { OK, LOW_CONFIDENCE, MODEL_NOT_READY, FAILED }
enum class AnalysisMethod { NONE, HEURISTIC, MODEL, HYBRID }

data class ColorFeatures(
    val dominantArgb: Int? = null,
    val hueDegrees: Float? = null,
    val saturation: Float? = null, // [0,1]
    val brightness: Float? = null, // [0,1]
    val luminousAreaRatio: Float? = null, // [0,1] of full input frame
)

/** Normalized [0,1] bounds in upright input bitmap, not screen coordinates. */
data class DebugRegion(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String, val excluded: Boolean,
)

data class VisionResult(
    val frameTimeEpochMs: Long,
    val status: AnalysisStatus,
    val urbanLightScore: Float? = null, // [0,1], lighting indicator, NOT business activity
    val colors: ColorFeatures? = null,
    val vehicleExcludedRatio: Float? = null, // masked vehicle/light area / full input area
    val confidence: Float? = null, // Legacy overall indicator; never substitute for v2 evidence below.
    val method: AnalysisMethod = AnalysisMethod.NONE,
    val reasons: List<String> = emptyList(),
    val debugRegions: List<DebugRegion> = emptyList(),
    val processingTimeMs: Long? = null,
    // [0,1], finite, independently evaluated by B AFTER vehicle/light exclusion.
    // Quality indicators, NOT calibrated probabilities or semantic recognition scores.
    val colorMeasurementConfidence: Float? = null,
    val lightMeasurementConfidence: Float? = null,
    // [0,1], finite; null = not evaluated/unsupported. Requires reasons describing
    // actual semantic labels/evidence. Even 1 does NOT establish business activity.
    val semanticConfidence: Float? = null,
    // True only after a successful vehicle/light exclusion pass, including zero detections.
    // Does not guarantee all vehicles/reflections were removed. Failed/unrun pass = false.
    val vehicleMaskApplied: Boolean = false,
    val sceneObservations: SceneObservations? = null,
)

interface VisionAnalyzer : AutoCloseable {
    /** Synchronous. All work and model loading run off main. No nested queued frames. */
    fun analyze(frame: CameraFrame): VisionResult
    /** Called on the SAME serial worker after in-flight analyze returns. */
    override fun close()
}

data class LocationSnapshot(
    val availability: Availability = Availability.UNAVAILABLE,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val bearingDegrees: Float? = null, // travel bearing; null if unavailable, not device compass
    val source: SourceInfo = SourceInfo("위치 미연결"),
)
enum class RoadType { HIGHWAY, URBAN, RESIDENTIAL, OTHER, UNKNOWN }
data class RoadContext(
    val availability: Availability = Availability.UNAVAILABLE,
    val type: RoadType = RoadType.UNKNOWN,
    val placeName: String? = null,
    val source: SourceInfo = SourceInfo("도로 데이터 미연결"),
)
data class WeatherSnapshot(
    val availability: Availability = Availability.UNAVAILABLE,
    val temperatureC: Double? = null,
    val precipitationMm: Double? = null,
    val condition: String? = null,
    val source: SourceInfo = SourceInfo("날씨 미연결"),
)
data class EnvironmentSnapshot(
    val generatedAtEpochMs: Long = System.currentTimeMillis(),
    val zoneId: String = java.time.ZoneId.systemDefault().id,
    val location: LocationSnapshot = LocationSnapshot(),
    val road: RoadContext = RoadContext(),
    val weather: WeatherSnapshot = WeatherSnapshot(),
    val notices: List<String> = emptyList(),
)
enum class EnvironmentRequestPhase { IDLE, LOCATING, FETCHING_WEATHER, COMPLETED, CANCELLED, FAILED, TIMED_OUT }

/** Terminal means acquisition ended, not that every observation is available.
 * Terminal snapshots belong to this request and remain unchanged during resource cleanup.
 */
data class EnvironmentRequestState(
    val requestId: Long = 0,
    val phase: EnvironmentRequestPhase = EnvironmentRequestPhase.IDLE,
    val snapshot: EnvironmentSnapshot? = null,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val message: String? = null,
) {
    val isTerminal: Boolean get() = phase in setOf(EnvironmentRequestPhase.COMPLETED,
        EnvironmentRequestPhase.CANCELLED, EnvironmentRequestPhase.FAILED, EnvironmentRequestPhase.TIMED_OUT)
}

interface EnvironmentProvider : AutoCloseable {
    val state: StateFlow<EnvironmentSnapshot>
    val requestState: StateFlow<EnvironmentRequestState>
    /** Main thread; positive unique ID. Concurrent/duplicate requests must not start new IO.
     * Location wait <=20s, HTTP <=12s; release listeners/timers/HTTP before terminal publication.
     * Missing permissions/key are explicit partial results. Only this command starts collection.
     */
    fun requestOnce(requestId: Long)
    /** Cancel only the matching active ID. No-op for already terminal requests. */
    fun cancelRequest(requestId: Long)
    /** Legacy lifecycle hooks. start/refresh do not collect; use requestOnce explicitly.
     * Main thread, non-blocking. stop cancels active work but preserves completed snapshots.
     * Missing/revoked access must remain safe. No background service or automatic restart. */
    fun start()
    fun refresh()
    fun stop()
    override fun close()
}

data class LocalTrack(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val tags: Set<String> = emptySet(),
    val rightsNote: String = "사용자가 선택한 로컬 음원. 공개 재생·공유 권리는 별도 확인 필요",
)
data class UserPreferences(
    val preferredGenres: Set<String> = emptySet(),
    val excludedTrackIds: Set<String> = emptySet(),
    /** User-selected desired atmosphere, never an observed emotional state. */
    val preferredMoods: Set<String> = emptySet(),
)

/** Shared UI/recommendation vocabulary; no parallel mood enum or arbitrary input aliases. */
object MoodVocabulary {
    val options: List<String> = listOf("따뜻한", "몽환적인", "차분한", "잔잔한", "편안한", "포근한", "청량한", "차가운", "우울", "활기 넘침")
}
data class RecommendationRequest(
    val vision: VisionResult?,
    val environment: EnvironmentSnapshot,
    val preferences: UserPreferences = UserPreferences(),
    val catalog: List<LocalTrack> = emptyList(),
    val recentTrackIds: List<String> = emptyList(),
    val nowEpochMs: Long = System.currentTimeMillis(),
    val sceneWindow: SceneWindow? = null,
    // False for recorded inputs with unknown wall-clock capture time. nowEpochMs may
    // still be an evaluation clock, but must not generate time-of-day mood evidence.
    val timeContextAvailable: Boolean = true,
    // Recorded inputs are evaluated against their capture end, not later decoding wall time.
    // Supplied only with this request's known capture anchor. Null preserves legacy behavior.
    val observationEndEpochMs: Long? = null,
)
data class RecommendationResult(
    val availability: Availability,
    val moodKeywords: List<String> = emptyList(),
    val youtubeQuery: String? = null, // search only; never a playable audio URL
    val candidates: List<LocalTrack> = emptyList(), // randomized preferred order, playable local only
    val reasons: List<String> = emptyList(),
    val confidence: Float? = null,
    val scoreSummary: MoodScoreSummary? = null,
)
interface RecommendationEngine {
    /** Serial background call. Pure local computation; no network/Bitmap access.
     * Combines road and visual evidence; GPS alone does not determine road class.
     * Never force a current track change on camera updates.
     */
    fun recommend(request: RecommendationRequest): RecommendationResult
}

enum class PlaybackStatus { UNAVAILABLE, IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.UNAVAILABLE,
    val currentTrack: LocalTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val error: String? = null,
)
interface AudioPlayer : AutoCloseable {
    val state: StateFlow<PlaybackState>
    /** All commands main thread, non-blocking. New queue must not interrupt current track.
     * next() / natural completion choose upcoming queue. The player handles audio focus and output changes.
     */
    fun setQueue(tracks: List<LocalTrack>)
    fun play()
    fun pause()
    fun next()
    override fun close()
}
