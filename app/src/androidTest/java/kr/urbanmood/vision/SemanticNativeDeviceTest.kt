package kr.urbanmood.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Debug
import android.util.Log
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.urbanmood.contracts.*
import kr.urbanmood.recommendation.SceneMoodScorer
import kr.urbanmood.MainActivity
import kr.urbanmood.camera.EvidenceOverlayView
import kr.urbanmood.camera.FrameReceipt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Preserved RGB fixtures through Android Bitmap/ORT/MediaPipe; not a live road accuracy test. */
@RunWith(AndroidJUnit4::class)
class SemanticNativeDeviceTest {
    @Test fun actualRgbFixturesAndRotatedPortraitUseNativeModelsAndC() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("1", android.system.Os.getenv("ORT_DISABLE_TELEMETRY"))
        assertEquals("1", System.getenv("ORT_DISABLE_TELEMETRY"))
        val folder = File(context.getExternalFilesDir(null), "semantic-fixtures-${System.currentTimeMillis()}").apply { mkdirs() }
        val candidate = SemanticLightAnalyzer(context) { OrtSemanticFrameModel(context) }
        val accepted = UrbanLightAnalyzer(context)
        val rows = JSONArray()
        var sequence = 0L
        try {
            for (name in listOf("clip-03-000400", "clip-03-000899", "clip-04-000250")) {
                val bytes = instrumentation.context.assets.open("scene-fixtures/$name.rgb").use { it.readBytes() }
                assertEquals(640 * 360 * 3, bytes.size)
                val pixels = IntArray(640 * 360) { index ->
                    val at = index * 3
                    Color.rgb(bytes[at].toInt() and 255, bytes[at+1].toInt() and 255, bytes[at+2].toInt() and 255)
                }
                val raw = Bitmap.createBitmap(pixels, 640, 360, Bitmap.Config.ARGB_8888)
                try {
                    for (portrait in listOf(false, true)) {
                        val bitmap = if (portrait) Bitmap.createBitmap(raw, 0, 0, 640, 360, Matrix().apply { postRotate(90f) }, false) else raw
                        try {
                            for ((mode, analyzer) in listOf("accepted" to accepted, "semantic" to candidate)) {
                                val now = System.currentTimeMillis()
                                val value = analyzer.analyze(CameraFrame(bitmap, now, System.nanoTime(), ++sequence))
                                val feature = value.sceneObservations?.lightFeatures
                                assertNotNull("$name $mode ${value.status}: ${value.reasons}", feature)
                                assertEquals(if(mode == "semantic") "semantic-contrast-mobile-v1" else "accepted-light-rgb-v1", feature!!.measurementVersion)
                                assertTrue(feature.rasterWidth > 0 && feature.rasterHeight > 0)
                                if (mode == "semantic") {
                                    assertEquals(if(portrait) 180 else 320, feature.rasterWidth)
                                    assertEquals(if(portrait) 320 else 180, feature.rasterHeight)
                                    assertNull(feature.quality.heuristicQuality)
                                }
                                assertEquals(feature.summary.pixelCount, feature.groups!!.sumOf { it.pixels.pixelCount })
                                val score = SceneMoodScorer().score(SceneWindow(1000, receivedFrameCount=1, samples=listOf(SceneSample(0, value))), null, now)
                                assertEquals("scene-v2.0", score.ruleVersion)
                                assertFalse(score.notes.any { it.contains("미지원 측정 버전") })
                                val row = JSONObject().put("fixture", name).put("portraitRotated", portrait).put("mode", mode)
                                    .put("input", "preserved RGB fixture executed on Android; not live road capture")
                                    .put("width", bitmap.width).put("height", bitmap.height).put("rasterWidth", feature.rasterWidth).put("rasterHeight", feature.rasterHeight)
                                    .put("pixels", feature.summary.pixelCount).put("categories", JSONArray(feature.summary.categoryCounts))
                                    .put("redYellowSaturationSum", feature.summary.redYellowSaturationSum).put("cyanBlueSaturationSum", feature.summary.cyanBlueSaturationSum)
                                    .put("regions", feature.groups.size).put("processingMs", value.processingTimeMs)
                                    .put("pssKb", Debug.getPss()).put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                                    .put("scores", JSONArray(score.scores.map { JSONObject().put("keyword", it.keyword).put("value", it.score ?: JSONObject.NULL) }))
                                rows.put(row)
                                File(folder, "results.json").writeText(rows.toString(2))
                                val view = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                try {
                                    val canvas = Canvas(view)
                                    val pen = Paint().apply { color = Color.GREEN; style=Paint.Style.STROKE; strokeWidth=2f }
                                    value.sceneObservations!!.regions.filter { it.kind == RegionEvidenceKind.AMBIENT_LIGHT }.forEach {
                                        canvas.drawRect(it.left*view.width, it.top*view.height, it.right*view.width, it.bottom*view.height, pen)
                                    }
                                    File(folder, "$name-$portrait-$mode.png").outputStream().use { view.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                } finally { view.recycle() }
                                if(name == "clip-03-000400" && !portrait) {

                                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                                        scenario.onActivity { activity ->
                                            field<View>(activity,"previewContainer").visibility=View.VISIBLE
                                            field<View>(activity,"preview").visibility=View.GONE
                                            field<ImageView>(activity,"decodedPreview").apply { visibility=View.VISIBLE;setImageBitmap(bitmap) }
                                            field<TextView>(activity,"cameraStatus").text="보존 RGB 비교 · ${if(mode == "semantic") "의미분할" else "기본"}"
                                            field<TextView>(activity,"frameStatus").text="clip03 / frame400 · Android 네이티브 추론"
                                            MainActivity::class.java.getDeclaredMethod("renderVision",VisionResult::class.java).apply { isAccessible=true }.invoke(activity,value)
                                            field<EvidenceOverlayView>(activity,"evidenceOverlay").show(value,FrameReceipt(sequence,bitmap.width,bitmap.height,value.processingTimeMs ?: 0,SystemClock.elapsedRealtime()),score)
                                        }
                                        SystemClock.sleep(250)
                                        instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                                            try { File(folder,"app-frame-$mode.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) } }
                                            finally { screenshot.recycle() }
                                        }
                                    }
                                }
                                Log.i("SemanticNativeTest", row.toString())
                            }
                        } finally { if(portrait) bitmap.recycle() }
                    }
                } finally { raw.recycle() }
            }
        } finally { candidate.close(); accepted.close() }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance:Any,name:String):T=instance.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(instance) as T
}

