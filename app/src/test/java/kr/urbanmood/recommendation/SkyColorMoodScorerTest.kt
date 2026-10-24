package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

/** Arithmetic/transport fixtures; no claim of image accuracy or emotion ground truth. */
class SkyColorMoodScorerTest {
    private val scorer = SceneMoodScorer()
    private fun sky(n: Int = 100, warm: Int = n, cool: Int = 0, saturation: Double = .5,
        luma: Double = .5, achromatic: Boolean = false): SkyColorObservation {
        val counts = MutableList(12) { 0 }
        if (!achromatic) { counts[0] = warm; counts[6] = cool; counts[3] = n - warm - cool }
        val s = if (achromatic) 0.0 else saturation
        return SkyColorObservation("sky-rgb-v1", 100, 10, 1000,
            SkyColorStatistics(n, counts, counts.map { it * s }, if (achromatic) n else 0,
                n * s, n * luma, List(4) { if (it == (luma * 4).toInt().coerceAtMost(3)) n else 0 }),
            if (n == 0) null else s, if (n == 0) null else luma,
            listOf(n / 3, n / 3, n - 2 * (n / 3)), "a".repeat(64), "b".repeat(64),
            SkyFrameIdentity(0, 1_000_000, 0, FrameInputSource.ANDROID_RECORDED, 0), "c".repeat(64))
    }
    private fun window(o: SkyColorObservation?, seconds: Int = 30, count: Int = seconds): SceneWindow =
        SceneWindow(seconds * 1000L, receivedFrameCount = count, samples = List(count) { i ->
            val epoch = 1_000_000 + i * 1000L
            SceneSample(i * 1000L, VisionResult(epoch, AnalysisStatus.OK, method = AnalysisMethod.HYBRID,
                sceneObservations = SceneObservations(SceneObservationRuntime.ANDROID_RECORDED, SourceInfo("numeric fixture"),
                    semantic = SemanticSceneObservation(skyColor = o?.copy(frame = o.frame.copy(sequence = i.toLong(),
                        capturedAtEpochMs = epoch, cameraTimestampNs = i * 1_000_000_000L, mediaPresentationTimeUs = i * 1_000_000L))))))
        })
    private fun words(w: SceneWindow) = scorer.keywords(scorer.score(w))
    private fun raw(w: SceneWindow, key: String = "warm") = scorer.score(w).scores.flatMap { it.contributions }
        .single { it.id == "sky_color_${key}_strength" }.delta!!

    @Test fun warmCoolAndMixedFollowDisjointHueGroupsWithoutInventingEmotion() {
        assertEquals(listOf("따뜻한"), words(window(sky())))
        assertEquals(listOf("차가운"), words(window(sky(warm = 0, cool = 100))))
        assertEquals(listOf("다채로운"), words(window(sky(warm = 50, cool = 50))))
        assertEquals(3.5f, raw(window(sky())), .00001f)
        assertEquals(3.5f, raw(window(sky(warm = 50, cool = 50)), "mixed"), .00001f)
        assertTrue(words(window(sky(warm = 0))).isEmpty())
    }

    @Test fun exactAreaLumaAndSaturationPolicyGatesDoNotEraseRawMeasurements() {
        assertEquals(listOf("따뜻한"), words(window(sky(n = 20, saturation = .05, luma = .10))))
        for (o in listOf(sky(n = 19), sky(luma = .099), sky(saturation = .049))) {
            assertTrue(words(window(o)).isEmpty())
            assertTrue(raw(window(o)) > 0f)
        }
        assertEquals(3.5f, raw(window(sky(luma = .001))), .00001f)
    }

    @Test fun dominanceAndMixedUseAllChromaticSaturationAsShareDenominator() {
        assertEquals(listOf("따뜻한"), words(window(sky(warm = 60))))
        assertTrue(words(window(sky(warm = 59))).isEmpty())
        assertEquals(listOf("다채로운"), words(window(sky(warm = 20, cool = 40))))
        assertTrue(words(window(sky(warm = 19, cool = 41))).isEmpty())
        assertTrue(words(window(sky(warm = 20, cool = 39))).isEmpty())
        val repeated = words(window(sky(warm = 60, cool = 40)))
        assertEquals(listOf("따뜻한", "다채로운"), repeated)
        assertEquals(repeated.distinct(), repeated)
    }

    @Test fun nullZeroAndAchromaticHaveDistinctMeasurementStates() {
        val missing = scorer.score(window(null))
        assertFalse(missing.scores.flatMap { it.contributions }.any { it.basis == ContributionBasis.SKY_COLOR_RULE })
        for (o in listOf(sky(n = 0), sky(achromatic = true))) {
            assertEquals(0f, raw(window(o)), 0f)
            assertTrue(words(window(o)).isEmpty())
            assertTrue(scorer.score(window(o)).scores.flatMap { it.contributions }
                .filter { it.basis == ContributionBasis.SKY_COLOR_RULE }.all { it.measurementQuality == null })
        }
    }

    @Test fun coverageUsesEntireRealThirtyAndSixtySecondWindows() {
        for (seconds in listOf(30, 60)) {
            assertTrue(words(window(sky(), seconds, seconds / 2 - 1)).isEmpty())
            assertEquals(listOf("따뜻한"), words(window(sky(), seconds, seconds / 2)))
            assertTrue(words(window(sky(), seconds, 1)).isEmpty())
        }
    }

