package kr.urbanmood.integration

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.os.ParcelFileDescriptor
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
import kr.urbanmood.ui.LogoJourneyView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Native UI-stage walkthrough. Saved result ledger, no camera capture/model benchmark. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class JourneyCaptureDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val frames = JSONArray()
    private val out by lazy { File(instrument.targetContext.getExternalFilesDir(null),
        "journey-review-${System.currentTimeMillis()}").apply { mkdirs() } }

    @Test fun recordIdleAnalysisResultAndMenusWithNativeSoundAndMotionChecks() {
        val context = instrument.targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("0.3.8", version.versionName)
        assertEquals(16L, version.longVersionCode)
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val activity = awaitActivity()
        var audioStream = 0
        try {
            await { field<Boolean>(field<Any>(activity, "clickSound"), "loaded") }
            main {
                field<View>(activity, "settingsShortcut").performClick()
                audioStream = field(field<Any>(activity, "clickSound"), "streamId")
                assertTrue("SoundPool did not return a playback stream", audioStream > 0)
                field<List<Button>>(activity, "navigationButtons")[0].performClick()
            }
            await { tagged(activity, "mood-idle-background").isShown }
            main { assertTrue((tagged(activity, "mood-idle-background").background as Animatable).isRunning) }
            main { assertTrue(activity.moveTaskToBack(true)) }
            await { !(tagged(activity, "mood-idle-background").background as Animatable).isRunning }
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            assertSame(activity, awaitActivity())
            await { (tagged(activity, "mood-idle-background").background as Animatable).isRunning }

            val saved = loadSaved()
            val movie = File(out, "phone-walkthrough-original.mp4")
            val pipe = instrument.uiAutomation.executeShellCommand("screenrecord --time-limit 40 --bit-rate 6000000 ${movie.absolutePath}")
            val timelineStart = SystemClock.elapsedRealtime()
            fun until(milliseconds: Long) { SystemClock.sleep((timelineStart + milliseconds - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
            var changed = 0
            ParcelFileDescriptor.AutoCloseInputStream(pipe).use { recording ->
                main { field<LogoJourneyView>(activity, "logoJourney").showIntro() }
                until(600); capture("01-start-center")
                until(3_700); val first = screenshot()
                save(first, "02-idle-a")
                until(6_700); val second = screenshot()
                save(second, "03-idle-b")
                try {
                    // Fixed background region above the stationary logo; no clock or controls.
                    for (y in first.height / 4 until first.height / 2 step 24) {
                        for (x in first.width / 8 until first.width * 7 / 8 step 24) {
                            val a = first.getPixel(x, y); val b = second.getPixel(x, y)
                            val difference = abs((a shr 16 and 255) - (b shr 16 and 255)) +
                                abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
                            if (difference > 9) changed++
                        }
                    }
                    assertTrue("Idle background did not visibly change", changed > 100)
                } finally { first.recycle(); second.recycle() }
                main { MainActivity::class.java.getDeclaredMethod("beginLogoAnalysis").apply { isAccessible = true }.invoke(activity) }
                until(8_200)
                main { field<LogoJourneyView>(activity, "logoJourney").recording(10_000, 30_000) }
                until(8_800); capture("04-capture-stage")
                until(10_000)
                main { field<LogoJourneyView>(activity, "logoJourney").recording(24_000, 30_000) }
                until(11_500)
                main { field<LogoJourneyView>(activity, "logoJourney").analyzing(10, 30) }
                until(12_100); capture("05-analysis-stage")
                until(14_000)
                main {
                    field<SessionState>(activity, "session").completedAnalysis = CompletedAnalysis(
                        AnalysisSnapshot(1, 0, EnvironmentSnapshot(generatedAtEpochMs = 0, zoneId = "Asia/Seoul"),
                            VisionResult(0, AnalysisStatus.LOW_CONFIDENCE)), saved, recommendationPreferences = null)
                    MainActivity::class.java.getDeclaredMethod("renderRecommendation", RecommendationResult::class.java)
                        .apply { isAccessible = true }.invoke(activity, saved)
                    field<TextView>(activity, "cameraStatus").text = "노을 드라이브 · 저장된 관측 결과"
                }
                until(16_100); capture("06-result")
                SystemClock.sleep(2_000)
                main {
                    views(activity.window.decorView).filterIsInstance<Button>()
                        .single { it.contentDescription == "분석 상세 펼치기 또는 접기" }.performClick()
                }
                await { tagged(activity, "evidence_detail").isShown && !tagged(activity, "evidence_detail").isLayoutRequested }
                main { scrollTo(activity, tagged(activity, "evidence_detail")) }
                SystemClock.sleep(2_000); capture("07-detail-colors")
                main { scrollTo(activity, tagged(activity, "evidence_metric_feature_area")) }
                SystemClock.sleep(2_000); capture("08-detail-scene")
                main { tagged(activity, "open_music").performClick() }
                SystemClock.sleep(2_000); capture("09-music")
                main { val button = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "추천 이유" }; scrollTo(activity, button); button.performClick() }
                SystemClock.sleep(2_000); capture("10-recommendation")
                main {
                    WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<Button>()
                        .single { it.isShown && it.text == "닫기" }.performClick()
                    val preset = views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "앰버" }
                    scrollTo(activity, preset)
                    preset.performClick()
                }
                SystemClock.sleep(2_000); capture("11-lighting-preview")
                main {
                    field<List<Button>>(activity, "navigationButtons")[2].performClick()
                }
                SystemClock.sleep(2_000); capture("12-settings")
                main { assertNull(field<SessionState>(activity, "session").activeRequestId) }
                recording.bufferedReader().readText()
            }
            assertTrue(movie.length() > 4096)
            File(out, "capture-info.json").writeText(JSONObject().put("appVersion", version.versionName)
                .put("versionCode", version.longVersionCode)
                .put("captureType", "Native UI-stage walkthrough with saved actual driving ledger; no camera/model inference")
                .put("sourceVideoId", "full-01").put("sourceLedgerSha256", JSONObject(instrument.context.assets.open("ui-review/full-01.json").bufferedReader().use { it.readText() }).getString("sourceLedgerSha256"))
                .put("idleBackgroundChangedSamples", changed).put("backgroundStopResumeVerified", true)
                .put("clickSoundLoaded", true).put("clickSoundReturnedStreamId", audioStream)
                .put("soundAudiblyVerified", false).put("frames", frames).toString(2))
        } finally { main { activity.finish() } }
    }

    private fun loadSaved(): RecommendationResult {
        val raw = JSONObject(instrument.context.assets.open("ui-review/full-01.json").bufferedReader().use { it.readText() })
        val data = raw.getJSONObject("scoreSummary")
        val scores = data.getJSONArray("scores").let { a -> (0 until a.length()).map { n ->
            val score = a.getJSONObject(n)
            val contributions = score.getJSONArray("contributions").let { c -> (0 until c.length()).map { i ->
                val item = c.getJSONObject(i)
                MoodScoreContribution(item.getString("id"), item.getString("label"),
                    observedValue = if (item.isNull("observedValue")) null else item.getDouble("observedValue").toFloat(),
                    unit = if (item.isNull("unit")) null else item.getString("unit"),
                    weight = if (item.isNull("weight")) 0f else item.getDouble("weight").toFloat(),
                    delta = if (item.isNull("delta")) null else item.getDouble("delta").toFloat(),
                    reason = item.getString("reason"), basis = ContributionBasis.valueOf(item.getString("basis")))
            } }
            MoodScore(score.getString("keyword"), if (score.isNull("score")) null else score.getDouble("score").toFloat(), contributions)
        } }
        val words = raw.getJSONArray("commonMoodKeywords").let { a -> (0 until a.length()).map(a::getString) }
        return RecommendationResult(Availability.AVAILABLE, moodKeywords = words, commonMoodKeywords = words,
            scoreSummary = MoodScoreSummary(data.getString("ruleVersion"), data.getLong("windowDurationMs"),
                data.getInt("receivedFrameCount"), data.getInt("sampledBucketCount"), scores))
    }
    private fun awaitActivity(): MainActivity {
        var activity: MainActivity? = null
        await { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull(); activity != null }
        return requireNotNull(activity)
    }
    private fun await(condition: () -> Boolean) {
        val limit = SystemClock.elapsedRealtime() + 12_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < limit) { main { ready = condition() }; if (!ready) SystemClock.sleep(50) }
        assertTrue("Finite UI state did not become ready", ready)
    }
    private fun main(action: () -> Unit) = instrument.runOnMainSync(action)
    private fun screenshot() = requireNotNull(instrument.uiAutomation.takeScreenshot())
    private fun capture(name: String) { val image = screenshot(); try { save(image, name) } finally { image.recycle() } }
    private fun save(image: Bitmap, name: String) {
        File(out, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        frames.put(JSONObject().put("file", "$name.png").put("capturedAtEpochMs", System.currentTimeMillis()))
    }
    private fun tagged(activity: MainActivity, tag: String) = views(activity.window.decorView).single { it.tag == tag }
    private fun scrollTo(activity: MainActivity, target: View) {
        val scroll = views(activity.window.decorView).filterIsInstance<ScrollView>().first { it.isShown }
        val position = IntArray(2); val origin = IntArray(2)
        target.getLocationOnScreen(position); scroll.getLocationOnScreen(origin)
        scroll.scrollBy(0, position[1] - origin[1] - 20)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Suppress("UNCHECKED_CAST") private fun <T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
}
