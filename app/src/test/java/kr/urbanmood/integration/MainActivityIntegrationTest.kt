package kr.urbanmood.integration

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kr.urbanmood.MainActivity
import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Real activity/services with Android shadows. No claim of native camera/model/GPS/audio execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityIntegrationTest {
    @Test fun recreationDuringPreferenceUpdateKeepsAppliedInputsWithTheirResult() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            val state = ViewModelProvider(activity)[SessionState::class.java]
            val oldInputs = UserPreferences(preferredMoods = setOf("차분한"))
            val newInputs = UserPreferences(preferredMoods = setOf("활기 넘침"))
            val previous = completedFixture().copy(recommendationPreferences = oldInputs)
            state.completedAnalysis = previous
            state.preferredMoods = newInputs.preferredMoods
            val gate = CompletableDeferred<RecommendationResult>()
            val update = activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.refreshRecommendation(newInputs) { _, _ -> gate.await() }
            }
            ReflectionHelpers.setField(activity, "recommendationJob", update)
            controller.recreate()
            val restored = controller.get()
            assertTrue(update.isCancelled)
            assertSame(previous, ViewModelProvider(restored)[SessionState::class.java].completedAnalysis)
            assertEquals("차분한", restored.window.decorView.findViewWithTag<TextView>("evidence_applied_preferences").text.toString())
            assertTrue(restored.window.decorView.findViewWithTag<TextView>("evidence_preference_notice").text.contains("아직 적용되지"))
            gate.complete(previous.recommendation)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(previous, ViewModelProvider(restored)[SessionState::class.java].completedAnalysis)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun navigationRetainsSelectedPageAndDoesNotStartSensors() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            ViewModelProvider(controller.get())[SessionState::class.java].completedAnalysis = completedFixture()
            controller.recreate()
            button(controller.get(), "음악·조명").performClick()
            assertEquals(1, ViewModelProvider(controller.get())[SessionState::class.java].selectedPage)
            controller.recreate()
            val activity = controller.get()
            assertTrue(button(activity, "음악·조명").isSelected)
            assertTrue(button(activity, "기기에서 음악 추가").isShown)
            assertFalse(button(activity, "무드 분석").isShown)
            button(activity, "분석").performClick()
            assertTrue(button(activity, "무드 분석").isShown)
            assertFalse(button(activity, "기술 정보 펼치기  ↓").isShown)
            button(activity, "상세 보기  ↓").performClick()
            assertTrue(button(activity, "기술 정보 펼치기  ↓").isShown)
            assertNull(shadowOf(activity).lastRequestedPermission)
            assertNull(ViewModelProvider(activity)[SessionState::class.java].activeRequestId)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun commonMoodAndLightingSurviveRotationAndCancelledNextRequestAtBothDurations() {
        for (length in listOf(30_000L, 60_000L)) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
            try {
                val initial = controller.get()
                val state = ViewModelProvider(initial)[SessionState::class.java]
                val base = completedFixture()
                val completed = base.copy(snapshot = base.snapshot.copy(sceneWindow = SceneWindow(length)),
                    recommendation = base.recommendation.copy(moodKeywords = listOf("차가운"),
                        commonMoodKeywords = listOf("따뜻한", "다채로운")))
                state.recordingDurationMs = length
                state.completedAnalysis = completed
                val light = LightingPreview.suggestion(completed)!!
                state.lightingPreview = light
                assertTrue(state.claimCompletionSound(completed.snapshot.requestId))
                controller.recreate()
                val activity = controller.get()
                val restored = ViewModelProvider(activity)[SessionState::class.java]
                assertSame(completed, restored.completedAnalysis)
                assertSame(light, restored.lightingPreview)
                assertEquals(length, restored.recordingDurationMs)
                assertTrue(screen(activity).contains("주변 무드: 따뜻한 · 다채로운"))
            assertTrue(screen(activity).contains("추천 분위기: 차가운"))
                assertTrue(screen(activity).contains("공통 무드: 따뜻한 · 다채로운"))
                assertFalse(restored.claimCompletionSound(completed.snapshot.requestId))
                assertNull(shadowOf(activity).lastRequestedPermission)
                button(activity, "무드 분석").performClick()
                assertNotNull(restored.activeRequestId)
                activity.onBackPressedDispatcher.onBackPressed()
                val pending = shadowOf(activity).lastRequestedPermission
                activity.onRequestPermissionsResult(pending.requestCode, pending.requestedPermissions,
                    IntArray(pending.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED })
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(restored.activeRequestId)
                assertSame(completed, restored.completedAnalysis)
                assertSame(light, restored.lightingPreview)
                assertFalse(restored.claimCompletionSound(completed.snapshot.requestId))
                assertTrue(screen(activity).contains("공통 무드: 따뜻한 · 다채로운"))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun recordingLengthAndLightingPreviewSurviveRotationWithoutStartingCapture() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            var activity = controller.get()
            assertFalse(screen(activity).contains("촬영 30초"))
            assertFalse(screen(activity).contains("촬영 60초"))
            assertTrue(button(activity, "무드 분석").isEnabled)
            openSettings(activity)
            button(activity, "음악·조명").performClick()
            button(activity, "아이스 블루").performClick()
            button(activity, "밝기 조절").performClick()
            ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 2, 2)
            val before = ViewModelProvider(activity)[SessionState::class.java]
            assertEquals(30_000L, before.recordingDurationMs)
            assertEquals(.75f, before.lightingPreview!!.brightness)
            assertEquals(LightingTarget.PREVIEW_ONLY, before.lightingPreview!!.target)
            assertNull(before.activeRequestId)
            val config = Configuration(activity.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            controller.configurationChange(config)
            activity = controller.get()
            assertEquals(30_000L, ViewModelProvider(activity)[SessionState::class.java].recordingDurationMs)
            assertTrue(screen(activity).contains("아이스 블루 · 밝기 75%"))
            assertNull(shadowOf(activity).lastRequestedPermission)
        } finally { controller.pause().stop().destroy() }
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun button(activity: MainActivity, label: String): View {
        if (label == "무드 분석") {
            shadowOf(Looper.getMainLooper()).idle()
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 1920)
            // Robolectric setup attaches the view while leaving the synthetic window GONE.
            val attachInfo = ReflectionHelpers.getField<Any?>(decor, "mAttachInfo")
            if (attachInfo != null) {
                ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
                decor.dispatchWindowVisibilityChanged(View.VISIBLE)
            }
            // Allow the finite intro/result transition to settle without draining recurring animations.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_500))
            val candidates = views(activity.window.decorView)
            return candidates.firstOrNull { it.tag == "mood-analysis-action" && it.isShown }
                ?: candidates.filterIsInstance<Button>().first { it.text.toString() == "재분석" }
        }
        return views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }
    }
    private fun openSettings(activity: MainActivity) {
        shadowOf(Looper.getMainLooper()).idle()
        val shortcut = views(activity.window.decorView).firstOrNull { it.contentDescription == "설정" && it.isShown }
        val action = shortcut ?: button(activity, "설정")
        assertTrue(action.isShown)
        action.performClick()
        assertTrue(button(activity, "기상청 인증키").isShown)
    }
    private fun screen(activity: MainActivity) = views(activity.window.decorView)
        .filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun awaitUi(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Expected UI state did not arrive", condition())
    }

    @Test fun launchWithoutKeysOrPermissionsShowsMissingDataAndAllowsLocalSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            assertTrue(button(activity, "기기에서 음악 추가").isEnabled)
            assertTrue(button(activity, "무드 분석").isEnabled)
            assertTrue(views(activity.window.decorView).any { it.contentDescription == "설정" && it.isShown })
            assertFalse(button(activity, "취소").isEnabled)
            assertNull(ViewModelProvider(activity)[SessionState::class.java].activeRequestId)
            assertNull(shadowOf(activity).lastRequestedPermission)
            assertTrue(screen(activity).contains("0곡"))
            assertFalse(screen(activity).contains("C 세션 연결 대기"))
            assertFalse(screen(activity).contains("B 세션 구현을 기다리고"))
            assertTrue(screen(activity).contains("Driving Mood"))
            assertFalse(screen(activity).contains("UrbanMood"))
            assertEquals("Driving Mood", activity.applicationInfo.loadLabel(activity.packageManager).toString())
            assertEquals("kr.urbanmood", activity.packageName)
            assertTrue(screen(activity).contains("영상은 기기에 임시 저장 후 삭제합니다."))
            assertFalse(screen(activity).contains("촬영 30초"))
            assertTrue(screen(activity).contains("조명 미리보기"))
            listOf("PHONE PROTOTYPE", "Pleos", "휴대폰 실험", "실제 기기 검증", "시연", "공모전", "제출", "HSV", "ABI").forEach {
                assertFalse("Internal wording on product screen: $it", screen(activity).contains(it))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun manualSettingsOnlyConfigureTheNextRun() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            openSettings(activity)
            button(activity, "수동 설정").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(null, 3, 3)
            val session = ViewModelProvider(activity)[SessionState::class.java]
            assertEquals(RoadType.HIGHWAY, session.manualRoad)
            assertTrue(screen(activity).contains("도로 알 수 없음"))
            assertNull(session.activeRequestId)
            assertNull(session.completedAnalysis)
            button(activity, "수동 설정").performClick()
            ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 5, 5)
            assertNull(session.manualRoad)
            assertNull(session.activeRequestId)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun weatherKeyIsMemoryOnlyMaskedAndDeletable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            openSettings(activity)
            button(activity, "기상청 인증키").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            assertFalse(input.isSaveEnabled)
            input.setText("test-placeholder-not-a-real-key")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            awaitUi { session.kmaKey.isNotEmpty() }
            assertEquals("test-placeholder-not-a-real-key", session.kmaKey)
            assertFalse(screen(activity).contains("test-placeholder-not-a-real-key"))
            button(activity, "기상청 인증키").performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            awaitUi { session.kmaKey.isEmpty() }
            assertEquals("", session.kmaKey)
            assertNull(session.activeRequestId)
            assertNull(session.completedAnalysis)
            assertNull(shadowOf(activity).lastRequestedPermission)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun duplicateTapIsBlockedPermissionPauseSurvivesButBackgroundCancels() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            val analyze = button(activity, "무드 분석")
            analyze.performClick()
            val id = session.activeRequestId
            assertNotNull(id)
            assertFalse(analyze.isEnabled)
            assertFalse(button(activity, "원하는 분위기 선택").isEnabled)
            analyze.performClick()
            assertEquals(id, session.activeRequestId)
            controller.pause()
            assertEquals(id, session.activeRequestId)
            controller.stop()
            assertNull(session.activeRequestId)
            controller.start().resume().visible()
            assertNull(session.activeRequestId)
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(session.activeRequestId)
            assertNull(session.completedAnalysis)
            assertTrue(button(activity, "무드 분석").isEnabled)
            assertTrue(screen(activity).contains("취소"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cancelThenLatePermissionKeepsPriorCompletedResult() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            val previous = completedFixture()
            session.completedAnalysis = previous
            button(activity, "무드 분석").performClick()
            activity.onBackPressedDispatcher.onBackPressed()
            assertNull(session.activeRequestId)
            assertSame(previous, session.completedAnalysis)
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED })
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(session.activeRequestId)
            assertSame(previous, session.completedAnalysis)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun deniedPermissionsCompletePartialResultAndResumeDoesNotRefresh() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            button(activity, "무드 분석").performClick()
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
            awaitUi { session.completedAnalysis != null && session.activeRequestId == null }
            val completed = session.completedAnalysis!!
            assertEquals(Availability.PERMISSION_DENIED, completed.snapshot.environment.location.availability)
            assertEquals(Availability.UNAVAILABLE, completed.snapshot.environment.weather.availability)
            assertEquals(AnalysisStatus.FAILED, completed.snapshot.vision.status)
            assertTrue(screen(activity).contains("분석 완료"))
            controller.pause().stop().start().resume().visible()
            assertSame(completed, session.completedAnalysis)
            assertNull(session.activeRequestId)
            assertTrue(screen(activity).contains("분석 완료"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun permissionDeadlineShowsCancellationAndLateCallbackCannotRestart() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            val previous = completedFixture()
            session.completedAnalysis = previous
            button(activity, "무드 분석").performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(111))
            assertNull(session.activeRequestId)
            assertSame(previous, session.completedAnalysis)
            assertTrue(screen(activity).contains("시간이 초과"))
            assertFalse(button(activity, "무드 분석").isEnabled)
            val request = shadowOf(activity).lastRequestedPermission
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED })
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(session.activeRequestId)
            assertSame(previous, session.completedAnalysis)
            assertTrue(button(activity, "무드 분석").isEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rotationRestoresCompletedSnapshotWithoutCollection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val session = ViewModelProvider(controller.get())[SessionState::class.java]
            val previous = completedFixture()
            session.completedAnalysis = previous
            controller.configurationChange(Configuration(controller.get().resources.configuration).apply {
                orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE)
                    Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            })
            val recreated = controller.get()
            val restored = ViewModelProvider(recreated)[SessionState::class.java]
            assertSame(previous, restored.completedAnalysis)
            assertNull(restored.activeRequestId)
            assertTrue(screen(recreated).contains("분석 완료"))
            assertTrue(screen(recreated).contains("도로 고속도로"))
            assertTrue(screen(recreated).contains("수동 입력"))
            assertTrue(button(recreated, "무드 분석").isEnabled)
            assertNull(shadowOf(recreated).lastRequestedPermission)
            button(recreated, "음악·조명").performClick()
            button(recreated, "취향 적용").performClick()
            awaitUi { restored.completedAnalysis?.recommendation != previous.recommendation }
            assertSame(previous.snapshot, restored.completedAnalysis?.snapshot)
            assertNull(restored.activeRequestId)
        } finally { controller.pause().stop().destroy() }
    }

    private fun completedFixture() = CompletedAnalysis(
        AnalysisSnapshot(99, 1_792_800_000_000, EnvironmentSnapshot(
            road = RoadContext(Availability.MANUAL, RoadType.HIGHWAY, source = SourceInfo("수동 입력"))),
            VisionResult(1_792_800_000_000, AnalysisStatus.FAILED)),
        RecommendationResult(Availability.MANUAL, listOf("차분한")))

    @Test fun scoreLedgerAndCompletedWindowSurviveRotationAndCancelledNewRequest() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val initial = controller.get()
            val base = completedFixture()
            val window = SceneWindow(50_000, receivedFrameCount = 8,
                samples = listOf(SceneSample(0, base.snapshot.vision), SceneSample(1_000, base.snapshot.vision)))
            val ledger = MoodScoreSummary("scene-test", 50_000, 8, 2, listOf(MoodScore("활기 넘침", 10f,
                listOf(MoodScoreContribution("people", "사람 검출", delta = 10f, reason = "대표 구간 평균의 그룹 기여")))))
            val completed = base.copy(snapshot = base.snapshot.copy(sceneWindow = window),
                recommendation = base.recommendation.copy(scoreSummary = ledger))
            ViewModelProvider(initial)[SessionState::class.java].completedAnalysis = completed
            controller.recreate()
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            assertSame(window, session.completedAnalysis!!.snapshot.sceneWindow)
            assertTrue(screen(activity).contains("2개 장면"))
            button(activity, "상세 보기  ↓").performClick()
            val technical = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("evidence_technical_body")
            assertFalse(technical.isShown)
            button(activity, "기술 정보 펼치기  ↓").performClick()
            assertTrue(technical.isShown)
            val details = views(technical).filterIsInstance<TextView>().joinToString(" ") { it.text }
            assertTrue(details.contains("사람 검출: +10.0점"))
            assertTrue(details.contains("대표 구간 평균"))
            button(activity, "무드 분석").performClick()
            activity.onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(completed, session.completedAnalysis)
            assertNull(session.activeRequestId)
            assertTrue(screen(activity).contains("활기 넘침 10.0점"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun bundledFontCoversMainInputButtonsDialogsAndUpdatedText() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            // Enter settings through the launch shortcut, without starting sensor collection.
            openSettings(activity)
            fun assertFont(root: View) {
                val texts = views(root).filterIsInstance<TextView>().filter { it.isShown }
                assertTrue(texts.isNotEmpty())
                texts.forEach { view ->
                    assertTrue("Bundled family missing for ${view.text}: ${shadowOf(view.typeface).fontDescription.familyName}; ${view.fontVariationSettings}",
                        shadowOf(view.typeface).fontDescription.familyName.contains("pretendard_variable"))
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertFont(activity.findViewById(android.R.id.content))
            button(activity, "기상청 인증키").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            var dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFont(dialog.window!!.decorView)
            val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            input.setText("font-display-placeholder")
            assertFont(dialog.window!!.decorView)
            dialog.dismiss()
            button(activity, "음악·조명").performClick()
            button(activity, "원하는 분위기 선택").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFont(dialog.window!!.decorView)
            selectMood(dialog, "차가운")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFont(activity.findViewById(android.R.id.content))
            assertTrue(activity.assets.open("licenses/Pretendard-OFL.txt").bufferedReader().use { it.readText() }.contains("SIL OPEN FONT LICENSE Version 1.1"))
        } finally { controller.pause().stop().destroy() }
    }

    private fun selectMood(dialog: AlertDialog, mood: String) {
        val index = MoodVocabulary.options.indexOf(mood)
        assertTrue(index >= 0)
        dialog.listView.performItemClick(dialog.listView.getChildAt(index), index, index.toLong())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun desiredMoodApplyCancelAndClearNeverStartCollection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            openSettings(activity)
            button(activity, "음악·조명").performClick()
            button(activity, "원하는 분위기 선택").performClick()
            var dialog = ShadowAlertDialog.getLatestAlertDialog()
            selectMood(dialog, "차가운")
            selectMood(dialog, "우울")
            selectMood(dialog, "활기 넘침")
            assertTrue(session.preferredMoods.isEmpty())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            awaitUi { session.preferredMoods.size == 3 }
            assertEquals(setOf("차가운", "우울", "활기 넘침"), session.preferredMoods)
            assertTrue(screen(activity).contains("원하는 분위기 · 차가운 · 우울 · 활기 넘침"))
            button(activity, "원하는 분위기 선택").performClick()
            dialog = ShadowAlertDialog.getLatestAlertDialog()
            selectMood(dialog, "우울")
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("우울" in session.preferredMoods)
            button(activity, "원하는 분위기 선택").performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            awaitUi { session.preferredMoods.isEmpty() }
            assertTrue(session.preferredMoods.isEmpty())
            assertNull(session.activeRequestId)
            assertNull(session.completedAnalysis)
            assertNull(shadowOf(activity).lastRequestedPermission)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun desiredMoodUsesCompletedSnapshotAndSurvivesRotation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible().windowFocusChanged(true)
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            openSettings(activity)
            button(activity, "음악·조명").performClick()
            val previous = completedFixture()
            session.completedAnalysis = previous
            button(activity, "원하는 분위기 선택").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            selectMood(dialog, "우울")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            awaitUi { session.completedAnalysis?.recommendation != previous.recommendation }
            assertTrue(session.completedAnalysis?.recommendation?.youtubeQuery.orEmpty().contains("우울"))
            assertTrue(session.completedAnalysis!!.recommendation.reasons.any { it.contains("사용자가 원하는 분위기") })
            assertSame(previous.snapshot, session.completedAnalysis?.snapshot)
            assertNull(session.activeRequestId)
            assertNull(shadowOf(activity).lastRequestedPermission)
            controller.configurationChange(Configuration(activity.resources.configuration).apply {
                orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE)
                    Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            })
            val recreated = controller.get()
            val restored = ViewModelProvider(recreated)[SessionState::class.java]
            assertEquals(setOf("우울"), restored.preferredMoods)
            assertSame(previous.snapshot, restored.completedAnalysis?.snapshot)
            assertTrue(screen(recreated).contains("원하는 분위기 · 우울"))
            assertNull(restored.activeRequestId)
        } finally { controller.pause().stop().destroy() }
    }
}
