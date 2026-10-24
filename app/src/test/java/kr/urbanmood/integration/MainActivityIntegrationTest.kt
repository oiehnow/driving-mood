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
import java.time.Duration

/** Real activity/services with Android shadows. No claim of native camera/model/GPS/audio execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityIntegrationTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun button(activity: MainActivity, label: String) = views(activity.window.decorView)
        .filterIsInstance<Button>().first { it.text.toString() == label }
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(button(activity, "로컬 음원 선택").isEnabled)
            assertTrue(button(activity, "무드 분석").isEnabled)
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
            assertTrue(screen(activity).contains("영상은 저장하거나 전송하지 않습니다."))
            listOf("PHONE PROTOTYPE", "Pleos", "휴대폰 실험", "실제 기기 검증", "시연", "공모전", "제출", "HSV", "ABI").forEach {
                assertFalse("Internal wording on product screen: $it", screen(activity).contains(it))
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun manualSettingsOnlyConfigureTheNextRun() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            button(activity, "기상청 키 설정").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            assertFalse(input.isSaveEnabled)
            input.setText("test-placeholder-not-a-real-key")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            awaitUi { session.kmaKey.isNotEmpty() }
            assertEquals("test-placeholder-not-a-real-key", session.kmaKey)
            assertFalse(screen(activity).contains("test-placeholder-not-a-real-key"))
            button(activity, "기상청 키 설정").performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            awaitUi { session.kmaKey.isEmpty() }
            assertEquals("", session.kmaKey)
            assertNull(session.activeRequestId)
            assertNull(session.completedAnalysis)
            assertNull(shadowOf(activity).lastRequestedPermission)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun duplicateTapIsBlockedPermissionPauseSurvivesButBackgroundCancels() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
            val previous = completedFixture()
            session.completedAnalysis = previous
            button(activity, "무드 분석").performClick()
            button(activity, "취소").performClick()
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
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
            assertTrue(screen(activity).contains("2개 시간 구간"))
            button(activity, "점수와 관측 근거").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val details = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString(" ") { it.text }
            assertTrue(details.contains("사람 검출: +10.0점"))
            assertTrue(details.contains("대표 구간 평균"))
            dialog.dismiss()
            button(activity, "무드 분석").performClick()
            button(activity, "취소").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(completed, session.completedAnalysis)
            assertNull(session.activeRequestId)
            assertTrue(screen(activity).contains("활기 넘침 10.0점"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun bundledFontCoversMainInputButtonsDialogsAndUpdatedText() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
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
            button(activity, "기상청 키 설정").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            var dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertFont(dialog.window!!.decorView)
            val input = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
            input.setText("font-display-placeholder")
            assertFont(dialog.window!!.decorView)
            dialog.dismiss()
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val session = ViewModelProvider(activity)[SessionState::class.java]
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
