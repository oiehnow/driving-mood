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
    @Test fun captureMusicIconControls() {
        // Do not wait for a globally idle queue while the app deliberately animates continuously.
        instrumentation.targetContext.startActivity(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(3_500)
        lateinit var activity: MainActivity
        instrumentation.runOnMainSync {
            activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
        }
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("renderRecommendation", RecommendationResult::class.java)
                    .apply { isAccessible = true }.invoke(activity,
                        RecommendationResult(Availability.AVAILABLE, commonMoodKeywords = listOf("따뜻한")))
            }
            SystemClock.sleep(2_100)
            instrumentation.runOnMainSync {
                click(activity, "음악·조명")
                val controls = views(activity.window.decorView).filterIsInstance<android.widget.ImageButton>()
                    .filter { it.contentDescription?.toString() in setOf("재생", "일시정지", "다음") }
                assertEquals(3, controls.size)
                controls.forEach {
                    assertNotNull(it.drawable)
                    assertTrue(it.minimumHeight >= 48 * activity.resources.displayMetrics.density)
                }
                assertFalse(views(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.isShown && it.text.toString() in setOf("재생", "일시정지", "다음") })
            }
            SystemClock.sleep(800)
            instrumentation.runOnMainSync {
                val scroll = views(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().first { it.isShown }
                val play = views(activity.window.decorView).filterIsInstance<android.widget.ImageButton>()
                    .single { it.contentDescription == "재생" }
                val buttonPosition = IntArray(2); val scrollPosition = IntArray(2)
                play.getLocationOnScreen(buttonPosition); scroll.getLocationOnScreen(scrollPosition)
                scroll.scrollBy(0, buttonPosition[1] - scrollPosition[1] - (180 * activity.resources.displayMetrics.density).toInt())
            }
            SystemClock.sleep(400)
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "music-icon-review").apply { mkdirs() }
            try { File(output, "music.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    /** Fixed UI palette fixture; captures native animation, not a new camera/model analysis. */
    @Test fun captureCoolGradientMotionPreview() {
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "cool-gradient-review").apply { mkdirs() }
        val result = RecommendationResult(Availability.AVAILABLE, moodKeywords = listOf("청량한"),
            commonMoodKeywords = listOf("청량한"))
        val timings = org.json.JSONArray()
        val backgroundSamples = mutableListOf<IntArray>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            lateinit var dashboard: kr.urbanmood.ui.MoodDashboardView
            lateinit var background: android.graphics.drawable.Animatable
            var stableText = ""
            val heroBounds = android.graphics.Rect()
            scenario.onActivity {
                activity = it
                MainActivity::class.java.getDeclaredMethod("renderRecommendation", RecommendationResult::class.java)
                    .apply { isAccessible = true }.invoke(it, result)
                field<TextView>(it, "cameraStatus").text = "팔레트 UI 검토 · 예시"
                field<TextView>(it, "frameStatus").visibility = View.GONE
                dashboard = views(it.window.decorView).filterIsInstance<kr.urbanmood.ui.MoodDashboardView>().single()
                background = dashboard.getChildAt(0).background as android.graphics.drawable.Animatable
            }
            // Finish the logo/result reveal and text transitions before comparing fixed content.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(2_100)
            fun visibleText() = views(activity.window.decorView).filterIsInstance<TextView>()
                .filter { it.isShown }.joinToString("\n") { it.text.toString() }
            instrumentation.runOnMainSync {
                assertTrue("Native gradient animator did not start", background.isRunning)
                assertEquals(View.GONE, field<View>(activity, "logoJourney").visibility)
                assertNull(field<SessionState>(activity, "session").activeRequestId)
                stableText = visibleText()
                assertTrue(stableText.contains("청량한"))
                val hero = dashboard.getChildAt(0)
                val location = IntArray(2)
                hero.getLocationOnScreen(location)
                heroBounds.set(location[0], location[1], location[0] + hero.width, location[1] + hero.height)
                assertTrue(heroBounds.width() > 0 && heroBounds.height() > 0)
            }
            val video = File(output, "cool-gradient-native.mp4")
            val recordingStartedAt = System.currentTimeMillis()
            val recording = instrumentation.uiAutomation.executeShellCommand("screenrecord --time-limit 15 ${video.absolutePath}")
            var recordingLog = ""
            android.os.ParcelFileDescriptor.AutoCloseInputStream(recording).use { commandOutput ->
                try {
                    SystemClock.sleep(500)
                    val timelineStartedAt = SystemClock.elapsedRealtime()
                    for (seconds in listOf(0, 3, 6)) {
                        SystemClock.sleep((timelineStartedAt + seconds * 1000L - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        instrumentation.runOnMainSync {
                            assertTrue("Gradient stopped at ${seconds}s", background.isRunning)
                            assertEquals("Fixture text changed during capture", stableText, visibleText())
                        }
                        val capturedAt = SystemClock.elapsedRealtime() - timelineStartedAt
                        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            File(output, "cool-%02ds.png".format(seconds)).outputStream().use {
                                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                            // Sample the clear upper area of the hero, away from text and the metric strip.
                            backgroundSamples += IntArray(60) { index ->
                                val x = heroBounds.left + (heroBounds.width() * (.2f + (index % 12) * .6f / 11)).toInt()
                                val y = heroBounds.top + (heroBounds.height() * (.14f + (index / 12) * .1f / 4)).toInt()
                                assertTrue("Hero sample outside screenshot", x in 0 until image.width && y in 0 until image.height)
                                image.getPixel(x, y)
                            }
                            timings.put(JSONObject().put("requestedSeconds", seconds).put("capturedElapsedMs", capturedAt))
                        } finally { image.recycle() }
                    }
                    fun changedPixels(first: IntArray, next: IntArray) = first.indices.count { index ->
                        val a = first[index]; val b = next[index]
                        kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 12
                    }
                    assertTrue("Animator ran but rendered background stayed unchanged at 3s",
                        changedPixels(backgroundSamples[0], backgroundSamples[1]) >= 10)
                    assertTrue("Rendered background stayed unchanged from 3s to 6s",
                        changedPixels(backgroundSamples[1], backgroundSamples[2]) >= 10)
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { assertFalse("Gradient kept running while stopped", background.isRunning) }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.runOnMainSync {
                        assertTrue("Gradient did not resume with its window", background.isRunning)
                        assertEquals(stableText, visibleText())
                        assertNull(field<SessionState>(activity, "session").activeRequestId)
                    }
                } finally {
                    // Allow the bounded recorder to finalize the MP4, including when an assertion fails.
                    recordingLog = commandOutput.bufferedReader().readText()
                }
            }
            assertTrue("Native recording missing or stale: $recordingLog",
                video.length() > 4096 && video.lastModified() >= recordingStartedAt - 2000)
            File(output, "capture-info.json").writeText(JSONObject()
                .put("fixtureType", "UI palette fixture; not new analysis data")
                .put("keyword", "청량한").put("nativeScreenrecordSeconds", 15)
                .put("frames", timings).put("backgroundPixelsChanged", true)
                .put("lifecycleStopResumeVerified", true).toString(2))
        }
    }
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
