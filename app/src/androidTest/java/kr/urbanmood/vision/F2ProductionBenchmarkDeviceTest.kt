package kr.urbanmood.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.urbanmood.contracts.*
import kr.urbanmood.integration.AppServices
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Product analyze() timing with unwrapped models. Fixture I/O, hashes, PSS and assertions
 * are outside the measured interval. Frozen output checks are a separate validation phase.
 * This is a bounded emulator/device experiment, not an S25+ latency claim or an FPS test.
 */
@RunWith(AndroidJUnit4::class)
class F2ProductionBenchmarkDeviceTest {
    private data class Fixture(val id: String, val width: Int, val height: Int)
    private data class Observed(val fixture: Fixture, val frameKey: String, val features: LightFeatureObservation)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun memory() = JSONObject().put("pssKb", Debug.getPss())
        .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        .put("javaHeapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
    private fun stats(value: LightPixelStats) = JSONObject()
        .put("pixelCount", value.pixelCount).put("categoryCounts", JSONArray(value.categoryCounts))
        .put("upperChannelCounts", JSONArray(value.upperChannelCounts))
        .put("saturationSum", value.saturationSum).put("saturationSumFloatBits", value.saturationSum.toRawBits())
        .put("redYellowSaturationSum", value.redYellowSaturationSum)
        .put("redYellowSaturationSumFloatBits", value.redYellowSaturationSum.toRawBits())
        .put("cyanBlueSaturationSum", value.cyanBlueSaturationSum)
        .put("cyanBlueSaturationSumFloatBits", value.cyanBlueSaturationSum.toRawBits())

    @Test(timeout = 300_000)
    fun eightActualLandscapeFramesUseUnhookedProductPipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("1", System.getenv("ORT_DISABLE_TELEMETRY"))
        val folder = File(checkNotNull(context.getExternalFilesDir(null)), "f2-review")
        val manifestFile = File(folder, "provenance.json")
        val listFile = File(folder, "frames.tsv")
        val referenceFile = File(folder, "native-results/f2-native-verification.json")
        for (file in listOf(manifestFile, listFile, referenceFile))
            assertTrue("Required native fixture/evidence missing: ${file.name}", file.isFile)
        val manifestBytes = manifestFile.readBytes()
        val files = JSONObject(String(manifestBytes, Charsets.UTF_8)).getJSONArray("frames").let { rows ->
            (0 until rows.length()).associate { index -> rows.getJSONObject(index).let {
                it.getString("frame") to it.getJSONObject("files")
            } }
        }
        val fixtures = listFile.readLines().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t'); require(fields.size == 3)
            require(fields[0].matches(Regex("[A-Za-z0-9_-]+")))
            Fixture(fields[0], fields[1].toInt(), fields[2].toInt()).also {
                require(it.width in 1..640 && it.height in 1..640 && it.width > it.height)
            }
        }
        assertEquals(8, fixtures.size)
        assertEquals(8, fixtures.map { it.id }.toSet().size)
        val prior = JSONObject(referenceFile.readText())
        assertTrue("Run successful F2 native parity first", prior.getBoolean("zeroDifferenceGatePassed"))
        assertEquals(0, prior.getInt("totalF2PixelDifferences"))
        assertEquals(sha(manifestBytes), prior.getString("provenanceSha256"))
        assertEquals(F2SemanticFusion.RULES_SHA256, prior.getString("rulesSha256"))
        assertEquals(OrtObjectDetector.MODEL_SHA256, prior.getString("detectorSha256"))
        val previous = prior.getJSONArray("frames").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }.filter { !it.getBoolean("portrait") }
                .associateBy { it.getString("frame") }
        }
        assertEquals(fixtures.map { it.id }.toSet(), previous.keys)
        fun verified(fixture: Fixture, name: String): ByteArray {
            val path = File(File(folder, fixture.id), name)
            assertTrue("Required fixture missing: ${fixture.id}/$name", path.isFile)
            val bytes = path.readBytes()
            assertEquals("Fixture checksum ${fixture.id}/$name", files.getValue(fixture.id).getString(name), sha(bytes))
            return bytes
        }
        fun load(fixture: Fixture): Bitmap {
            val rgb = verified(fixture, "rgb.bin")
            assertEquals(fixture.width * fixture.height * 3, rgb.size)
            val pixels = IntArray(fixture.width * fixture.height) { i ->
                Color.rgb(rgb[i * 3].toInt() and 255, rgb[i * 3 + 1].toInt() and 255, rgb[i * 3 + 2].toInt() and 255)
            }
            return Bitmap.createBitmap(pixels, fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        }

        val emulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
        val report = JSONObject().put("scope", "Unhooked product analyze call on frozen actual landscape RGB")
            .put("deviceModel", Build.MODEL).put("deviceHardware", Build.HARDWARE)
            .put("abi", Build.SUPPORTED_ABIS.joinToString()).put("emulatorDetected", emulator)
            .put("galaxyS25PlusBenchmark", false).put("cpuOnly", true)
            .put("rulesSha256", F2SemanticFusion.RULES_SHA256).put("detectorSha256", OrtObjectDetector.MODEL_SHA256)
            .put("baselineSha256", OrtSemanticFrameModel.BASELINE.sha256)
            .put("skySha256", OrtSemanticFrameModel.SKY_640.sha256)
            .put("structureSha256", OrtSemanticFrameModel.STRUCTURE_C.sha256)
            .put("fixtureManifestSha256", sha(manifestBytes)).put("priorNativeEvidenceSha256", sha(referenceFile.readBytes()))
            .put("timedIncludes", "SemanticLightAnalyzer.analyze: product preparation, native inference, fusion, object detection, light/sky observation and DTO construction; lazy model loading and product asset validation on cold first call")
            .put("timedExcludes", "Fixture reads and fixture checksums, bitmap creation, PSS sampling, test correctness checks, binary/JSON writes, video decoding, camera/UI and whole-session accumulation")
            .put("modelsWrapped", false).put("callbacksInstalled", false).put("forcedGc", false)
            .put("factory", "AppServices(context).vision")
            .put("uniqueLandscapeFrames", 8).put("portraitFrames", 0)
            .put("plannedColdCalls", 1).put("plannedWarmCalls", 8)
            .put("coldDefinition", "First analyze on fresh AppServices/model sessions; OS page cache, runtime/JIT and device thermal state are not reset")
            .put("warmProtocol", "First actual frame repeated once after cold; remaining seven frames once each, same analyzer; no discarded timing runs")
            .put("completed", false).put("beforeAnalyzerConstruction", memory())
        val output = File(folder, "production-benchmark-${System.currentTimeMillis()}.json")
        val rows = JSONArray(); report.put("frames", rows)
        fun save() = output.writeText(report.toString(2))
        save()
        lateinit var services: AppServices
        var constructionMs = 0.0
        instrumentation.runOnMainSync {
            val constructed = SystemClock.elapsedRealtimeNanos()
            services = AppServices(context)
            constructionMs = (SystemClock.elapsedRealtimeNanos() - constructed) / 1_000_000.0
        }
        val analyzer = services.vision
        report.put("appServicesConstructionMs", constructionMs)
            .put("modelInitializationIncludedInColdAnalyze", true).put("afterAnalyzerConstruction", memory())
        val observed = mutableListOf<Observed>(); val warmTimes = mutableListOf<Double>(); var sequence = 0L
        fun run(bitmap: Bitmap, fixture: Fixture, stage: String) {
            val original = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(original, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val pts = ++sequence * 1_000_000L
            val frame = CameraFrame(bitmap, 1_700_000_000_000L + pts / 1000, pts * 1000, sequence,
                FrameInputSource.ANDROID_RECORDED, pts)
            val row = JSONObject().put("frame", fixture.id).put("stage", stage)
                .put("width", bitmap.width).put("height", bitmap.height).put("beforeAnalyze", memory())
            rows.put(row)
            // Nothing except the actual production method runs inside this timing bracket.
            val start = SystemClock.elapsedRealtimeNanos()
            val result = analyzer.analyze(frame)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            row.put("productAnalyzeMs", elapsedMs).put("productReportedProcessingMs", result.processingTimeMs)
                .put("status", result.status.name).put("afterAnalyze", memory())
            if (stage == "cold-first") report.put("coldFirstAnalyzeMs", elapsedMs)
            else warmTimes += elapsedMs
            save()
            assertTrue("Product analysis failed: ${result.reasons}", result.status in setOf(AnalysisStatus.OK, AnalysisStatus.LOW_CONFIDENCE))
            val scene = checkNotNull(result.sceneObservations)
            val features = checkNotNull(scene.lightFeatures); val groups = checkNotNull(features.groups)
            val sky = checkNotNull(scene.semantic?.skyColor)
            val detail = scene.source.detail.orEmpty()
            assertTrue("Unexpected baseline fallback", detail.contains("F2|${F2SemanticFusion.RULES_SHA256}|"))
            assertFalse("Unexpected baseline fallback", detail.contains("F2 fallback"))
            assertTrue(detail.contains(OrtObjectDetector.MODEL_SHA256))
            assertEquals(fixture.width, features.rasterWidth); assertEquals(fixture.height, features.rasterHeight)
            val expected = previous.getValue(fixture.id)
            assertEquals("Prior optimized native light pixels", expected.getInt("featurePixels"), features.summary.pixelCount)
            assertEquals("Prior optimized native light groups", expected.getInt("featureGroups"), groups.size)
            assertEquals("Prior native sky pixels", expected.getInt("skyPixels"), sky.statistics.skyPixCount)
            assertEquals(expected.getString("skyMaskSha256"), sky.skyMaskSha256)
            assertEquals(expected.getString("skyRgbSha256"), sky.sourceRgbSha256)
            assertEquals(expected.getString("sourceSha256"), sky.semanticModelSha256)
            assertEquals(frame.sequence, sky.frame.sequence)
            assertEquals(frame.mediaPresentationTimeUs, sky.frame.mediaPresentationTimeUs)
            val after = IntArray(original.size)
            assertFalse(bitmap.isRecycled)
            bitmap.getPixels(after, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertArrayEquals(original, after)
            val groupRows = JSONArray()
            groups.forEach { group -> groupRows.put(JSONObject().put("regionId", group.regionId)
                .put("kind", group.kind.name).put("pixels", stats(group.pixels))) }
            row.put("sourceDetail", detail).put("featureSummary", stats(features.summary))
                .put("featureGroups", groupRows).put("featureMeasurementVersion", features.measurementVersion)
                .put("skyPixels", sky.statistics.skyPixCount).put("skyMaskSha256", sky.skyMaskSha256)
                .put("skyRgbSha256", sky.sourceRgbSha256).put("previousNativeAggregateMatch", true)
                .put("borrowedBitmapPreserved", true)
            if (stage != "cold-first") observed += Observed(fixture, "${frame.cameraTimestampNs}:${frame.sequence}", features)
            save()
        }
        try {
            for ((index, fixture) in fixtures.withIndex()) {
                val bitmap = load(fixture)
                try {
                    if (index == 0) run(bitmap, fixture, "cold-first")
                    run(bitmap, fixture, if (index == 0) "warm-first" else "warm-following")
                } finally { bitmap.recycle() }
            }
        } catch (failure: Throwable) {
            report.put("failure", failure.toString()); throw failure
        } finally {
            report.put("beforeAnalyzerClose", memory()); analyzer.close()
            instrumentation.runOnMainSync { services.environment.close(); services.audio.close() }
            report.put("afterAnalyzerClose", memory()).put("timedCallsCompleted", rows.length())
                .put("warmCallsCompleted", warmTimes.size)
            save()
        }
        assertEquals(9, rows.length()); assertEquals(8, warmTimes.size)
        val sorted = warmTimes.sorted()
        report.put("warmMedianMs", (sorted[3] + sorted[4]) / 2.0)
            .put("warmMinMs", sorted.first()).put("warmMaxMs", sorted.last())
            .put("warmMeanMs", warmTimes.average()).put("warmFirstMs", warmTimes.first())

        // Validation phase is after every timed call and after closing the benchmark analyzer.
        // Use exact frozen F2 output + a separate native detector; no model hooks in the timed path.
        val validation = JSONArray(); report.put("outsideTimedFullStatisticsValidation", validation)
        val validationDetector = OrtObjectDetector(context)
        var validationDetectorCalls = 0
        try {
            for (entry in observed) {
                val bitmap = load(entry.fixture)
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val classes = verified(entry.fixture, "F2-aligned.bin")
                    validationDetectorCalls++
                    val detections = validationDetector.detect(bitmap)
                    val reference = SemanticContrastObserver().observe(PixelFrame(bitmap.width, bitmap.height, pixels),
                        classes, detections.boxes, detections.atCapacity, entry.frameKey)
                    assertEquals("Exact light statistics/groups/quality for ${entry.fixture.id}", reference.features, entry.features)
                    validation.put(JSONObject().put("frame", entry.fixture.id).put("fullLightFeaturesExactlyEqual", true))
                    save()
                } finally { bitmap.recycle() }
            }
        } catch (failure: Throwable) {
            report.put("validationFailure", failure.toString()); throw failure
        } finally {
            validationDetector.close()
            report.put("outsideTimedAdditionalDetectorCalls", validationDetectorCalls).put("afterValidationClose", memory())
            save()
        }
        assertEquals(8, validation.length())
        report.put("completed", true).put("fullStatisticsChecksPassed", true); save()
    }
}
