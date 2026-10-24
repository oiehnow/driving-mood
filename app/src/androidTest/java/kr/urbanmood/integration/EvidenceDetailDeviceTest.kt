package kr.urbanmood.integration

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kr.urbanmood.MainActivity
import kr.urbanmood.contracts.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Native UI replay of preserved real-video scores; does not perform new camera/model inference. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class EvidenceDetailDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val output by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null),
            "evidence-detail-review-${System.currentTimeMillis()}").apply { mkdirs() }
    }
    private val captures = JSONArray()

    private data class SavedResult(val id: String, val raw: JSONObject, val result: RecommendationResult)

    @Test fun savedDrivingResultsShowVisualEvidenceAndRecommendationConnection() {
        val context = instrumentation.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("0.3.7", info.versionName)
        assertEquals(15L, info.longVersionCode)
        val activity = launchActivity()
        try {
            instrumentation.runOnMainSync {
                assertNull(field<SessionState>(activity, "session").activeRequestId)
                assertEquals(View.VISIBLE, field<View>(activity, "logoJourney").visibility)
                assertEquals(View.GONE, field<View>(activity, "detailPanel").visibility)
            }
            capture(activity, "01-start")
            for (id in listOf("full-01", "full-02")) {
                val saved = loadSavedResult(id)
                render(activity, saved)
                capture(activity, "$id-result", saved)
                instrumentation.runOnMainSync {
                    val panel = field<View>(activity, "detailPanel")
                    if (panel.visibility != View.VISIBLE) {
                        views(activity.window.decorView).filterIsInstance<Button>()
                            .single { it.contentDescription == "분석 상세 펼치기 또는 접기" }.performClick()
                    }
                    assertTrue(tagged(activity, "evidence_detail").isShown)
                    assertEquals(View.GONE, tagged(activity, "evidence_technical_body").visibility)
                    // Graph values must come from the stored contributions, not from an invented example.
                    val contributions = saved.result.scoreSummary!!.scores.flatMap { it.contributions }
                    for (metricId in listOf("sky_color_warm_strength", "sky_color_cool_strength", "feature_warm", "feature_cool")) {
                        val expected = contributions.first { it.id == metricId }.delta!!
                        val number = if (expected > 0f && expected < .1f) "<0.1" else String.format(Locale.KOREA, "%.1f", expected)
                        val meter = tagged(activity, "evidence_metric_$metricId")
                        assertFalse("Evidence should include a native graphic, not just text", meter is TextView)
                        assertTrue("Wrong stored value for $metricId: ${meter.contentDescription}",
                            meter.contentDescription.toString().contains(number))
                    }
                    // Restored observations retain the exact source-ledger values and hash.
                    for (metricId in listOf("feature_area", "interpret_visual", "interpret_people_light")) {
                        val expected = contributions.first { it.id == metricId }.observedValue!! * 100f
                        val number = if (expected > 0f && expected < .1f) "<0.1" else String.format(Locale.KOREA, "%.1f", expected)
                        assertTrue(tagged(activity, "evidence_metric_$metricId").contentDescription.toString().contains("$number%"))
                    }
                    assertFalse(views(tagged(activity, "evidence_detail")).filterIsInstance<TextView>().any { it.text == "0개" })
                }
                awaitLayout(taggedOnMain(activity, "evidence_detail"))
                instrumentation.runOnMainSync { scrollTo(activity, tagged(activity, "evidence_detail")) }
                capture(activity, "$id-evidence-top", saved)
                instrumentation.runOnMainSync {
                    assertOnScreen(tagged(activity, "evidence_metric_sky_color_warm_strength"))
                    val observed = tagged(activity, "evidence_observed_moods")
                    val observedText = views(observed).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }
                    val musicText = (tagged(activity, "evidence_music_moods") as TextView).text.toString()
                    saved.result.commonMoodKeywords.forEach { assertTrue(observedText.contains(it)) }
                    saved.result.moodKeywords.forEach { assertTrue(musicText.contains(it)) }
                }
                for ((heading, fileSuffix) in listOf("밝기와 사람" to "evidence-scene", "촬영 정보" to "evidence-context")) {
                    instrumentation.runOnMainSync {
                        scrollTo(activity, views(tagged(activity, "evidence_detail")).filterIsInstance<TextView>()
                            .single { it.text == heading })
                    }
                    capture(activity, "$id-$fileSuffix", saved)
                }
                instrumentation.runOnMainSync {
                    scrollTo(activity, tagged(activity, "evidence_music_moods"))
                }
                capture(activity, "$id-evidence-mood-connection", saved)
                instrumentation.runOnMainSync {
                    assertOnScreen(tagged(activity, "evidence_music_moods"))
                    val toggle = tagged(activity, "evidence_technical_toggle")
                    toggle.performClick()
                    assertTrue(tagged(activity, "evidence_technical_body").isShown)
                    scrollTo(activity, toggle)
                }
                capture(activity, "$id-technical-expanded", saved)
                instrumentation.runOnMainSync {
                    tagged(activity, "evidence_technical_toggle").performClick()
                    assertEquals(View.GONE, tagged(activity, "evidence_technical_body").visibility)
                    // Keep the next result capture at the top with the detail view collapsed.
                    views(activity.window.decorView).filterIsInstance<Button>()
                        .single { it.contentDescription == "분석 상세 펼치기 또는 접기" }.performClick()
                    views(activity.window.decorView).filterIsInstance<ScrollView>().first { it.isShown }.scrollTo(0, 0)
                }
            }
            instrumentation.runOnMainSync {
                tagged(activity, "open_music").performClick()
                assertEquals(1, field<SessionState>(activity, "session").selectedPage)
                assertNull(field<SessionState>(activity, "session").activeRequestId)
            }
            capture(activity, "06-music-page", loadSavedResult("full-02"))
            instrumentation.runOnMainSync {
                scrollTo(activity, button(activity, "추천 이유"))
                button(activity, "추천 이유").performClick()
            }
            SystemClock.sleep(250)
            instrumentation.runOnMainSync {
                val windows = WindowInspector.getGlobalWindowViews().flatMap(::views)
                val diagram = windows.single { it.tag == "evidence_recommendation_detail" }
                assertOnScreen(diagram)
                val observed = views(diagram).single { it.tag == "evidence_observed_moods" }
                val observedText = views(observed).filterIsInstance<TextView>().joinToString(" ") { it.text.toString() }
                val musicText = (views(diagram).single { it.tag == "evidence_music_moods" } as TextView).text.toString()
                val saved = loadSavedResult("full-02")
                saved.result.commonMoodKeywords.forEach { assertTrue(observedText.contains(it)) }
                saved.result.moodKeywords.forEach { assertTrue(musicText.contains(it)) }
                assertEquals(View.GONE, windows.single { it.tag == "evidence_recommendation_technical_body" }.visibility)
            }
            capture(activity, "07-recommendation-connection", loadSavedResult("full-02"))
            instrumentation.runOnMainSync {
                WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<Button>()
                    .single { it.isShown && it.text == "닫기" }.performClick()
                field<List<Button>>(activity, "navigationButtons")[2].performClick()
                assertEquals(2, field<SessionState>(activity, "session").selectedPage)
                assertNull(field<SessionState>(activity, "session").activeRequestId)
            }
            capture(activity, "08-settings-page")
            File(output, "capture-info.json").writeText(JSONObject()
                .put("appVersion", info.versionName).put("versionCode", info.longVersionCode)
                .put("input", "Saved actual-video score replay; no new camera or model inference")
                .put("sourceLedgerObservationsVerified", true)
                .put("technicalDetailsInitiallyCollapsed", true)
                .put("recommendationNavigationVerified", true)
                .put("captures", captures).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun loadSavedResult(id: String): SavedResult {
        val raw = JSONObject(instrumentation.context.assets.open("ui-review/$id.json")
            .bufferedReader().use { it.readText() })
        val data = raw.getJSONObject("scoreSummary")
        val scores = data.getJSONArray("scores").let { rows -> (0 until rows.length()).map { n ->
            val score = rows.getJSONObject(n)
            val contributions = score.getJSONArray("contributions").let { entries ->
                (0 until entries.length()).map { i ->
                    val item = entries.getJSONObject(i)
                    MoodScoreContribution(item.getString("id"), item.getString("label"),
                        observedValue = if (item.isNull("observedValue")) null else item.getDouble("observedValue").toFloat(),
                        unit = if (item.isNull("unit")) null else item.getString("unit"),
                        weight = if (item.isNull("weight")) 0f else item.getDouble("weight").toFloat(),
                        delta = if (item.isNull("delta")) null else item.getDouble("delta").toFloat(),
                        reason = item.getString("reason"), basis = ContributionBasis.valueOf(item.getString("basis")))
                }
            }
            MoodScore(score.getString("keyword"),
                if (score.isNull("score")) null else score.getDouble("score").toFloat(), contributions)
        } }
        val words = raw.getJSONArray("commonMoodKeywords").let { list ->
            (0 until list.length()).map(list::getString)
        }
        return SavedResult(id, raw, RecommendationResult(Availability.AVAILABLE,
            moodKeywords = words, commonMoodKeywords = words,
            scoreSummary = MoodScoreSummary(data.getString("ruleVersion"), data.getLong("windowDurationMs"),
                data.getInt("receivedFrameCount"), data.getInt("sampledBucketCount"), scores)))
    }

    private fun launchActivity(): MainActivity {
        // Global-idle waits cannot complete while the welcome gradient deliberately redraws.
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val deadline = SystemClock.elapsedRealtime() + 12_000
        var resumed: MainActivity? = null
        while (resumed == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
            }
            if (resumed == null) SystemClock.sleep(100)
        }
        val activity = requireNotNull(resumed) { "MainActivity did not resume within 12 seconds" }
        // showLogoScreen posts showIntro after screen construction; RESUMED can arrive first.
        // Wait for that one finite state/layout change, never for all animations to become idle.
        val introDeadline = SystemClock.elapsedRealtime() + 8_000
        var introReady = false
        while (!introReady && SystemClock.elapsedRealtime() < introDeadline) {
            instrumentation.runOnMainSync {
                val logo = field<View>(activity, "logoJourney")
                introReady = logo.visibility == View.VISIBLE && logo.isLaidOut && logo.width > 0 && logo.height > 0
            }
            if (!introReady) SystemClock.sleep(100)
        }
        assertTrue("Initial logo did not become visible and laid out within 8 seconds", introReady)
        return activity
    }

    private fun render(activity: MainActivity, saved: SavedResult) {
        instrumentation.runOnMainSync {
            // New evidence UI reads the completed result and its captured inputs as one unit.
            // The preserved ledger contains no frame/context/preferences; keep them unknown.
            field<SessionState>(activity, "session").completedAnalysis = CompletedAnalysis(
                AnalysisSnapshot(requestId = saved.id.removePrefix("full-").toLong(), analyzedAtEpochMs = 0,
                    environment = EnvironmentSnapshot(generatedAtEpochMs = 0, zoneId = "Asia/Seoul"),
                    vision = VisionResult(0, AnalysisStatus.LOW_CONFIDENCE)),
                recommendation = saved.result, recommendationPreferences = null)
            MainActivity::class.java.getDeclaredMethod("renderRecommendation", RecommendationResult::class.java)
                .apply { isAccessible = true }.invoke(activity, saved.result)
            field<TextView>(activity, "cameraStatus").text = saved.raw.getString("sceneTitle") + " · 저장된 관측 결과"
            field<TextView>(activity, "frameStatus").visibility = View.GONE
            assertNull(field<SessionState>(activity, "session").activeRequestId)
        }
        // Only the finite result transition is allowed to settle, not the perpetual gradient.
        SystemClock.sleep(2_100)
        instrumentation.runOnMainSync {
            assertEquals(View.GONE, field<View>(activity, "logoJourney").visibility)
            saved.result.commonMoodKeywords.forEach { assertTrue(visibleText(activity).contains(it)) }
        }
    }

    private fun capture(activity: MainActivity, name: String, saved: SavedResult? = null) {
        instrumentation.runOnMainSync { assertFalse(activity.isFinishing) }
        SystemClock.sleep(350)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(output, "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
        captures.put(JSONObject().put("file", "$name.png").put("capturedAtEpochMs", System.currentTimeMillis())
            .put("sourceVideoId", saved?.id ?: JSONObject.NULL)
            .put("sourceLedgerSha256", saved?.raw?.getString("sourceLedgerSha256") ?: JSONObject.NULL))
    }

    private fun scrollTo(activity: MainActivity, target: View) {
        val scroll = views(activity.window.decorView).filterIsInstance<ScrollView>().first { it.isShown }
        val targetPosition = IntArray(2)
        val scrollPosition = IntArray(2)
        target.getLocationOnScreen(targetPosition)
        scroll.getLocationOnScreen(scrollPosition)
        scroll.scrollBy(0, targetPosition[1] - scrollPosition[1] - (20 * activity.resources.displayMetrics.density).toInt())
    }

    private fun taggedOnMain(activity: MainActivity, tag: String): View {
        lateinit var result: View
        instrumentation.runOnMainSync { result = tagged(activity, tag) }
        return result
    }

    private fun awaitLayout(view: View) {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { ready = view.isShown && view.isLaidOut && !view.isLayoutRequested && view.height > 0 }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("Evidence detail did not finish its finite layout pass", ready)
    }

    private fun assertOnScreen(view: View) {
        val bounds = Rect()
        assertTrue("Expected visible ${view.tag ?: view.contentDescription}", view.getGlobalVisibleRect(bounds))
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
    }

    private fun visibleText(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<TextView>()
        .filter { it.isShown }.joinToString("\n") { it.text.toString() }

    private fun button(activity: MainActivity, label: String) = views(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == label }

    private fun tagged(activity: MainActivity, tag: String) = views(activity.window.decorView).single { it.tag == tag }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target) as T
}
