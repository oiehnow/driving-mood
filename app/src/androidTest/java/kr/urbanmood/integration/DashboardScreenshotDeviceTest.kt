package kr.urbanmood.integration

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.urbanmood.MainActivity
import kr.urbanmood.contracts.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Screenshot replay of saved, real-video scores. Fixtures are only in the test APK. */
@RunWith(AndroidJUnit4::class)
class DashboardScreenshotDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    /** Edited state walkthrough, not a timed camera benchmark. Uses genuine stored result scores. */
    @Test fun captureAnalysisMotionPreview() {
        val raw = JSONObject(instrumentation.context.assets.open("ui-review/full-01.json").bufferedReader().use { it.readText() })
        val summary = raw.getJSONObject("scoreSummary")
        val scores = summary.getJSONArray("scores").let { a -> (0 until a.length()).map { index ->
            val item = a.getJSONObject(index)
            val contributions = item.getJSONArray("contributions").let { c -> (0 until c.length()).map { n ->
                val entry = c.getJSONObject(n)
                MoodScoreContribution(entry.getString("id"), entry.getString("label"),
                    delta = if (entry.isNull("delta")) null else entry.getDouble("delta").toFloat(),
                    reason = entry.getString("reason"), basis = ContributionBasis.valueOf(entry.getString("basis")))
            } }
            MoodScore(item.getString("keyword"), if (item.isNull("score")) null else item.getDouble("score").toFloat(), contributions)
        } }
        val words = raw.getJSONArray("commonMoodKeywords").let { a -> (0 until a.length()).map { a.getString(it) } }
        val result = RecommendationResult(Availability.AVAILABLE, moodKeywords = words, commonMoodKeywords = words,
            scoreSummary = MoodScoreSummary(summary.getString("ruleVersion"), summary.getLong("windowDurationMs"),
                summary.getInt("receivedFrameCount"), summary.getInt("sampledBucketCount"), scores))
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "analysis-motion").apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var journey: kr.urbanmood.ui.LogoJourneyView
            lateinit var previewActivity: MainActivity
            scenario.onActivity { activity ->
                previewActivity = activity
                journey = field(activity, "logoJourney")
            }
            val begin = MainActivity::class.java.getDeclaredMethod("beginLogoAnalysis").apply { isAccessible = true }
            val complete = MainActivity::class.java.getDeclaredMethod("renderRecommendation", RecommendationResult::class.java)
                .apply { isAccessible = true }
            fun showSavedResult() {
                field<TextView>(previewActivity, "cameraStatus").text = raw.getString("sceneTitle") + " · 저장된 관측 결과"
                complete.invoke(previewActivity, result)
            }
            val video = File(output, "logo-journey.mp4")
            val recordingStartedAt = System.currentTimeMillis()
            val recording = instrumentation.uiAutomation.executeShellCommand(
                "screenrecord --time-limit 30 ${video.absolutePath}")
            var recordingLog = ""
            android.os.ParcelFileDescriptor.AutoCloseInputStream(recording).use { commandOutput ->
                try {
                    SystemClock.sleep(500)
                    val timelineStartedAt = SystemClock.elapsedRealtime()
                    val stillFrames = setOf(0, 45, 80, 120, 170, 200)
                    for (frame in 0 until 220) {
                        instrumentation.runOnMainSync {
                            when {
                                frame == 0 -> journey.showIntro()
                                frame == 50 -> begin.invoke(previewActivity)
                                frame in 65..95 -> journey.recording((frame - 65) * 1_000L, 30_000)
                                frame == 96 -> journey.analyzing()
                                frame in 100..135 -> journey.analyzing(((frame - 100) * 30L / 35 + 1).coerceAtMost(30), 30)
                                frame == 140 -> showSavedResult()
                                frame == 190 -> begin.invoke(previewActivity)
                                frame in 205..219 -> journey.recording((frame - 205) * 1_000L, 30_000)
                            }
                        }
                        // Screenrecord preserves native motion timing; stills never define video playback speed.
                        val nextFrameAt = timelineStartedAt + (frame + 1) * 100L
                        SystemClock.sleep((nextFrameAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        if (frame in stillFrames) {
                            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                            try {
                                File(output, "frame-%03d.png".format(frame)).outputStream().use {
                                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                            } finally { image.recycle() }
                        }
                    }
                    instrumentation.runOnMainSync { showSavedResult() }
                    SystemClock.sleep(2_100)
                    instrumentation.runOnMainSync {
                        val visibleText = views(previewActivity.window.decorView).filterIsInstance<TextView>()
                            .filter { it.isShown }.joinToString("\n") { it.text.toString() }
                        words.forEach { word -> assertTrue("Saved result keyword not visible: $word", visibleText.contains(word)) }
                        assertEquals(View.GONE, journey.visibility)
                        assertEquals(View.VISIBLE, field<View>(previewActivity, "headerLogo").visibility)
                        assertNull(field<SessionState>(previewActivity, "session").activeRequestId)
                    }
                } finally {
                    // Keep the shell pipe open until the bounded recorder exits and finalizes its MP4.
                    recordingLog = commandOutput.bufferedReader().readText()
                }
            }
            assertTrue("Native screen recording missing or stale: $recordingLog",
                video.length() > 4_096 && video.lastModified() >= recordingStartedAt - 2_000)
        }
    }
    @Test fun captureAppAndSavedDrivingResults() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            capture("01-home")
            scenario.onActivity { activity ->
                val dashboard = views(activity.window.decorView).filterIsInstance<kr.urbanmood.ui.MoodDashboardView>().single()
                assertTrue((dashboard.getChildAt(0).background as android.graphics.drawable.Animatable).isRunning)
            }
            repeat(12) { capture("idle-motion-%02d".format(it)) }
            for (id in listOf("full-01", "full-02")) {
                val json = JSONObject(instrumentation.context.assets.open("ui-review/$id.json").bufferedReader().use { it.readText() })
                val words = json.getJSONArray("commonMoodKeywords").let { a -> (0 until a.length()).map { a.getString(it) } }
                val raw = json.getJSONObject("scoreSummary")
                val scores = raw.getJSONArray("scores").let { a -> (0 until a.length()).map { n ->
                    val score = a.getJSONObject(n)
                    val contributions = score.getJSONArray("contributions").let { c -> (0 until c.length()).map { i ->
                        val item = c.getJSONObject(i)
                        MoodScoreContribution(item.getString("id"), item.getString("label"),
                            delta = if (item.isNull("delta")) null else item.getDouble("delta").toFloat(),
                            reason = item.getString("reason"), basis = ContributionBasis.valueOf(item.getString("basis")))
                    } }
                    MoodScore(score.getString("keyword"), if (score.isNull("score")) null else score.getDouble("score").toFloat(), contributions)
                } }
                val summary = MoodScoreSummary(raw.getString("ruleVersion"), raw.getLong("windowDurationMs"),
                    raw.getInt("receivedFrameCount"), raw.getInt("sampledBucketCount"), scores)
                val result = RecommendationResult(Availability.AVAILABLE, moodKeywords = words,
                    scoreSummary = summary, commonMoodKeywords = words)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("renderRecommendation", RecommendationResult::class.java)
                        .apply { isAccessible = true }.invoke(activity, result)
                    field<TextView>(activity, "cameraStatus").text = json.getString("sceneTitle") + " · 저장된 관측 결과"
                    field<TextView>(activity, "frameStatus").visibility = View.GONE
                    val session = field<SessionState>(activity, "session")
                    session.lightingPreview = LightingPreview.suggestion(CompletedAnalysis(
                        AnalysisSnapshot(1, 0, EnvironmentSnapshot(), VisionResult(0, AnalysisStatus.LOW_CONFIDENCE)), result))
                    MainActivity::class.java.getDeclaredMethod("renderLighting").apply { isAccessible = true }.invoke(activity)
                    assertNull(session.activeRequestId)
                }
                capture(if (id == "full-01") "02-warm-result" else "03-city-result")
                if (id == "full-01") repeat(12) { capture("warm-motion-%02d".format(it)) }
            }
            scenario.onActivity { activity ->
                click(activity, "음악·조명")
                val dashboard = views(activity.window.decorView).filterIsInstance<kr.urbanmood.ui.MoodDashboardView>().single()
                assertFalse((dashboard.getChildAt(0).background as android.graphics.drawable.Animatable).isRunning)
            }
            capture("04-music")
            scenario.onActivity { activity ->
                views(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().first { it.isShown }.fullScroll(View.FOCUS_DOWN)
            }
            capture("05-lighting")
            scenario.onActivity { click(it, "설정") }
            capture("06-settings")
            scenario.onActivity { activity ->
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "제작자 oieh" })
                assertNotNull(activity.packageManager.getApplicationIcon(activity.packageName))
            }
        }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(800)
        val out = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review-033").apply { mkdirs() }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(out, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
    private fun click(activity: MainActivity, text: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == text }.performClick()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
}