    @Test fun measuredZeroBucketsReduceRawAverageAndSupportButMissingBucketsOnlyReduceSupport() {
        val full = window(sky())
        val zero = window(sky(n = 0))
        val halfZero = full.copy(samples = full.samples.take(15) + zero.samples.drop(15))
        assertEquals(1.75f, raw(halfZero), .00001f)
        assertEquals(listOf("따뜻한"), words(halfZero))
        assertEquals(3.5f, raw(full.copy(samples = full.samples.take(15))), .00001f)
    }

    @Test fun malformedAndUnboundObservationsNeverCreateSkyEvidence() {
        val o = sky()
        val bad = listOf(o.copy(meanSaturation = Double.NaN), o.copy(meanEncodedLuma = .9),
            o.copy(sourceRgbSha256 = "missing"), o.copy(framePixelCount = 0), o.copy(measurementVersion = "other"),
            o.copy(rowBandSkyPixCounts = listOf(0, 0, 0)), o.copy(semanticRasterWidth = 320),
            o.copy(statistics = o.statistics.copy(saturationSum = Double.POSITIVE_INFINITY)),
            o.copy(statistics = o.statistics.copy(hueSectorSaturationSums = List(12) { 0.0 })),
            o.copy(statistics = o.statistics.copy(hueSectorCounts = listOf(100))))
        for (invalid in bad) assertEquals(scorer.score(window(null)), scorer.score(window(invalid)))
        val w = window(o).let { it.copy(samples = it.samples.map { s -> withSky(s) { x ->
            x.copy(frame = x.frame.copy(capturedAtEpochMs = x.frame.capturedAtEpochMs + 1)) } }) }
        assertEquals(scorer.score(window(null)), scorer.score(w))
    }

    private fun withSky(s: SceneSample, update: (SkyColorObservation) -> SkyColorObservation): SceneSample {
        val scene = s.vision.sceneObservations!!
        val semantic = scene.semantic!!
        return s.copy(vision = s.vision.copy(sceneObservations = scene.copy(semantic =
            semantic.copy(skyColor = update(semantic.skyColor!!)))))
    }

    @Test fun missingRepeatedBackwardOrFabricatedPtsCannotFillTimeCoverage() {
        val w = window(sky())
        for (change in listOf<(SkyColorObservation) -> SkyColorObservation>(
            { it.copy(frame = it.frame.copy(mediaPresentationTimeUs = null)) },
            { it.copy(frame = it.frame.copy(mediaPresentationTimeUs = 0)) },
            { it.copy(frame = it.frame.copy(mediaPresentationTimeUs = 30_000_000 - it.frame.mediaPresentationTimeUs!!)) },
            { it.copy(frame = it.frame.copy(mediaPresentationTimeUs = it.frame.mediaPresentationTimeUs!! * 2)) },
            { it.copy(frame = it.frame.copy(sequence = 0)) },
            { it.copy(frame = it.frame.copy(cameraTimestampNs = 0)) })) {
            assertTrue(words(w.copy(samples = w.samples.map { withSky(it, change) })).isEmpty())
        }
        // Actual nonzero media origin is fine: elapsed is relative, not the absolute PTS.
        assertEquals(listOf("따뜻한"), words(w.copy(samples = w.samples.map { withSky(it) { o ->
            o.copy(frame = o.frame.copy(mediaPresentationTimeUs = o.frame.mediaPresentationTimeUs!! + 90_000_000)) } })))
    }

    @Test fun staleFailedDuplicateAndOutOfWindowFramesDoNotCreateCoverage() {
        val w = window(sky())
        assertTrue(scorer.keywords(scorer.score(w, evaluationTimeEpochMs = 9_000_000)).isEmpty())
        assertTrue(words(w.copy(samples = List(30) { w.samples.first() })).isEmpty())
        assertTrue(words(w.copy(samples = w.samples.map { it.copy(elapsedMs = it.elapsedMs + 30_000) })).isEmpty())
        assertTrue(words(w.copy(samples = w.samples.map { it.copy(vision = it.vision.copy(method = AnalysisMethod.NONE)) })).isEmpty())
    }

    @Test fun liveClockAndRecordedClockStaySeparate() {
        val w = window(sky())
        val live = w.copy(samples = w.samples.map { s -> withSky(s) { o -> o.copy(frame = o.frame.copy(
            inputSource = FrameInputSource.LIVE_CAMERA, mediaPresentationTimeUs = null)) } })
        assertEquals(listOf("따뜻한"), words(live))
        assertEquals(scorer.score(w), scorer.score(w))
    }

    @Test fun personalMusicPreferenceCannotOverwriteCommonObservation() {
        val request = RecommendationRequest(null, EnvironmentSnapshot(), nowEpochMs = 1_030_000,
            observationEndEpochMs = 1_030_000, sceneWindow = window(sky()))
        val engine = MoodRecommendationEngine()
        val base = engine.recommend(request)
        val preferred = engine.recommend(request.copy(preferences = UserPreferences(preferredMoods = setOf("몽환적인"))))
        assertEquals(listOf("따뜻한"), base.commonMoodKeywords)
        assertEquals(base.commonMoodKeywords, preferred.commonMoodKeywords)
        assertTrue(preferred.moodKeywords.contains("몽환적인"))
        assertFalse(preferred.commonMoodKeywords.contains("몽환적인"))
        assertEquals(base.scoreSummary, preferred.scoreSummary)
    }
}
