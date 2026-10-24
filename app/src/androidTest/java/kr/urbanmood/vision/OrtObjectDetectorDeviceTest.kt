package kr.urbanmood.vision

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Uses separately provisioned original RGB and desktop export evidence, never generated model input.
 * Test fixtures are not bundled in the app. This test must run before changing the production factory.
 */
@RunWith(AndroidJUnit4::class)
class OrtObjectDetectorDeviceTest {
    @Test fun pinnedModelMatchesFiveOriginalRgbFixturesAndCloses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "detector-review")
        val evidence = JSONObject(File(folder, "export-verification.json").readText())
        assertEquals(OrtObjectDetector.SOURCE_MODEL_SHA256, evidence.getString("onnxSha256"))
        assertTrue(evidence.getBoolean("parityPassed"))
        val samples = evidence.getJSONArray("samples")
        assertEquals(5, samples.length())
        val detector = OrtObjectDetector(context)
        val measurements = org.json.JSONArray()
        try {
            for (i in 0 until samples.length()) {
                val sample = samples.getJSONObject(i)
                val id = sample.getString("clipId") + "-" + sample.getString("frameId")
                val imageFile = File(folder, "$id.png")
                val hash = MessageDigest.getInstance("SHA-256").digest(imageFile.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(sample.getString("inputSha256"), hash)
                val bitmap = checkNotNull(BitmapFactory.decodeFile(imageFile.absolutePath))
                try {
                    val originalPixel = bitmap.getPixel(0, 0)
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = detector.detect(bitmap)
                    val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    assertFalse(bitmap.isRecycled)
                    assertEquals(originalPixel, bitmap.getPixel(0, 0))
                    assertFalse(result.atCapacity)
                    val expected = sample.getJSONArray("boxesXYXYScoreClass")
                    assertEquals(expected.length(), result.boxes.size)
                    val unused = result.boxes.toMutableList()
                    var maxBoxDelta = 0f
                    for (j in 0 until expected.length()) {
                        val row = expected.getJSONArray(j)
                        val label = OrtObjectDetector.LABELS[row.getInt(5)]
                        val target = floatArrayOf(row.getDouble(0).toFloat() / bitmap.width,
                            row.getDouble(1).toFloat() / bitmap.height,
                            row.getDouble(2).toFloat() / bitmap.width,
                            row.getDouble(3).toFloat() / bitmap.height)
                        fun delta(b: ObjectBox): Float = listOf(kotlin.math.abs(b.left - target[0]),
                            kotlin.math.abs(b.top - target[1]), kotlin.math.abs(b.right - target[2]),
                            kotlin.math.abs(b.bottom - target[3])).max()
                        val box = checkNotNull(unused.filter { it.label == label }.minByOrNull(::delta))
                        maxBoxDelta = maxOf(maxBoxDelta, delta(box))
                        assertTrue("$id $label box delta ${delta(box)}", delta(box) <= .002f)
                        assertEquals(row.getDouble(4).toFloat(), box.confidence, .001f)
                        unused.remove(box)
                    }
                    assertEquals(sample.getInt("personAt05"), result.boxes.count { it.label == "person" && it.confidence >= .5f })
                    measurements.put(JSONObject().put("sampleId", id).put("inputSha256", hash)
                        .put("detectIncludingPreparationMs", elapsed).put("boxCount", result.boxes.size)
                        .put("maxNormalizedBoxDelta", maxBoxDelta).put("borrowedBitmapPreserved", true))
                } finally { bitmap.recycle() }
            }
        } finally { detector.close() }
        detector.close()
        val probe = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        try { assertThrows(IllegalStateException::class.java) { detector.detect(probe) } }
        finally { probe.recycle() }
        File(folder, "android-detector-verification.json").writeText(JSONObject()
            .put("modelSha256", OrtObjectDetector.MODEL_SHA256).put("cpuOnly", true)
            .put("deviceModel", android.os.Build.MODEL).put("abi", android.os.Build.SUPPORTED_ABIS.joinToString())
            .put("samples", measurements).toString(2))
    }
}
