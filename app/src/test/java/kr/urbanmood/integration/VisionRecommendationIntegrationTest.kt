package kr.urbanmood.integration

import android.graphics.Bitmap
import android.net.Uri
import kr.urbanmood.contracts.*
import kr.urbanmood.recommendation.MoodRecommendationEngine
import kr.urbanmood.recommendation.SceneAccumulator
import kr.urbanmood.vision.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual B pixel measurement -> actual C recommendation. Authored pixels and fake detections:
 * verifies integration policy, NOT native model accuracy or real-camera performance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VisionRecommendationIntegrationTest {
    private val now = 1_800_000_000_000L
    private val environment = EnvironmentSnapshot(generatedAtEpochMs = now, zoneId = "Asia/Seoul")
    private val catalog = listOf(
        LocalTrack("warm", Uri.parse("content://fixture/warm"), "Warm fixture", tags = setOf("따뜻한")),
        LocalTrack("cool", Uri.parse("content://fixture/cool"), "Cool fixture", tags = setOf("차가운")),
    )

    private fun analyze(color: Int = 0xffe09030.toInt(), masked: Boolean = false,
                        dark: Boolean = false, failed: Boolean = false, lit: Boolean = true,
                        inputSource: FrameInputSource = FrameInputSource.LIVE_CAMERA): VisionResult {
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (dark) 0xff000000.toInt() else 0xff202020.toInt())
        if (lit) for (y in 30 until 46) for (x in 20 until 55) bitmap.setPixel(x, y, color)
        val analyzer = UrbanLightAnalyzer({ object : FrameObjectDetector {
            override fun detect(borrowedBitmap: Bitmap): DetectionBatch {
                if (failed) error("authored inference failure")
                return DetectionBatch(if (masked) listOf(ObjectBox(.09f, .23f, .29f, .40f, "car", .9f))
                    else emptyList(), false)
            }
            override fun close() = Unit
        } }, { 1_000_000_000L })
        return try { analyzer.analyze(CameraFrame(bitmap, now, 250_000_000L, 1, inputSource,
            if (inputSource == FrameInputSource.ANDROID_RECORDED) 250_000 else null)) }
        finally { analyzer.close(); bitmap.recycle() }
    }

    private fun recommend(v: VisionResult, seed: Int = 7, env: EnvironmentSnapshot = environment,
                          at: Long = now) = MoodRecommendationEngine(Random(seed)).recommend(
        RecommendationRequest(v, env, catalog = catalog, nowEpochMs = at))

    @Test fun actualLowConfidenceMeasurementsChangeKeywordsAndWeightedCandidateOrder() {
        val warm = analyze()
        val cool = analyze(color = 0xff3090e0.toInt())
        for (v in listOf(warm, cool)) {
            assertEquals(AnalysisStatus.LOW_CONFIDENCE, v.status)
            assertNull(v.semanticConfidence)
            assertTrue(VisionMeasurementPolicy.usableColor(v, now))
        }
        assertTrue(recommend(warm).moodKeywords.contains("따뜻한"))
        assertFalse(recommend(cool).moodKeywords.contains("따뜻한"))
        assertTrue(recommend(cool).moodKeywords.contains("차가운"))
        assertFalse(recommend(cool).moodKeywords.contains("몽환적인"))
        assertTrue(recommend(warm).youtubeQuery!!.contains("따뜻한"))
        assertTrue(recommend(cool).youtubeQuery!!.contains("차가운"))
        // Paired identical seeds keep randomness identical while only measured hue changes.
        val warmFirst = (0 until 64).count { recommend(warm, it).candidates.first().id == "warm" }
        val coolFirst = (0 until 64).count { recommend(cool, it).candidates.first().id == "warm" }
        assertTrue("Measured color must change candidate weights", warmFirst > coolFirst)
        assertFalse(recommend(warm).moodKeywords.contains("경쾌한"))
    }

    @Test fun actualMeasuredWindowRetainsEarlyEvidenceAndDoesNotInventRecordedTime() {
        val early = analyze()
        val late = early.copy(frameTimeEpochMs = now + 49_000,
            sceneObservations = early.sceneObservations?.let { it.copy(source = it.source.copy(observedAtEpochMs = now + 49_000)) })
        val accumulator = SceneAccumulator()
        assertTrue(accumulator.add(0, early))
        assertTrue(accumulator.add(49_000, late))
        val result = MoodRecommendationEngine(Random(7)).recommend(RecommendationRequest(late, environment,
            catalog = catalog, nowEpochMs = now + 50_000, sceneWindow = accumulator.snapshot(), timeContextAvailable = false))
        assertEquals(2, result.scoreSummary!!.sampledBucketCount)
        val warmth = result.scoreSummary.scores.single { it.keyword == "따뜻한" }
        assertTrue(warmth.score!! > 0)
        val feature = warmth.contributions.single { it.id == "feature_warm" }
        assertEquals(ContributionBasis.OBSERVED_FEATURE_STRENGTH, feature.basis)
        assertEquals(setOf(0L, 49_000L), feature.evidence.map { it.sampleElapsedMs }.toSet())
        // Two observed seconds retain measured strength, but do not establish persistence across 50s.
        assertFalse(result.moodKeywords.contains("따뜻한"))
        assertFalse(result.moodKeywords.contains("몽환적인"))
        assertTrue(result.moodKeywords.none { it in listOf("야간", "아침", "낮", "저녁") })
        assertTrue(result.reasons.none { it.contains("기기 시각") })
    }

    @Test fun actualExcludedEmptyAndFailedFramesCannotSupplyColorMood() {
        for (v in listOf(analyze(masked = true), analyze(dark = true, lit = false), analyze(failed = true))) {
            assertFalse(VisionMeasurementPolicy.usableColor(v, now))
            assertFalse(recommend(v).moodKeywords.contains("따뜻한"))
            assertFalse(recommend(v).moodKeywords.contains("경쾌한"))
        }
    }

    @Test fun darkBackgroundDoesNotEraseActualMeasuredLightFeatures() {
        val v = analyze(dark = true)
        assertFalse(VisionMeasurementPolicy.usableColor(v, now)) // legacy quality gate
        assertTrue(v.sceneObservations!!.lightFeatures!!.summary.pixelCount > 0)
        val result = recommend(v)
        assertTrue(result.scoreSummary!!.scores.single { it.keyword == "따뜻한" }.score!! > 0)
        assertTrue(result.moodKeywords.contains("따뜻한"))
    }

    @Test fun highwayLimitsActivityInterpretationWhileStaleMeasurementsRemainExcluded() {
        val v = analyze()
        assertTrue(VisionMeasurementPolicy.usableColor(v, now))
        val highway = environment.copy(road = RoadContext(Availability.MANUAL, RoadType.HIGHWAY,
            source = SourceInfo("수동 시연 fixture")))
        val result = recommend(v, env = highway)
        assertTrue(result.moodKeywords.contains("고속도로"))
        assertEquals(Availability.MANUAL, result.availability)
        assertTrue(result.moodKeywords.contains("따뜻한"))
        assertEquals(recommend(v).scoreSummary!!.scores.single { it.keyword == "따뜻한" }.score,
            result.scoreSummary!!.scores.single { it.keyword == "따뜻한" }.score)
        assertFalse(result.moodKeywords.contains("활기 넘침"))
        assertFalse(recommend(v, at = now + 10_001).moodKeywords.contains("따뜻한"))
    }

    @Test fun recordedBFeaturesSurviveDecodeDelayUsingKnownCaptureEnd() {
        val original = analyze(inputSource = FrameInputSource.ANDROID_RECORDED)
        assertEquals(SceneObservationRuntime.ANDROID_RECORDED, original.sceneObservations!!.runtime)
        val accumulator = SceneAccumulator(30_000)
        for (second in 0 until 30) {
            val at = now + second * 1_000
            val frame = original.copy(frameTimeEpochMs = at,
                sceneObservations = original.sceneObservations.copy(source = original.sceneObservations.source.copy(observedAtEpochMs = at)))
            assertTrue(accumulator.add(second * 1_000L, frame))
        }
        val request = RecommendationRequest(original, environment, catalog = catalog,
            nowEpochMs = now + 600_000, sceneWindow = accumulator.snapshot(), observationEndEpochMs = now + 30_000)
        val engine = MoodRecommendationEngine(Random(7))
        assertTrue(engine.recommend(request).moodKeywords.contains("따뜻한"))
        assertFalse(engine.recommend(request.copy(observationEndEpochMs = null)).moodKeywords.contains("따뜻한"))
    }
}
