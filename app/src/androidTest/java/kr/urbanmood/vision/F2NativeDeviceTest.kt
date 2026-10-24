package kr.urbanmood.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.urbanmood.contracts.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Native CPU inference only: no Activity, camera, UI synchronization or screenshot calls.
 * Provision the frozen android-parity-inputs directory as external app files/f2-review first.
 * A nonzero parity difference is preserved for diagnosis and fails the zero-difference gate.
 */
@RunWith(AndroidJUnit4::class)
class F2NativeDeviceTest {
    private data class Fixture(val id: String, val width: Int, val height: Int)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun memory() = JSONObject().put("pssKb", Debug.getPss())
        .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        .put("javaHeapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
    private fun differences(actual: ByteArray, expected: ByteArray): Int {
        assertEquals(expected.size, actual.size)
        return actual.indices.count { actual[it] != expected[it] }
    }

    @Test(timeout = 300_000)
    fun frozenEightFramesAndPortraitRunF2AndYoloWithoutUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("1", System.getenv("ORT_DISABLE_TELEMETRY"))
        val folder = File(context.getExternalFilesDir(null), "f2-review")
        val out = File(folder, "native-results").apply { mkdirs() }
        val manifestBytes = File(folder, "provenance.json").readBytes()
        val provenance = JSONObject(String(manifestBytes, Charsets.UTF_8)).getJSONArray("frames")
        val expectedFiles = (0 until provenance.length()).associate { index ->
            provenance.getJSONObject(index).let { it.getString("frame") to it.getJSONObject("files") }
        }
        val fixtureList = File(folder, "frames.tsv").readLines().filter { it.isNotBlank() }.map {
            val fields = it.split('\t'); require(fields.size == 3)
            Fixture(fields[0], fields[1].toInt(), fields[2].toInt())
        }
        assertEquals(8, fixtureList.size)
        assertEquals(8, fixtureList.map { it.id }.toSet().size)
        val report = JSONObject().put("scope", "Frozen RGB native CPU parity and pipeline integrity; not live accuracy")
            .put("deviceModel", android.os.Build.MODEL).put("abi", android.os.Build.SUPPORTED_ABIS.joinToString())
            .put("provenanceSha256", sha(manifestBytes)).put("rulesSha256", F2SemanticFusion.RULES_SHA256)
            .put("detectorSha256", OrtObjectDetector.MODEL_SHA256).put("beforeInit", memory())
        val rows = JSONArray(); report.put("frames", rows)
        fun save() = File(out, "f2-native-verification.json").writeText(report.toString(2))
        save()
        var current: Fixture? = null
        var currentId = ""
        var roleRows = JSONArray()
        var latestRaster: SemanticObservationRaster? = null
        var totalMismatch = 0
        val slowFrames = mutableListOf<String>()

        fun verified(name: String): ByteArray {
            val fixture = checkNotNull(current)
            val bytes = File(File(folder, fixture.id), name).readBytes()
            assertEquals("${fixture.id}/$name hash", expectedFiles.getValue(fixture.id).getString(name), sha(bytes))
            return bytes
        }
        fun wrapped(asset: SemanticModelAsset, role: String, stem: String): SemanticFrameModel {
            val real = OrtSemanticFrameModel(context, asset)
            return object : SemanticFrameModel {
                override val inputSize = real.inputSize
                override val modelSha256 = real.modelSha256
                override fun infer(prepared: SemanticPreparedFrame): ByteArray {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val raw = real.infer(prepared)
                    val row = JSONObject().put("role", role).put("modelSha256", modelSha256)
                        .put("inferMs", (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
                        .put("rawSha256", sha(raw)).put("memory", memory())
                    File(out, "$currentId-$stem-raw.bin").writeBytes(raw)
                    current?.let { fixture ->
                        val expectedTensor = ByteBuffer.wrap(verified("expected-$inputSize.f32"))
                            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        assertEquals(prepared.tensor.size, expectedTensor.remaining())
                        var bitDifferences = 0; var maxAbs = 0f
                        for (value in prepared.tensor) {
                            val expected = expectedTensor.get()
                            if (value.toRawBits() != expected.toRawBits()) bitDifferences++
                            maxAbs = maxOf(maxAbs, abs(value - expected))
                        }
                        row.put("inputFloatBitDifferences", bitDifferences).put("inputMaxAbs", maxAbs)
                            .put("rawPixelDifferences", differences(raw, verified("$stem-raw.bin")))
                        val cropped = prepared.layout.cropClassMap(raw)
                        val aligned = F2SemanticFusion.align(cropped, prepared.pixels.width, prepared.pixels.height,
                            fixture.width, fixture.height)
                        row.put("alignedPixelDifferences", differences(aligned, verified("$stem-aligned.bin")))
                            .put("alignedSha256", sha(aligned))
                        File(out, "$currentId-$stem-aligned.bin").writeBytes(aligned)
                    }
                    roleRows.put(row)
                    return raw
                }
                override fun close() = real.close()
            }
        }
        val f2 = F2SemanticFrameModel(wrapped(OrtSemanticFrameModel.BASELINE, "baseline320", "n320"),
            { wrapped(OrtSemanticFrameModel.SKY_640, "sky640", "n640") },
            { wrapped(OrtSemanticFrameModel.STRUCTURE_C, "structureC320", "C") })
        val recordingModel = object : SemanticFrameModel, SourceSemanticFrameModel {
            override val inputSize = f2.inputSize
            override val modelSha256 = f2.modelSha256
            override fun infer(prepared: SemanticPreparedFrame) = f2.infer(prepared)
            override fun inferSource(frame: PixelFrame) = f2.inferSource(frame).also { latestRaster = it }
            override fun close() = f2.close()
        }
        val analyzer = SemanticLightAnalyzer({ OrtObjectDetector(context) }, { recordingModel },
            skyColorCalculator = DefaultSkyColorCalculator,
            objectModelDescription = "YOLO26n ORT CPU ${OrtObjectDetector.MODEL_SHA256}; cap25")
        var sequence = 0L
        fun run(bitmap: Bitmap, fixture: Fixture?, id: String) {
            current = fixture; currentId = id; roleRows = JSONArray(); latestRaster = null
            val row = JSONObject().put("frame", id).put("width", bitmap.width).put("height", bitmap.height)
                .put("portrait", fixture == null).put("before", memory())
            rows.put(row); save()
            try {
                val original = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(original, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val pts = ++sequence * 1_000_000L
                val frame = CameraFrame(bitmap, 1_700_000_000_000L + pts / 1000, pts * 1000, sequence,
                    FrameInputSource.ANDROID_RECORDED, pts)
                val started = SystemClock.elapsedRealtimeNanos()
                val result = analyzer.analyze(frame)
                val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                row.put("pipelineMs", elapsed).put("status", result.status.name).put("reasons", JSONArray(result.reasons))
                    .put("roles", roleRows).put("afterInference", memory())
                save()
                if (elapsed > 30_000) slowFrames += id
                val raster = checkNotNull(latestRaster) { "No native F2 output: ${result.reasons}" }
                row.put("roleElapsedMs", JSONObject(raster.roleElapsedMs)).put("sourceSha256", raster.sourceSha256)
                    .put("description", raster.description).put("fallbackReason", raster.fallbackReason ?: JSONObject.NULL)
                    .put("actualF2Sha256", sha(raster.classes))
                File(out, "$id-F2-aligned.bin").writeBytes(raster.classes)
                assertNull("Unexpected fallback", raster.fallbackReason)
                assertEquals(setOf("baseline320", "sky640", "structureC320"), raster.roleElapsedMs.keys)
                assertTrue(raster.description.startsWith("F2|${F2SemanticFusion.RULES_SHA256}|"))
                assertEquals(bitmap.width * bitmap.height, raster.classes.size)
                assertTrue(raster.classes.all { (it.toInt() and 255) < 150 })
                fixture?.let {
                    val expected = verified("F2-aligned.bin")
                    val count = differences(raster.classes, expected); totalMismatch += count
                    row.put("expectedF2Sha256", sha(expected)).put("f2PixelDifferences", count)
                        .put("f2PixelDifferenceRatio", count.toDouble() / expected.size)
                }
                val scene = checkNotNull(result.sceneObservations)
                val features = checkNotNull(scene.lightFeatures)
                val groups = checkNotNull(features.groups)
                assertEquals(bitmap.width, features.rasterWidth); assertEquals(bitmap.height, features.rasterHeight)
                assertEquals(features.summary.pixelCount, groups.sumOf { it.pixels.pixelCount })
                assertEquals(groups.size, groups.map { it.regionId }.toSet().size)
                for (i in features.summary.categoryCounts.indices)
                    assertEquals(features.summary.categoryCounts[i], groups.sumOf { it.pixels.categoryCounts[i] })
                for (i in features.summary.upperChannelCounts.indices)
                    assertEquals(features.summary.upperChannelCounts[i], groups.sumOf { it.pixels.upperChannelCounts[i] })
                fun checkSum(value: Float, parts: List<Float>) = assertEquals(value.toDouble(), parts.sumOf { it.toDouble() },
                    1e-4 * maxOf(1.0, abs(value.toDouble())))
                checkSum(features.summary.saturationSum, groups.map { it.pixels.saturationSum })
                checkSum(features.summary.redYellowSaturationSum, groups.map { it.pixels.redYellowSaturationSum })
                checkSum(features.summary.cyanBlueSaturationSum, groups.map { it.pixels.cyanBlueSaturationSum })
                val sky = checkNotNull(scene.semantic?.skyColor)
                assertEquals(raster.sourceSha256, sky.semanticModelSha256)
                assertEquals(bitmap.width, sky.rasterWidth); assertEquals(bitmap.height, sky.rasterHeight)
                assertEquals(raster.classes.count { (it.toInt() and 255) == 2 }, sky.statistics.skyPixCount)
                assertEquals(frame.sequence, sky.frame.sequence)
                assertEquals(frame.mediaPresentationTimeUs, sky.frame.mediaPresentationTimeUs)
                assertTrue(scene.source.detail.orEmpty().contains(OrtObjectDetector.MODEL_SHA256))
                assertTrue(scene.source.detail.orEmpty().contains(F2SemanticFusion.RULES_SHA256))
                assertEquals(SceneObservationRuntime.ANDROID_RECORDED, scene.runtime)
                assertFalse(bitmap.isRecycled)
                val after = IntArray(original.size)
                bitmap.getPixels(after, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertArrayEquals(original, after)
                row.put("featurePixels", features.summary.pixelCount).put("featureGroups", groups.size)
                    .put("skyPixels", sky.statistics.skyPixCount).put("skyMaskSha256", sky.skyMaskSha256)
                    .put("skyRgbSha256", sky.sourceRgbSha256).put("sourceDetail", scene.source.detail)
                    .put("groupPartitionVerified", true).put("bitmapPreserved", true)
            } catch (failure: Throwable) {
                row.put("failure", failure.toString()); throw failure
            } finally { save() }
        }
        try {
            for (fixture in fixtureList) {
                current = fixture
                val rgb = verified("rgb.bin")
                assertEquals(fixture.width * fixture.height * 3, rgb.size)
                val pixels = IntArray(fixture.width * fixture.height) { i ->
                    Color.rgb(rgb[i * 3].toInt() and 255, rgb[i * 3 + 1].toInt() and 255, rgb[i * 3 + 2].toInt() and 255)
                }
                val bitmap = Bitmap.createBitmap(pixels, fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
                try {
                    run(bitmap, fixture, fixture.id)
                    if (fixture == fixtureList.first()) {
                        // Exact clockwise pixel permutation: orientation check, not a fabricated independent fixture.
                        val rotated = IntArray(pixels.size)
                        for (y in 0 until fixture.height) for (x in 0 until fixture.width)
                            rotated[x * fixture.height + fixture.height - 1 - y] = pixels[y * fixture.width + x]
                        val portrait = Bitmap.createBitmap(rotated, fixture.height, fixture.width, Bitmap.Config.ARGB_8888)
                        try { run(portrait, null, fixture.id + "-portrait90") } finally { portrait.recycle() }
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            report.put("beforeClose", memory())
            analyzer.close(); analyzer.close()
            report.put("afterClose", memory()).put("totalF2PixelDifferences", totalMismatch)
                .put("framesOver30Seconds", JSONArray(slowFrames)).put("completedRows", rows.length())
            save()
        }
        assertEquals(9, rows.length())
        assertTrue("Slow frames recorded: $slowFrames", slowFrames.isEmpty())
        assertEquals("Native F2 mismatch preserved in native-results; investigate before adoption", 0, totalMismatch)
        report.put("zeroDifferenceGatePassed", true); save()
    }
}
