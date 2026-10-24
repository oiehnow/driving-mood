package kr.urbanmood.integration

import android.Manifest
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kr.urbanmood.MainActivity
import kr.urbanmood.contracts.SceneObservationRuntime
import kr.urbanmood.contracts.FrameInputSource
import kr.urbanmood.recommendation.SceneMoodScorer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native camera/codec smoke tests. Run only on a dedicated test device or emulator.
 * Uses AndroidX ActivityScenario (android/android-test, Apache-2.0); no copied sample code.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class RecordedFlowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val output by lazy { File(context.getExternalFilesDir(null), "native-check-${System.currentTimeMillis()}").apply { mkdirs() } }

    @Before fun permissions() {
        assertEquals("1", android.system.Os.getenv("ORT_DISABLE_TELEMETRY"))
        listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
    }

    @Test fun recordThirtySecondsDecodeAndCommit() = complete(30_000)
    @Test fun recordSixtySecondsDecodeAndCommit() = complete(60_000)

    private fun complete(duration: Long) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var state: SessionState? = null
            lateinit var runningActivity: MainActivity
            scenario.onActivity { activity ->
                runningActivity = activity
                state = field(activity, "session")
                if (duration == 60_000L) {
                    field<Button>(activity, "durationButton").performClick()
                    val choices = WindowInspector.getGlobalWindowViews().firstNotNullOf { findList(it) }
                    choices.performItemClick(choices.getChildAt(1), 1, choices.getItemIdAtPosition(1))
                }
                assertEquals(duration, state!!.recordingDurationMs)
                assertEquals("촬영 ${duration / 1000}초", field<Button>(activity, "durationButton").text.toString())
                field<Button>(activity, "analyzeButton").performClick()
            }
            var sawRecording = false
            var sawAnalysis = false
            var sawDecodedImage = false
            var latestStatus = ""
            var lastStatus = ""
            val start = SystemClock.elapsedRealtime()
            var maxPssKb = 0
            await(270_000, "record/decode/commit $duration") {
                var finished = false
                maxPssKb = maxOf(maxPssKb, android.os.Debug.getPss().toInt())
                var takeFrame = false
                // Continuous decorative animation need not become idle for state polling.
                instrumentation.runOnMainSync {
                    val activity = runningActivity
                    latestStatus = field<TextView>(activity, "cameraStatus").text.toString()
                    if (latestStatus == "촬영 중") sawRecording = true
                    if (latestStatus == "분석 중") {
                        sawAnalysis = true
                        assertEquals(ImageView.ScaleType.FIT_CENTER, field<ImageView>(activity, "decodedPreview").scaleType)
                        val preview = field<ImageView>(activity, "decodedPreview")
                        val bitmap = (preview.drawable as? BitmapDrawable)?.bitmap
                        if (!sawDecodedImage && bitmap != null) {
                            val overlay = field<View>(activity, "evidenceOverlay")
                            assertEquals(preview.width, overlay.width)
                            assertEquals(preview.height, overlay.height)
                            assertTrue(bitmap.width > 0 && bitmap.height > 0)
                            File(output, "$duration-decoded.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            report("DECODED duration=$duration bitmap=${bitmap.width}x${bitmap.height} viewport=${preview.width}x${preview.height} overlay=matching FIT_CENTER")
                            sawDecodedImage = true
                            takeFrame = true
                        }
                    }
                    finished = state!!.activeRequestId == null
                }
                if (takeFrame) screenshot("$duration-decoded-screen")
                if (lastStatus != latestStatus) {
                    report("duration=$duration elapsed=${SystemClock.elapsedRealtime() - start} status=$latestStatus")
                    lastStatus = latestStatus
                    screenshot("${duration}-${SystemClock.elapsedRealtime() - start}")
                }
                finished
            }
            scenario.onActivity {
                val completed = state!!.completedAnalysis
                assertNotNull("No result: $latestStatus", completed)
                val snapshot = completed!!.snapshot
                val window = snapshot.sceneWindow!!
                assertTrue(sawRecording)
                assertTrue(sawAnalysis)
                assertTrue(sawDecodedImage)
                assertTrue(window.durationMs in (duration - 1000)..duration)
                assertTrue("sample count ${window.samples.size}", window.samples.size >= duration / 1000 - 2)
                assertTrue(window.samples.all { sample -> sample.vision.sceneObservations?.runtime == SceneObservationRuntime.ANDROID_RECORDED })
                window.samples.forEach { sample ->
                    val raw = sample.vision.sceneObservations?.semantic?.skyColor
                    assertNotNull("Missing B raw sky statistics at ${sample.elapsedMs}", raw)
                    assertEquals("sky-rgb-v1", raw!!.measurementVersion)
                    assertEquals(FrameInputSource.ANDROID_RECORDED, raw.frame.inputSource)
                    assertEquals(sample.vision.frameTimeEpochMs, raw.frame.capturedAtEpochMs)
                    assertNotNull(raw.frame.mediaPresentationTimeUs)
                    assertEquals(raw.statistics.skyPixCount, raw.statistics.hueSectorCounts.sum() + raw.statistics.achromaticPixCount)
                }
                val pts = window.samples.map { it.vision.sceneObservations!!.semantic!!.skyColor!!.frame.mediaPresentationTimeUs!! }
                assertTrue("Actual decoder PTS must increase", pts.zipWithNext().all { (a, b) -> b > a })
                val expectedVersion = "semantic-contrast-mobile-v1"
                assertTrue(window.samples.all { sample -> sample.vision.sceneObservations?.lightFeatures?.measurementVersion == expectedVersion })
                assertEquals("scene-v2.0+sky-color-v1", completed.recommendation.scoreSummary?.ruleVersion)
                assertFalse(completed.recommendation.scoreSummary!!.notes.any { note -> note.contains("미지원 측정 버전") })
                assertTrue(window.samples.zipWithNext().all { (a,b) -> b.elapsedMs > a.elapsedMs })
                assertTrue(snapshot.analyzedAtEpochMs >= snapshot.observationEndEpochMs!!)
                assertEquals(snapshot.requestId, field<Long?>(state!!, "lastNotifiedId"))
                assertFalse(state!!.claimCompletionSound(snapshot.requestId))
                val common = completed.recommendation.commonMoodKeywords
                assertEquals(SceneMoodScorer().keywords(completed.recommendation.scoreSummary!!), common)
                assertTrue(field<TextView>(it, "moodText").text.contains(
                    "주변 무드: ${common.joinToString(" · ").ifEmpty { "근거 부족" }}"))
                val suggested = LightingPreview.suggestion(completed)
                if (suggested != null) {
                    assertEquals(common, state!!.lightingPreview?.moodKeywords)
                    assertEquals(snapshot.requestId, state!!.lightingPreview?.requestId)
                }
                report("COMMON words=$common lighting=${state!!.lightingPreview?.moodKeywords} skyRawSamples=${pts.size} firstPtsUs=${pts.first()} lastPtsUs=${pts.last()}")
                report("SUCCESS mode=$expectedVersion duration=$duration samples=${window.samples.size} first=${window.samples.first().elapsedMs} last=${window.samples.last().elapsedMs} completionGate=once elapsed=${SystemClock.elapsedRealtime() - start} peakSampledPssKb=$maxPssKb nativeHeapBytes=${android.os.Debug.getNativeHeapAllocatedSize()}")
            }
            await(10_000, "completed video cleanup") { recordings().isEmpty() }
            val beforeRotation = state!!.completedAnalysis
            val lightBeforeRotation = state!!.lightingPreview
            scenario.recreate()
            scenario.onActivity { activity ->
                val restored = field<SessionState>(activity, "session")
                assertSame(beforeRotation, restored.completedAnalysis)
                assertEquals(lightBeforeRotation, restored.lightingPreview)
                assertNull(restored.activeRequestId)
                assertFalse(restored.claimCompletionSound(beforeRotation!!.snapshot.requestId))
                assertTrue(field<Button>(activity, "analyzeButton").isEnabled)
            }
            assertTrue(recordings().isEmpty())
            report("RESTORED duration=$duration same completed snapshot/common mood/light; no new recording or completion cue")
        }
    }

    @Test fun backgroundCancellationDeletesVideoAndDoesNotCommit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var state: SessionState? = null
            scenario.onActivity { activity ->
                state = field(activity, "session")
                field<Button>(activity, "analyzeButton").performClick()
            }
            await(30_000, "recording started") {
                var recording = false
                scenario.onActivity { recording = field<TextView>(it, "cameraStatus").text == "촬영 중" }
                recording && recordings().any { it.length() > 0 }
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            await(15_000, "cancelled video cleanup") { recordings().isEmpty() }
            assertNull(state!!.activeRequestId)
            assertNull(state!!.completedAnalysis)
            assertNull(field<Long?>(state!!, "lastNotifiedId"))
            scenario.moveToState(Lifecycle.State.RESUMED)
            SystemClock.sleep(1500)
            assertNull(state!!.activeRequestId)
            assertTrue(recordings().isEmpty())
            report("SUCCESS background cancellation: no result, no completion gate, no temporary video, no automatic restart")
        }
    }

    @Test fun completionSoundLoadsAndNativeStreamStarts() {
        var cue: CompletionCue? = null
        instrumentation.runOnMainSync { cue = CompletionCue(context) }
        try {
            await(10_000, "SoundPool resource load") {
                var loaded = false
                instrumentation.runOnMainSync { loaded = field(cue!!, "loaded") }
                loaded
            }
            var stream = 0
            instrumentation.runOnMainSync {
                stream = field<SoundPool>(cue!!, "pool").play(field(cue!!, "sound"), .6f, .6f, 1, 0, 1f)
            }
            assertTrue("SoundPool rejected playback", stream > 0)
            SystemClock.sleep(700)
            report("SUCCESS native completion sound stream=$stream (audibility not assessed)")
        } finally { instrumentation.runOnMainSync { cue?.close() } }
    }

    @Test fun cancelDuringDecodeDeletesVideo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var state: SessionState? = null
            scenario.onActivity {
                state = field(it, "session")
                field<Button>(it, "analyzeButton").performClick()
            }
            await(90_000, "decoded preview before cancellation") {
                var decoded = false
                scenario.onActivity { decoded = field<ImageView>(it, "decodedPreview").drawable != null }
                decoded
            }
            assertTrue(recordings().isNotEmpty())
            scenario.onActivity { field<Button>(it, "cancelButton").performClick() }
            await(15_000, "decoder cancellation cleanup") { recordings().isEmpty() }
            assertNull(state!!.activeRequestId)
            assertNull(state!!.completedAnalysis)
            assertNull(field<Long?>(state!!, "lastNotifiedId"))
            report("SUCCESS decoder cancellation: no result, no completion gate, no temporary video")
        }
    }

    private fun recordings() = File(context.cacheDir, "mood-recordings").listFiles()?.filter { it.extension == "mp4" }.orEmpty()
    private fun await(timeout: Long, label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(500)
        }
        fail("Timed out: $label")
    }
    private fun report(message: String) {
        Log.i("RecordedFlowTest", message)
        File(output, "observations.txt").appendText(message + "\n")
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    private fun findList(view: View): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findList(view.getChildAt(index))?.let { return it }
        return null
    }
}
