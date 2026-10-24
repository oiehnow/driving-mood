package kr.urbanmood

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
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
import androidx.activity.OnBackPressedCallback
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
    private lateinit var analyzeButton: View
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
    private val ink = Color.rgb(0, 44, 95)
    private val paper = Color.rgb(247, 248, 250)
    private val muted = Color.rgb(101, 113, 130)
    private lateinit var dashboard: kr.urbanmood.ui.MoodDashboardView
    private lateinit var detailPanel: LinearLayout
    private val pages = mutableListOf<ScrollView>()
    private val navigationButtons = mutableListOf<Button>()
    private lateinit var homeBody: LinearLayout
    private lateinit var navigationBar: View
    private lateinit var logoJourney: kr.urbanmood.ui.LogoJourneyView
    private lateinit var resultShell: View
    private lateinit var headerLogo: ImageView
    private lateinit var reanalyzeButton: Button
    private lateinit var settingsShortcut: ImageButton
    private var welcomeVisible = false
    private var resultTransitionRunning = false

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
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        session = ViewModelProvider(this)[SessionState::class.java]
        services = AppServices(applicationContext) { session.kmaKey }
        buildScreen()
        camera = VideoCaptureController(this, this, preview)
        recordedAnalyzer = RecordedSceneAnalyzer(services.vision)
        completionCue = CompletionCue(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    session.activeRequestId != null -> cancelAnalysis("분석을 취소했습니다.")
                    session.selectedPage != 0 -> selectPage(0)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
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
        selectPage(0)
        val id = session.beginAnalysis() ?: return
        beginLogoAnalysis()
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
                logoJourney.recording(elapsed, duration)
                frameStatus.text = "${elapsed / 1000} / ${duration / 1000}초"
            } },
            finalizing = { if (session.ownsRequest(id)) cameraStatus.text = "영상 저장 마무리 중" })
        try {
            if (!session.ownsRequest(id)) throw CancellationException()
            preview.visibility = View.GONE
            decodedPreview.visibility = View.VISIBLE
            cameraStatus.text = "분석 중"
            logoJourney.analyzing()
            frameStatus.text = "촬영한 영상의 장면을 확인하고 있습니다."
            return withTimeout(180_000) {
                recordedAnalyzer.analyze(clip) { value, bitmap, elapsed, samples ->
                    val env = services.environment.requestState.value.takeIf { it.requestId == id }?.snapshot
                    val score = SceneMoodScorer().score(SceneWindow(1_000, receivedFrameCount = 1,
                        samples = listOf(SceneSample(0, value))), env, value.frameTimeEpochMs)
                    withContext(Dispatchers.Main) {
                        if (session.ownsRequest(id)) {
                            logoJourney.analyzing(elapsed / 1000 + 1, (clip.timing.durationMs + 999) / 1000)
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
            logoJourney.resetAfterFailure()
            if (foreground) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        reanalyzeButton.isEnabled = !busy && !permissionInFlight
        settingsShortcut.visibility = if (welcomeVisible && !resultTransitionRunning && !busy && !permissionInFlight) View.VISIBLE else View.GONE
        cancelButton.isEnabled = busy
        cancelButton.visibility = if (busy && !welcomeVisible) View.VISIBLE else View.GONE
        frameStatus.visibility = if (!welcomeVisible && (busy || session.completedAnalysis != null)) View.VISIBLE else View.GONE
        navigationButtons.forEachIndexed { index, button -> button.isEnabled = !busy || index == 0 }
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
        dashboard.complete(value)
        moodText.text = recommendationText(value)
        scoreText.text = kr.urbanmood.integration.scoreSummaryText(value.scoreSummary)
        searchButton.isEnabled = !value.youtubeQuery.isNullOrBlank()
        revealAnalysisScreen()
    }

    private fun renderLighting() {
        if (!::lightingText.isInitialized) return
        val value = session.lightingPreview
        lightingText.text = value?.let { "${it.name} · 밝기 ${(it.brightness * 100).toInt()}% · ${it.reason}\n공통 무드: ${it.moodKeywords.joinToString(" · ").ifEmpty { "미확인" }}" }
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
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        resultShell = shell
        val stage = FrameLayout(this).apply { setBackgroundColor(paper) }
        stage.addView(shell, FrameLayout.LayoutParams(-1, -1))
        logoJourney = kr.urbanmood.ui.LogoJourneyView(this).apply { onStart = { startAnalysis() } }
        stage.addView(logoJourney, FrameLayout.LayoutParams(-1, -1))
        analyzeButton = logoJourney.actionView
        settingsShortcut = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings_gear)
            imageTintList = ColorStateList.valueOf(muted)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "설정"
            setOnClickListener { selectPage(2) }
        }
        stage.addView(settingsShortcut, FrameLayout.LayoutParams(dp(48), dp(48),
            android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                rightMargin = dp(16); bottomMargin = dp(16)
            })
        val pageHost = FrameLayout(this)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        fun page(): LinearLayout {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(26)) }
            val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body) }
            pageHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            pages += scroll
            return body
        }
        val root = page()
        homeBody = root
        val musicPage = page()
        val settingsPage = page()
        setContentView(stage)
        ViewCompat.setOnApplyWindowInsetsListener(stage) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        val brand = row(root).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        headerLogo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        brand.addView(headerLogo, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        brand.addView(text(getString(R.string.app_name), 18, ink, true))
        root.addView(text("지금 이 길의 분위기", 13, muted))
        dashboard = kr.urbanmood.ui.MoodDashboardView(this)
        dashboard.actionView.apply { tag = null; isClickable = false; isFocusable = false; accessibilityDelegate = null }
        root.addView(dashboard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(12) })
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
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, dp(190)))
        cameraStatus = text("분석을 시작하면 카메라가 켜집니다.", 12, muted)
        frameStatus = text("촬영 → 분석 → 무드 확인", 12, muted).apply { visibility = View.GONE }
        root.addView(cameraStatus)
        root.addView(frameStatus)
        cancelButton = addButton(root, "취소", false) { cancelAnalysis("분석을 취소했습니다. 이전 결과는 유지됩니다.") }
        cancelButton.visibility = View.GONE
        root.removeView(cancelButton)
        root.addView(cancelButton, root.indexOfChild(previewContainer))
        lateinit var detailsButton: Button
        detailsButton = addButton(root, "상세 보기  ↓") {
            val expanded = detailPanel.visibility != View.VISIBLE
            detailPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            detailsButton.text = if (expanded) "상세 접기  ↑" else "상세 보기  ↓"
        }
        detailsButton.contentDescription = "분석 상세 펼치기 또는 접기"
        detailPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(detailPanel)
        val analysisCard = card(detailPanel, "장면 관측")
        analysisText = text("분석을 마치면 주변 색감과 빛을 확인할 수 있어요.", 14, ink)
        analysisCard.addView(analysisText)
        addButton(analysisCard, "분석 상세") {
            showDetails("분석 상세", latestVision?.let { value ->
                "관측 ${time(value.frameTimeEpochMs)}\n" + value.reasons.joinToString("\n")
            } ?: "무드 분석을 마치면 내용을 확인할 수 있습니다.")
        }
        scoreText = text("관측을 마치면 분위기별 점수와 기여 항목을 확인할 수 있습니다.", 13, muted)
        analysisCard.addView(scoreText)
        addButton(analysisCard, "점수와 관측 근거") {
            showDetails("점수와 관측 근거", kr.urbanmood.integration.scoreDetailsText(recommendation?.scoreSummary))
        }
        addButton(root, "음악과 조명으로 이어가기  →") { selectPage(1) }
        reanalyzeButton = addButton(root, "재분석") { startAnalysis() }

        musicPage.addView(text("나의 드라이브", 28, ink, true))
        musicPage.addView(text("주변의 무드를 나만의 분위기로", 13, muted))
        val moodCard = card(musicPage, "분위기와 취향")
        moodText = text("주변 무드를 분석하고 어울리는 음악을 만나보세요.", 15, ink)
        moodCard.addView(moodText)
        desiredMoodText = text("", 13, muted)
        moodCard.addView(desiredMoodText)
        renderDesiredMoods()
        configurationButtons += addButton(moodCard, "원하는 분위기 선택") { showDesiredMoods() }
        preferenceInput = EditText(this).apply {
            hint = "좋아하는 장르 · jazz, ambient"
            setTextColor(ink); setHintTextColor(muted); textSize = 14f
            maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT
            contentDescription = "쉼표로 구분한 선호 장르"
            setText(session.genres)
        }
        moodCard.addView(preferenceInput)
        addButton(moodCard, "취향 적용") { requestRecommendation() }
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
        val audioCard = card(musicPage, "내 음악")
        catalogText = text("선택한 음원 ${tracks.size}곡", 13, muted)
        playerText = text("재생 대기 · 기기 내 음원을 선택하세요.", 15, ink)
        audioCard.addView(catalogText); audioCard.addView(playerText)
        addButton(audioCard, "로컬 음원 선택", services.audioAvailable) { musicPicker.launch(arrayOf("audio/*")) }
        addButton(audioCard, "곡별 장르·분위기 태그") { showTrackTags() }
        val audioButtons = row(audioCard)
        addAudioButton(audioButtons, "재생", R.drawable.ic_play, services.audioAvailable) {
            if (session.completedAnalysis == null) Toast.makeText(this, "무드 분석 후 음악을 재생할 수 있습니다.", Toast.LENGTH_SHORT).show()
            else services.audio.play()
        }
        addAudioButton(audioButtons, "일시정지", R.drawable.ic_pause, services.audioAvailable) { services.audio.pause() }
        addAudioButton(audioButtons, "다음", R.drawable.ic_next, services.audioAvailable) { services.audio.next() }
        val lightingCard = card(musicPage, "조명 미리보기")
        lightingSwatch = View(this).apply { setBackgroundColor(Color.DKGRAY) }
        lightingCard.addView(lightingSwatch, LinearLayout.LayoutParams(-1, dp(58)))
        lightingText = text("프리셋을 선택하세요.", 14, ink)
        lightingCard.addView(lightingText)
        var lightingButtons = row(lightingCard)
        LightingPreview.presets.forEachIndexed { index, preset ->
            if (index == 2) lightingButtons = row(lightingCard)
            configurationButtons += addButton(lightingButtons, preset.name) {
            session.lightingPreview = preset.copy(requestId = session.completedAnalysis?.snapshot?.requestId,
                moodKeywords = session.completedAnalysis?.recommendation?.commonMoodKeywords.orEmpty().toList())
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

        settingsPage.addView(text("설정", 28, ink, true))
        settingsPage.addView(text("분석에 사용할 연결과 권한", 13, muted))
        val environmentCard = card(settingsPage, "위치 · 도로 · 날씨")
        environmentText = text("위치 미연결\n도로 알 수 없음 · 날씨 미연결", 14, ink)
        environmentCard.addView(environmentText)
        configurationButtons += addButton(environmentCard, "기상청 키 설정") { showKeySettings() }
        configurationButtons += addButton(environmentCard, "수동 설정") { showManualSettings() }
        addButton(settingsPage, "권한 · 앱 설정") {
            openExternal(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        addButton(settingsPage, "데이터 사용 안내") {
            showDetails("데이터 사용 안내", AndroidEnvironmentProvider.PRIVACY + "\n영상은 기기에 임시 저장 후 삭제합니다.\n날씨를 연결하려면 인증키가 필요합니다. 도로는 직접 설정할 수 있습니다.")
        }
        settingsPage.addView(text("영상은 기기에 임시 저장 후 삭제합니다.", 12, muted))
        val about = card(settingsPage, "Driving Mood")
        about.addView(text("제작자 oieh", 15, ink, true))
        about.addView(text("버전 ${packageManager.getPackageInfo(packageName, 0).versionName}", 12, muted))
        addButton(settingsPage, "오픈소스 라이선스") {
            val names = (assets.list("licenses")?.map { "licenses/$it" }.orEmpty() +
                listOf("vision/Apache-2.0.txt", "vision/THIRD-PARTY-NOTICES.md")).sorted()
            AlertDialog.Builder(this).setTitle("외부 라이브러리 고지")
                .setItems(names.toTypedArray()) { _, index ->
                    val notice = assets.open(names[index]).bufferedReader().use { it.readText() }
                    val body = text(notice, 12, ink).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
                    AlertDialog.Builder(this).setTitle(names[index])
                        .setView(ScrollView(this).apply { addView(body) })
                        .setPositiveButton("닫기", null).showWithAppFont()
                }.setNegativeButton("닫기", null).showWithAppFont()
        }

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.WHITE)
        }
        navigationBar = navigation
        shell.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        listOf("분석", "음악·조명", "설정").forEachIndexed { index, label ->
            val item = addButton(navigation, label) { selectPage(index) }
            item.setCompoundDrawablesWithIntrinsicBounds(0, listOf(R.drawable.ic_mood, R.drawable.ic_music, R.drawable.ic_settings)[index], 0, 0)
            item.compoundDrawablePadding = dp(4); item.textSize = 11f
            navigationButtons += item
        }
        selectPage(session.selectedPage)
        if (!session.hasEnteredAnalysis && session.completedAnalysis == null && session.selectedPage == 0) {
            showLogoScreen()
        }
        typography.watch(stage)
    }

    private fun showLogoScreen() {
        welcomeVisible = true
        analyzeButton = logoJourney.actionView
        resultShell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        headerLogo.visibility = View.INVISIBLE
        settingsShortcut.visibility = View.VISIBLE
        logoJourney.post { if (welcomeVisible && session.activeRequestId == null) logoJourney.showIntro() }
    }

    private fun headerBounds(): RectF {
        val logoLocation = IntArray(2)
        val overlayLocation = IntArray(2)
        headerLogo.getLocationOnScreen(logoLocation)
        logoJourney.getLocationOnScreen(overlayLocation)
        val x = (logoLocation[0] - overlayLocation[0]).toFloat()
        val y = (logoLocation[1] - overlayLocation[1]).toFloat()
        return RectF(x, y, x + headerLogo.width, y + headerLogo.height)
    }

    private fun beginLogoAnalysis() {
        resultTransitionRunning = false
        pages[0].scrollTo(0, 0)
        val fromHeader = if (!welcomeVisible) headerBounds() else null
        welcomeVisible = true
        analyzeButton = logoJourney.actionView
        settingsShortcut.visibility = View.GONE
        resultShell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        headerLogo.visibility = View.INVISIBLE
        logoJourney.startCapture(fromHeader)
    }

    private fun revealAnalysisScreen() {
        session.hasEnteredAnalysis = true
        if (!welcomeVisible) return
        resultTransitionRunning = true
        settingsShortcut.visibility = View.GONE
        previewContainer.visibility = View.GONE
        pages[0].scrollTo(0, 0)
        logoJourney.finishTo(headerBounds()) {
            resultTransitionRunning = false
            welcomeVisible = false
            analyzeButton = reanalyzeButton
            headerLogo.visibility = View.VISIBLE
            resultShell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            updateControls()
        }
    }

    private fun selectPage(index: Int) {
        if (session.activeRequestId != null && index != 0) return
        val selected = index.coerceIn(0, pages.lastIndex)
        session.selectedPage = selected
        pages.forEachIndexed { position, view -> view.visibility = if (position == selected) View.VISIBLE else View.GONE }
        navigationButtons.forEachIndexed { position, button ->
            button.isSelected = position == selected
            button.setTextColor(if (button.isSelected) ink else muted)
            button.backgroundTintList = ColorStateList.valueOf(if (button.isSelected) Color.rgb(234, 240, 247) else Color.WHITE)
            button.compoundDrawableTintList = ColorStateList.valueOf(if (button.isSelected) ink else muted)
        }
        if (::reanalyzeButton.isInitialized) {
            if (selected != 0) {
                welcomeVisible = false
                logoJourney.visibility = View.GONE
                settingsShortcut.visibility = View.GONE
                resultShell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else if (session.completedAnalysis == null && session.activeRequestId == null && !welcomeVisible) {
                showLogoScreen()
            }
        }
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
        val body = text(value, 14, ink).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
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
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        }
        parent.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        result.addView(text(title, 16, ink, true))
        return result
    }
    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; parent.addView(this)
    }
    private fun primary(button: Button) {
        button.setTextColor(Color.WHITE)
        button.compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        button.backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(ink, Color.rgb(122, 140, 160)))
    }
    private fun addAudioButton(parent: LinearLayout, label: String, icon: Int, enabled: Boolean, action: () -> Unit): ImageButton {
        val button = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = label
            isEnabled = enabled
            minimumWidth = dp(48); minimumHeight = dp(48)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(ink, muted))
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x18002c5f),
                GradientDrawable().apply { setColor(Color.rgb(237, 241, 246)); cornerRadius = dp(14).toFloat() }, null)
            stateListAnimator = null
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            topMargin = dp(6); bottomMargin = dp(6); marginStart = dp(3); marginEnd = dp(3)
        })
        return button
    }
    private fun addButton(parent: LinearLayout, label: String, enabled: Boolean = true, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f; isEnabled = enabled
            minHeight = dp(48); minimumHeight = dp(48); setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(ink, muted)))
            background = GradientDrawable().apply { setColor(Color.rgb(237, 241, 246)); cornerRadius = dp(14).toFloat() }
            stateListAnimator = null
            val icon = when (label) {
                "무드 분석" -> R.drawable.ic_camera
                "재생" -> R.drawable.ic_play
                "일시정지" -> R.drawable.ic_pause
                "다음" -> R.drawable.ic_next
                "밝기 조절" -> R.drawable.ic_light
                "데이터 사용 안내" -> R.drawable.ic_info
                else -> null
            }
            icon?.let {
                setCompoundDrawablesWithIntrinsicBounds(it, 0, 0, 0)
                compoundDrawablePadding = dp(6)
                compoundDrawableTintList = ColorStateList.valueOf(ink)
            }
            setOnClickListener { action() }
        }
        val params = if (parent.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(6); params.bottomMargin = dp(6)
        if (parent.orientation == LinearLayout.HORIZONTAL) { params.marginStart = dp(3); params.marginEnd = dp(3) }
        parent.addView(button, params)
        return button
    }
}
