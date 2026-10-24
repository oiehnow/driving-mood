package kr.urbanmood

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.graphics.Bitmap
import kr.urbanmood.camera.VideoCaptureController
import kr.urbanmood.camera.FrameReceipt
import kr.urbanmood.camera.EvidenceOverlayView
import kr.urbanmood.contracts.*
import kr.urbanmood.integration.AppServices
import kr.urbanmood.integration.SessionState
import kr.urbanmood.integration.recommendationText
import kr.urbanmood.integration.roadLabel
import kr.urbanmood.integration.AnalysisSnapshot
import kr.urbanmood.integration.CompletedAnalysis
import kr.urbanmood.integration.AppTypography
import kr.urbanmood.integration.CapturedScene
import kr.urbanmood.integration.RecordedSceneAnalyzer
import kr.urbanmood.integration.RecordedRequestRunner
import kr.urbanmood.integration.CompletionCue
import kr.urbanmood.integration.LightingPreview
import kotlinx.coroutines.withTimeout
import kr.urbanmood.recommendation.SceneMoodScorer
import kr.urbanmood.context.AndroidEnvironmentProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val typography by lazy { AppTypography(this) }
    private lateinit var session: SessionState
    private lateinit var services: AppServices
    private lateinit var camera: VideoCaptureController
    private lateinit var recordedAnalyzer: RecordedSceneAnalyzer
    private lateinit var completionCue: CompletionCue
    private lateinit var decodedPreview: ImageView
    private var displayedBitmap: Bitmap? = null
    private lateinit var durationButton: Button
    private lateinit var lightingText: TextView
    private lateinit var lightingSwatch: View
    private lateinit var preview: PreviewView
    private lateinit var previewContainer: FrameLayout
    private lateinit var evidenceOverlay: EvidenceOverlayView
    private lateinit var scoreText: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var frameStatus: TextView
    private lateinit var analysisText: TextView
    private lateinit var environmentText: TextView
    private lateinit var moodText: TextView
    private lateinit var playerText: TextView
    private lateinit var catalogText: TextView
    private lateinit var searchButton: Button
    private lateinit var preferenceInput: EditText
    private lateinit var desiredMoodText: TextView
    private lateinit var analyzeButton: Button
    private lateinit var cancelButton: Button
    private val configurationButtons = mutableListOf<Button>()
    private var latestVision: VisionResult? = null
    private var environment = EnvironmentSnapshot()
    private var recommendation: RecommendationResult? = null
    private val tracks get() = session.tracks
    private val recentTracks get() = session.recentTracks
    private var analysisJob: Job? = null
    private var recommendationJob: Job? = null
    private var permissionInFlight = false
    private var permissionRequestId: Long? = null
    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private var cameraRequestId: Long? = null
    private var foreground = false
    private val mint = Color.rgb(157, 228, 203)
    private val muted = Color.rgb(171, 190, 200)

    private val analysisPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val continuation = permissionContinuation
        val id = permissionRequestId
        permissionContinuation = null
        permissionRequestId = null
        permissionInFlight = false
        if (id != null && session.ownsRequest(id) && continuation?.isActive == true) {
            continuation.resume(hasPermission(Manifest.permission.CAMERA))
        }
        updateControls()
    }
    private val musicPicker = registerForActivityResult(object : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uris ->
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try {
                        // SAF providers may not support persistable grants. Session access still works.
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        val title = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "선택한 음원"
                        LocalTrack(uri.toString(), uri, title)
                    } catch (_: Exception) { null }
                }
            }
            tracks.addAll(selected.filter { candidate -> tracks.none { it.id == candidate.id } })
            if (selected.size < uris.size) Toast.makeText(this@MainActivity, "일부 파일을 읽지 못했습니다. 기기 내 음원 파일을 다시 선택하세요.", Toast.LENGTH_LONG).show()
            catalogText.text = "선택한 음원 ${tracks.size}곡"
            requestRecommendation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[SessionState::class.java]
        services = AppServices(applicationContext) { session.kmaKey }
        buildScreen()
        camera = VideoCaptureController(this, this, preview)
        recordedAnalyzer = RecordedSceneAnalyzer(services.vision)
        completionCue = CompletionCue(this)
        session.completedAnalysis?.let {
            renderCompleted(it)
            services.audio.setQueue(it.recommendation.candidates)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    services.audio.state.collect {
                        playerText.text = "${playbackLabel(it.status)} · ${it.currentTrack?.title ?: "선택된 곡 없음"}" +
                            "\n${duration(it.positionMs)} / ${it.durationMs?.let(::duration) ?: "—"}" +
                            (it.error?.let { message -> "\n$message" } ?: "")
                        val id = it.currentTrack?.id
                        if (it.status == PlaybackStatus.PLAYING && id != null && recentTracks.lastOrNull() != id) {
                            recentTracks.addLast(id)
                            while (recentTracks.size > 20) recentTracks.removeFirst()
                            requestRecommendation()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        updateControls()
    }

    override fun onPause() {
        foreground = false
        if (!permissionInFlight) cancelAnalysis("분석을 취소했습니다. 다시 누르면 새로 분석합니다.")
        session.genres = preferenceInput.text.toString()
        services.audio.pause()
        super.onPause()
    }

    override fun onStop() {
        cancelAnalysis("화면을 벗어나 분석을 취소했습니다. 이전 결과는 유지됩니다.")
        super.onStop()
    }

    override fun onDestroy() {
        cancelAnalysis("분석을 취소했습니다.")
        recommendationJob?.cancel()
        camera.close()
        recordedAnalyzer.close()
        completionCue.close()
        clearDecodedPreview()
        services.environment.close()
        services.audio.close()
        typography.close()
        super.onDestroy()
    }

    private fun hasPermission(value: String) = ContextCompat.checkSelfPermission(this, value) == PackageManager.PERMISSION_GRANTED

    private fun startAnalysis() {
        if (!foreground || permissionInFlight) return
        val id = session.beginAnalysis() ?: return
        recommendationJob?.cancel()
        scoreText.text = "촬영을 마치면 주변 무드를 분석합니다."
        clearDecodedPreview()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateControls()
        analysisJob = lifecycleScope.launch {
            try {
                services.environment.setManualWeather(condition = session.manualWeather)
                services.environment.setManualRoad(session.manualRoad)
                cameraStatus.text = "권한 확인 중"
                val result = RecordedRequestRunner(services.environment).run(id,
                    permissions = { requestPermissionsForAnalysis(id) },
                    capture = { captureScene(id) },
                    recommend = { recommendSnapshot(it) },
                    recommending = { if (session.ownsRequest(id)) cameraStatus.text = "주변 무드와 음악 정리 중" })
                if (session.ownsRequest(id)) {
                    session.completedAnalysis = result
                    session.lightingPreview = LightingPreview.suggestion(result)
                    renderCompleted(result)
                    services.audio.setQueue(result.recommendation.candidates)
                    if (foreground && session.claimCompletionSound(id)) completionCue.play()
                }
            } catch (_: TimeoutCancellationException) {
                if (session.ownsRequest(id)) showInterrupted("시간이 초과되어 분석을 취소했습니다. 이전 결과는 유지됩니다.")
            } catch (_: CancellationException) {
                if (session.ownsRequest(id)) showInterrupted("분석을 취소했습니다. 이전 결과는 유지됩니다.")
            } catch (_: Exception) {
                if (session.ownsRequest(id)) showInterrupted("분석을 마치지 못했습니다. 이전 결과는 유지됩니다. 다시 시도해 주세요.")
            } finally {
                releaseCamera(id)
                services.environment.cancelRequest(id)
                session.endAnalysis(id)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                session.completedAnalysis?.let {
                    if (session.activeRequestId == null) scoreText.text = kr.urbanmood.integration.scoreSummaryText(it.recommendation.scoreSummary)
                }
                updateControls()
            }
        }
    }

    private suspend fun requestPermissionsForAnalysis(id: Long): Boolean {
        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) missing += Manifest.permission.CAMERA
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (missing.isEmpty()) return true
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation = continuation
            permissionRequestId = id
            permissionInFlight = true
            continuation.invokeOnCancellation {
                if (permissionContinuation === continuation) permissionContinuation = null
                // Keep the pending-system-dialog guard until its callback; never restart IO there.
            }
            analysisPermissions.launch(missing.toTypedArray())
        }
    }

    private suspend fun captureScene(id: Long): CapturedScene {
        cameraRequestId = id
        val duration = session.recordingDurationMs
        previewContainer.visibility = View.VISIBLE
        preview.visibility = View.VISIBLE
        decodedPreview.visibility = View.GONE
        cameraStatus.text = "카메라 연결 중"
        val clip = camera.record(duration,
            progress = { elapsed -> if (session.ownsRequest(id)) {
                cameraStatus.text = "촬영 중"
                frameStatus.text = "${elapsed / 1000} / ${duration / 1000}초"
            } },
            finalizing = { if (session.ownsRequest(id)) cameraStatus.text = "영상 저장 마무리 중" })
        try {
            if (!session.ownsRequest(id)) throw CancellationException()
            preview.visibility = View.GONE
            decodedPreview.visibility = View.VISIBLE
            cameraStatus.text = "분석 중"
            frameStatus.text = "촬영한 영상의 장면을 확인하고 있습니다."
            return withTimeout(180_000) {
                recordedAnalyzer.analyze(clip) { value, bitmap, elapsed, samples ->
                    val env = services.environment.requestState.value.takeIf { it.requestId == id }?.snapshot
                    val score = SceneMoodScorer().score(SceneWindow(1_000, receivedFrameCount = 1,
                        samples = listOf(SceneSample(0, value))), env, value.frameTimeEpochMs)
                    withContext(Dispatchers.Main) {
                        if (session.ownsRequest(id)) {
                            val owned = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            decodedPreview.setImageBitmap(owned)
                            displayedBitmap?.recycle()
                            displayedBitmap = owned
                            frameStatus.text = "분석 ${elapsed / 1000 + 1} / ${(clip.timing.durationMs + 999) / 1000}구간 · 영상 프레임 ${samples}개"
                            renderVision(value)
                            evidenceOverlay.show(value, FrameReceipt(samples.toLong(), bitmap.width, bitmap.height,
                                value.processingTimeMs ?: 0, SystemClock.elapsedRealtime()), score)
                        }
                    }
                }
            }
        } finally { clip.close() }
    }

    private fun clearDecodedPreview() {
        if (::decodedPreview.isInitialized) decodedPreview.setImageDrawable(null)
        displayedBitmap?.recycle()
        displayedBitmap = null
    }

    private fun releaseCamera(id: Long) {
        if (cameraRequestId != id) return
        cameraRequestId = null
        camera.cancel()
        evidenceOverlay.clear()
        previewContainer.visibility = View.GONE
    }

    private fun cancelAnalysis(message: String) {
        val id = session.activeRequestId ?: return
        session.endAnalysis(id)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        analysisJob?.cancel()
        services.environment.cancelRequest(id)
        releaseCamera(id)
        showInterrupted(message)
        updateControls()
    }

    private fun showInterrupted(message: String) {
        val previous = session.completedAnalysis
        if (previous != null) renderCompleted(previous) else {
            analysisText.text = "관측을 완료하지 못했습니다. 다시 눌러 새로 관측할 수 있습니다."
            frameStatus.text = "미완료 관측을 지웠습니다."
            scoreText.text = kr.urbanmood.integration.scoreSummaryText(null)
        }
        cameraStatus.text = message
    }

    private fun updateControls() {
        if (!::analyzeButton.isInitialized) return
        val busy = session.activeRequestId != null
        analyzeButton.isEnabled = !busy && !permissionInFlight
        analyzeButton.text = if (busy) "분석 중…" else "무드 분석"
        cancelButton.isEnabled = busy
        configurationButtons.forEach { it.isEnabled = !busy }
    }

    private suspend fun recommendSnapshot(snapshot: AnalysisSnapshot): RecommendationResult {
        val request = RecommendationRequest(snapshot.vision, snapshot.environment,
            UserPreferences(preferenceInput.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                preferredMoods = session.preferredMoods.toSet()),
            tracks.toList(), recentTracks.toList(), nowEpochMs = snapshot.analyzedAtEpochMs,
            sceneWindow = snapshot.sceneWindow, observationEndEpochMs = snapshot.observationEndEpochMs)
        return withContext(Dispatchers.Default) { services.recommendation.recommend(request) }
    }

    private fun requestRecommendation() {
        val completed = session.completedAnalysis ?: return
        if (session.activeRequestId != null) return
        recommendationJob?.cancel()
        recommendationJob = lifecycleScope.launch {
            try {
                val result = recommendSnapshot(completed.snapshot)
                if (session.completedAnalysis?.snapshot === completed.snapshot && session.activeRequestId == null) {
                    val updated = completed.copy(recommendation = result)
                    session.completedAnalysis = updated
                    renderRecommendation(result)
                    services.audio.setQueue(result.candidates)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@MainActivity, "음악 추천을 갱신하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun renderRecommendation(value: RecommendationResult) {
        recommendation = value
        moodText.text = recommendationText(value)
        scoreText.text = kr.urbanmood.integration.scoreSummaryText(value.scoreSummary)
        searchButton.isEnabled = !value.youtubeQuery.isNullOrBlank()
    }

    private fun renderLighting() {
        if (!::lightingText.isInitialized) return
        val value = session.lightingPreview
        lightingText.text = value?.let { "${it.name} · 밝기 ${(it.brightness * 100).toInt()}% · ${it.reason}" }
            ?: "프리셋을 선택하세요."
        val color = value?.colorArgb ?: Color.DKGRAY
        val level = value?.brightness ?: 1f
        lightingSwatch.setBackgroundColor(Color.rgb((Color.red(color) * level).toInt(),
            (Color.green(color) * level).toInt(), (Color.blue(color) * level).toInt()))
    }

    private fun renderCompleted(value: CompletedAnalysis) {
        latestVision = value.snapshot.vision
        environment = value.snapshot.environment
        cameraStatus.text = "분석 완료 · ${time(value.snapshot.analyzedAtEpochMs)}"
        frameStatus.text = value.snapshot.sceneWindow?.let {
            "${"%.1f".format(java.util.Locale.KOREA, it.durationMs / 1000.0)}초 영상 · ${it.samples.size}개 장면" +
                (value.snapshot.observationEndEpochMs?.let { end -> " · 촬영 ${time(end)}" } ?: "")
        } ?: "환경 정보 분석 완료"
        renderVision(value.snapshot.vision)
        renderEnvironment(value.snapshot.environment)
        renderRecommendation(value.recommendation)
        renderLighting()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 27, 35)); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(28)) }
        scroll.addView(root)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(text(getString(R.string.app_name), 11, mint))
        root.addView(text("도시의 빛을, 음악으로", 27, Color.WHITE, true))
        root.addView(text("주변의 색감과 날씨, 나의 취향에 어울리는 음악을 만나보세요.", 13, muted))

        val captureCard = card(root, "01   지금의 무드 분석")
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "후면 카메라 실시간 미리보기"
        }
        evidenceOverlay = EvidenceOverlayView(this)
        decodedPreview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        previewContainer = FrameLayout(this).apply {
            visibility = View.GONE
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(decodedPreview, FrameLayout.LayoutParams(-1, -1))
            addView(evidenceOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        captureCard.addView(previewContainer, LinearLayout.LayoutParams(-1, dp(260)))
        cameraStatus = text("짧게 촬영하고 주변 무드를 확인하세요.", 14, mint)
        frameStatus = text("촬영 → 분석 → 무드와 음악", 12, muted)
        captureCard.addView(cameraStatus)
        captureCard.addView(frameStatus)
        durationButton = addButton(captureCard, "촬영 ${session.recordingDurationMs / 1000}초") {
            AlertDialog.Builder(this).setTitle("촬영 길이")
                .setSingleChoiceItems(arrayOf("30초", "60초"), if (session.recordingDurationMs == 30_000L) 0 else 1) { dialog, selected ->
                    session.recordingDurationMs = if (selected == 0) 30_000 else 60_000
                    durationButton.text = "촬영 ${session.recordingDurationMs / 1000}초"
                    dialog.dismiss()
                }.setNegativeButton("취소", null).showWithAppFont()
        }
        configurationButtons += durationButton
        val cameraButtons = row(captureCard)
        analyzeButton = addButton(cameraButtons, "무드 분석") { startAnalysis() }
        cancelButton = addButton(cameraButtons, "취소", false) { cancelAnalysis("분석을 취소했습니다. 이전 결과는 유지됩니다.") }
        addButton(captureCard, "권한 · 앱 설정") {
            openExternal(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        captureCard.addView(text("영상은 기기에 임시 저장 후 삭제합니다. 운전 중에는 조작하지 마세요.", 12, muted))

        val analysisCard = card(root, "02   도시 조명 분석")
        analysisText = text("무드 분석을 누르면 주변 색감과 빛을 확인합니다.", 15, Color.WHITE)
        analysisCard.addView(analysisText)
        addButton(analysisCard, "분석 상세") {
            showDetails("분석 상세", latestVision?.let { value ->
                "관측 ${time(value.frameTimeEpochMs)}\n" + value.reasons.joinToString("\n")
            } ?: "무드 분석을 마치면 내용을 확인할 수 있습니다.")
        }

        val environmentCard = card(root, "03   위치 · 도로 · 날씨")
        environmentText = text("위치 미연결\n도로 알 수 없음 · 날씨 미연결", 14, Color.WHITE)
        environmentCard.addView(environmentText)
        environmentCard.addView(text(AndroidEnvironmentProvider.PRIVACY + "\n날씨를 연결하려면 인증키가 필요합니다. 도로는 자동으로 확인하지 않으며 직접 설정할 수 있습니다.", 12, muted))
        configurationButtons += addButton(environmentCard, "기상청 키 설정") { showKeySettings() }
        configurationButtons += addButton(environmentCard, "수동 설정") { showManualSettings() }

        val moodCard = card(root, "04   나에게 맞는 분위기")
        preferenceInput = EditText(this).apply {
            hint = "선호 장르 (예: jazz, ambient)"
            setTextColor(Color.WHITE); setHintTextColor(muted); textSize = 14f
            maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT
            contentDescription = "쉼표로 구분한 선호 장르"
            setText(session.genres)
        }
        moodCard.addView(preferenceInput)
        addButton(moodCard, "취향 적용") { requestRecommendation() }
        desiredMoodText = text("", 13, muted)
        moodCard.addView(desiredMoodText)
        renderDesiredMoods()
        configurationButtons += addButton(moodCard, "원하는 분위기 선택") { showDesiredMoods() }
        moodText = text("무드 분석을 마치면 키워드와 음악 추천을 확인할 수 있습니다.", 14, Color.WHITE)
        moodCard.addView(moodText)
        scoreText = text("관측을 마치면 분위기별 점수와 기여 항목을 확인할 수 있습니다.", 13, muted)
        moodCard.addView(scoreText)
        addButton(moodCard, "점수와 관측 근거") {
            showDetails("점수와 관측 근거", kr.urbanmood.integration.scoreDetailsText(recommendation?.scoreSummary))
        }
        addButton(moodCard, "추천 이유") {
            showDetails("추천 이유", recommendation?.reasons?.filterNot { it.startsWith("영상 근거:") }
                ?.joinToString("\n") ?: "사용 가능한 환경과 취향을 바탕으로 추천을 준비하고 있습니다.")
        }
        searchButton = addButton(moodCard, "YouTube에서 검색 열기", false) {
            recommendation?.youtubeQuery?.takeIf { it.isNotBlank() }?.let { query ->
                openExternal(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results").buildUpon()
                    .appendQueryParameter("search_query", query).build()))
            }
        }

        val audioCard = card(root, "05   내 음악")
        catalogText = text("선택한 음원 ${tracks.size}곡", 13, muted)
        playerText = text("재생 대기 · 기기 내 음원을 선택하세요.", 15, Color.WHITE)
        audioCard.addView(catalogText); audioCard.addView(playerText)
        addButton(audioCard, "로컬 음원 선택", services.audioAvailable) { musicPicker.launch(arrayOf("audio/*")) }
        addButton(audioCard, "곡별 장르·분위기 태그") { showTrackTags() }
        val audioButtons = row(audioCard)
        addButton(audioButtons, "재생", services.audioAvailable) {
            if (session.completedAnalysis == null) Toast.makeText(this, "무드 분석 후 음악을 재생할 수 있습니다.", Toast.LENGTH_SHORT).show()
            else services.audio.play()
        }
        addButton(audioButtons, "일시정지", services.audioAvailable) { services.audio.pause() }
        addButton(audioButtons, "다음", services.audioAvailable) { services.audio.next() }
        val lightingCard = card(root, "06   조명 미리보기")
        lightingSwatch = View(this).apply { setBackgroundColor(Color.DKGRAY) }
        lightingCard.addView(lightingSwatch, LinearLayout.LayoutParams(-1, dp(58)))
        lightingText = text("프리셋을 선택하세요.", 14, Color.WHITE)
        lightingCard.addView(lightingText)
        val lightingButtons = row(lightingCard)
        LightingPreview.presets.forEach { preset -> configurationButtons += addButton(lightingButtons, preset.name) {
            session.lightingPreview = preset.copy(requestId = session.completedAnalysis?.snapshot?.requestId)
            renderLighting()
        } }
        configurationButtons += addButton(lightingCard, "밝기 조절") {
            val current = session.lightingPreview ?: return@addButton
            val levels = arrayOf("25%", "50%", "75%", "100%")
            AlertDialog.Builder(this).setTitle("미리보기 밝기").setItems(levels) { _, which ->
                session.lightingPreview = current.copy(brightness = (which + 1) / 4f, reason = "직접 선택")
                renderLighting()
            }.showWithAppFont()
        }
        renderLighting()
        addButton(root, "오픈소스 라이선스") {
            val names = (assets.list("licenses")?.map { "licenses/$it" }.orEmpty() +
                listOf("vision/Apache-2.0.txt", "vision/THIRD-PARTY-NOTICES.md")).sorted()
            AlertDialog.Builder(this).setTitle("외부 라이브러리 고지")
                .setItems(names.toTypedArray()) { _, index ->
                    val notice = assets.open(names[index]).bufferedReader().use { it.readText() }
                    val body = text(notice, 12, Color.WHITE).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
                    AlertDialog.Builder(this).setTitle(names[index])
                        .setView(ScrollView(this).apply { addView(body) })
                        .setPositiveButton("닫기", null).showWithAppFont()
                }.setNegativeButton("닫기", null).showWithAppFont()
        }
        typography.watch(root)
    }

    private fun showKeySettings() {
        val input = EditText(this).apply {
            hint = if (session.kmaKey.isEmpty()) "일반/디코딩 인증키" else "키 설정됨 · 바꾸려면 새 키 입력"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        AlertDialog.Builder(this).setTitle("기상청 인증키 · 메모리에만 보관")
            .setMessage("공공데이터포털 초단기실황의 일반/디코딩 키를 입력하세요. 키는 파일에 저장하지 않으며 앱이 완전히 종료되면 사라집니다. 빈 입력은 기존 키를 유지합니다.\n" + AndroidEnvironmentProvider.PRIVACY)
            .setView(input).setPositiveButton("적용") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { session.kmaKey = it }
                input.text.clear()
                Toast.makeText(this, "키 설정은 다음 무드 분석부터 사용합니다.", Toast.LENGTH_SHORT).show()
            }.setNeutralButton("키 삭제") { _, _ ->
                session.kmaKey = ""; input.text.clear()
                Toast.makeText(this, "키를 삭제했습니다. 다음 분석에는 날씨를 조회하지 않습니다.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("취소", null).showWithAppFont()
    }

    private fun showManualSettings() {
        val choices = arrayOf("수동 날씨: 비", "수동 날씨: 눈", "수동 날씨: 강수 없음", "수동 도로: 고속도로", "수동 도로: 도심", "수동 입력 모두 해제")
        AlertDialog.Builder(this).setTitle("수동 설정 · 실제 관측과 구분됩니다")
            .setItems(choices) { _, index ->
                when (index) {
                    0, 1, 2 -> { session.manualWeather = listOf("비", "눈", "강수 없음")[index] }
                    3, 4 -> { session.manualRoad = if (index == 3) RoadType.HIGHWAY else RoadType.URBAN }
                    5 -> { session.manualWeather = null; session.manualRoad = null }
                }
                Toast.makeText(this, "수동 설정은 다음 무드 분석부터 반영합니다.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("취소", null).showWithAppFont()
    }

    private fun renderDesiredMoods() {
        val selected = MoodVocabulary.options.filter { it in session.preferredMoods }
        desiredMoodText.text = "원하는 분위기 · " + if (selected.isEmpty()) "선택 없음" else selected.joinToString(" · ")
    }

    private fun showDesiredMoods() {
        if (session.activeRequestId != null) return
        val options = MoodVocabulary.options.toTypedArray()
        val checked = BooleanArray(options.size) { options[it] in session.preferredMoods }
        fun applySelection(values: Set<String>) {
            if (session.activeRequestId != null) return
            session.preferredMoods = values
            renderDesiredMoods()
            requestRecommendation()
        }
        AlertDialog.Builder(this).setTitle("원하는 분위기 · 여러 개 선택")
            .setMultiChoiceItems(options, checked) { _, index, selected -> checked[index] = selected }
            .setPositiveButton("적용") { _, _ -> applySelection(options.filterIndexed { index, _ -> checked[index] }.toSet()) }
            .setNeutralButton("선택 해제") { _, _ -> applySelection(emptySet()) }
            .setNegativeButton("취소", null).showWithAppFont()
    }

    private fun showTrackTags() {
        if (tracks.isEmpty()) { Toast.makeText(this, "로컬 음원을 먼저 선택하세요.", Toast.LENGTH_SHORT).show(); return }
        val snapshot = tracks.toList()
        AlertDialog.Builder(this).setTitle("곡 선택 · 태그는 사용자 입력")
            .setItems(snapshot.map { it.title }.toTypedArray()) { _, selected ->
                val track = snapshot[selected]
                val tags = EditText(this).apply { hint = "예: jazz, 차분한, 야간"; setText(track.tags.joinToString(", ")) }
                AlertDialog.Builder(this).setTitle(track.title).setMessage("곡의 분위기 태그를 쉼표로 구분해 입력하세요.")
                    .setView(tags).setPositiveButton("적용") { _, _ ->
                        val index = tracks.indexOfFirst { it.id == track.id }
                        if (index >= 0) tracks[index] = track.copy(tags = tags.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                        requestRecommendation()
                    }.setNegativeButton("취소", null).showWithAppFont()
            }.setNegativeButton("닫기", null).showWithAppFont()
    }

    private fun renderVision(value: VisionResult) {
        val status = when (value.status) {
            AnalysisStatus.OK -> "분석 완료"
            AnalysisStatus.LOW_CONFIDENCE -> "관측 근거 제한"
            AnalysisStatus.MODEL_NOT_READY -> "분석을 준비하지 못했습니다. 잠시 후 다시 시작해 주세요."
            AnalysisStatus.FAILED -> "분석하지 못했습니다. 카메라를 다시 시작해 주세요."
        }
        analysisText.text = "$status\n" + kr.urbanmood.integration.visionQualityText(value) +
            "\n밝기 ${percent(value.colors?.brightness)} · 채도 ${percent(value.colors?.saturation)} · 빛 영역 ${percent(value.colors?.luminousAreaRatio)}" +
            (value.sceneObservations?.let { "\n현재 프레임 · 사람 검출 ${it.persons?.detectionCount?.let { count -> "${count}개" } ?: "미확인"}" +
                " · 주변 빛 후보 ${it.ambientLights?.candidateCount?.let { count -> "${count}개" } ?: "미확인"}" } ?: "")
    }

    private fun renderEnvironment(value: EnvironmentSnapshot) {
        environmentText.text = "위치 ${availabilityLabel(value.location.availability)} · 정확도 ${value.location.accuracyMeters?.let { "${it.toInt()}m" } ?: "—"}\n" +
            source(value.location.source) + "\n도로 ${roadLabel(value.road.type)} · ${availabilityLabel(value.road.availability)}\n" +
            source(value.road.source) + "\n날씨 ${availabilityLabel(value.weather.availability)} · ${value.weather.condition ?: "상태 없음"} · ${value.weather.temperatureC?.let { "$it°C" } ?: "—"}" +
            " · 강수 ${value.weather.precipitationMm?.let { "${it}mm" } ?: "—"}\n" +
            source(value.weather.source) + "\n" + value.notices
                .filterNot { it == AndroidEnvironmentProvider.PRIVACY || it.startsWith("도로 데이터 미연결 시") }
                .joinToString("\n")
    }
    private fun source(value: SourceInfo) = "${value.name} · 관측 ${time(value.observedAtEpochMs)} · 조회 ${time(value.fetchedAtEpochMs)}" +
        (if (value.cached) " · 저장된 최근 정보" else "") + (value.detail?.let { "\n$it" } ?: "")
    private fun duration(value: Long) = "%d:%02d".format(value / 60_000, value / 1000 % 60)
    private fun time(value: Long?) = value?.let { DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "—"
    private fun percent(value: Float?) = value?.takeIf { it.isFinite() && it in 0f..1f }?.let { "${(it * 100).toInt()}%" } ?: "—"
    private fun availabilityLabel(value: Availability) = when (value) {
        Availability.AVAILABLE -> "연결됨"; Availability.UNAVAILABLE -> "미연결"
        Availability.PERMISSION_DENIED -> "권한 거부"; Availability.STALE -> "오래된 정보"
        Availability.ERROR -> "오류"; Availability.MANUAL -> "수동 입력"
    }
    private fun playbackLabel(value: PlaybackStatus) = when (value) {
        PlaybackStatus.UNAVAILABLE -> "미연결"; PlaybackStatus.IDLE -> "대기"
        PlaybackStatus.LOADING -> "불러오는 중"; PlaybackStatus.PLAYING -> "재생 중"
        PlaybackStatus.PAUSED -> "일시정지"; PlaybackStatus.ENDED -> "재생 종료"; PlaybackStatus.ERROR -> "오류"
    }
    private fun openExternal(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "열 수 있는 앱이 없습니다.", Toast.LENGTH_LONG).show() }
    }
    private fun showDetails(title: String, value: String) {
        val body = text(value, 14, Color.WHITE).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("닫기", null).showWithAppFont()
    }
    private fun AlertDialog.Builder.showWithAppFont(): AlertDialog {
        val dialog = show()
        val decor = requireNotNull(dialog.window).decorView
        val stop = typography.watch(decor)
        dialog.listView?.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                typography.apply(decor)
            }
        })
        dialog.setOnDismissListener { stop() }
        return dialog
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(7), 0, dp(7))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val result = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(Color.rgb(27, 42, 52)); cornerRadius = dp(18).toFloat() }
        }
        parent.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        result.addView(text(title, 14, mint, true))
        return result
    }
    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; parent.addView(this)
    }
    private fun addButton(parent: LinearLayout, label: String, enabled: Boolean = true, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f; isEnabled = enabled
            minHeight = dp(48); setTextColor(Color.rgb(16, 27, 35))
            backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(mint, Color.rgb(102, 117, 124)))
            setOnClickListener { action() }
        }
        val params = if (parent.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        parent.addView(button, params)
        return button
    }
}
