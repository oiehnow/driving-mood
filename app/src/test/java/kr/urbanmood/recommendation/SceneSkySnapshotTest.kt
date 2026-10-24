package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** DTO isolation/regression fixtures, not image recognition or sunset accuracy tests. */
class SceneSkySnapshotTest {
    private fun sky(zero: Boolean = false): SkyColorObservation {
        val n = if (zero) 0 else 12
        return SkyColorObservation(
            measurementVersion = "sky-rgb-v1", rasterWidth = 10, rasterHeight = 10,
            framePixelCount = 100,
            statistics = SkyColorStatistics(n, MutableList(12) { if (zero) 0 else 1 },
                MutableList(12) { if (zero) 0.0 else .5 }, 0, n * .5, n * .5,
                mutableListOf(0, 0, n, 0)),
            meanSaturation = if (zero) null else .5, meanEncodedLuma = if (zero) null else .5,
            rowBandSkyPixCounts = mutableListOf(n / 3, n / 3, n / 3),
            skyMaskSha256 = "a".repeat(64), sourceRgbSha256 = "b".repeat(64),
            frame = SkyFrameIdentity(1, 1_000_000, 0, FrameInputSource.ANDROID_RECORDED, 0),
            semanticModelSha256 = "c".repeat(64))
    }

    private fun frame(sky: SkyColorObservation?, features: Boolean = false, index: Int = 0): VisionResult {
        val stats = LightPixelStats(100, listOf(100, 0, 0, 0, 0, 0, 0, 0),
            listOf(100, 0, 0, 0), 80f, 80f, 0f)
        return VisionResult(1_000_000 + index * 1_000L, AnalysisStatus.OK,
            method = AnalysisMethod.HYBRID, vehicleMaskApplied = true,
            colors = ColorFeatures(hueDegrees = 20f, saturation = .8f, brightness = .6f),
            colorMeasurementConfidence = 1f,
            sceneObservations = SceneObservations(SceneObservationRuntime.ANDROID_RECORDED,
                SourceInfo("numeric fixture"), persons = PersonObservation(5, measurementQuality = .8f),
                semantic = SemanticSceneObservation(skyAreaRatio = .3f, skyBrightness = .8f,
                    measurementQuality = 1f, skyColor = sky?.copy(frame = sky.frame.copy(
                        sequence = index + 1L, capturedAtEpochMs = 1_000_000 + index * 1_000L,
                        cameraTimestampNs = index * 1_000_000_000L, mediaPresentationTimeUs = index * 1_000_000L))),
                lightFeatures = if (!features) null else LightFeatureObservation("accepted-light-rgb-v1",
                    100, 100, stats, null, LightFeatureQuality())))
    }

    private fun snapshot(a: SceneAccumulator) = a.snapshot().samples.single().vision.sceneObservations!!.semantic!!.skyColor!!
    private fun frozen(s: SkyColorObservation) = s.copy(statistics = s.statistics.copy(
        hueSectorCounts = s.statistics.hueSectorCounts.toList(),
        hueSectorSaturationSums = s.statistics.hueSectorSaturationSums.toList(),
        encodedLumaBinCounts = s.statistics.encodedLumaBinCounts.toList()),
        rowBandSkyPixCounts = s.rowBandSkyPixCounts.toList())
    private fun lists(s: SkyColorObservation): List<List<*>> = listOf(s.statistics.hueSectorCounts,
        s.statistics.hueSectorSaturationSums, s.statistics.encodedLumaBinCounts, s.rowBandSkyPixCounts)

    private fun mutate(s: SkyColorObservation, index: Int) {
        when (index) {
            0 -> (s.statistics.hueSectorCounts as MutableList<Int>)[0] = 99
            1 -> (s.statistics.hueSectorSaturationSums as MutableList<Double>)[0] = 99.0
            2 -> (s.statistics.encodedLumaBinCounts as MutableList<Int>)[0] = 99
            3 -> (s.rowBandSkyPixCounts as MutableList<Int>)[0] = 99
        }
    }

    @Test fun everyInputListIsDetachedAtAdd() {
        repeat(4) { field ->
            val input = sky()
            val expected = frozen(input)
            val a = SceneAccumulator()
            assertTrue(a.add(0, frame(input)))
            mutate(input, field)
            assertEquals("input list $field escaped", expected, snapshot(a))
        }
    }

    @Test fun everyOutputListIsDetachedFromStoreAndEarlierSnapshot() {
        repeat(4) { field ->
            val a = SceneAccumulator()
            assertTrue(a.add(0, frame(sky())))
            val first = snapshot(a)
            val second = snapshot(a)
            val expected = frozen(first)
            assertNotSame(lists(first)[field], lists(second)[field])
            mutate(second, field)
            assertEquals("earlier snapshot list $field escaped", expected, first)
            assertEquals("stored list $field escaped", expected, snapshot(a))
        }
    }

    @Test fun zeroSkyRemainsObservedZeroWithNullMeansAndDetachedBins() {
        val input = sky(zero = true)
        val a = SceneAccumulator()
        assertTrue(a.add(0, frame(input)))
        val saved = snapshot(a)
        assertEquals(0, saved.statistics.skyPixCount)
        assertEquals(List(12) { 0 }, saved.statistics.hueSectorCounts)
        assertEquals(List(12) { 0.0 }, saved.statistics.hueSectorSaturationSums)
        assertEquals(List(4) { 0 }, saved.statistics.encodedLumaBinCounts)
        assertEquals(List(3) { 0 }, saved.rowBandSkyPixCounts)
        assertNull(saved.meanSaturation)
        assertNull(saved.meanEncodedLuma)
        mutate(input, 0)
        mutate(saved, 3)
        assertEquals(0, snapshot(a).statistics.hueSectorCounts.sum())
        assertEquals(0, snapshot(a).rowBandSkyPixCounts.sum())
    }

    @Test fun nullObservationAndMissingSemanticRemainNull() {
        val a = SceneAccumulator()
        assertTrue(a.add(0, frame(null)))
        assertNull(a.snapshot().samples.single().vision.sceneObservations!!.semantic!!.skyColor)
        a.clear()
        val v = frame(null)
        assertTrue(a.add(0, v.copy(sceneObservations = v.sceneObservations!!.copy(semantic = null))))
        assertNull(a.snapshot().samples.single().vision.sceneObservations!!.semantic)
    }

    @Test fun clearAndLaterInputDoNotChangeReturnedSnapshotOrFrameIdentity() {
        val a = SceneAccumulator()
        val input = sky()
        assertTrue(a.add(0, frame(input)))
        val saved = snapshot(a)
        val expected = frozen(saved)
        a.clear()
        assertTrue(a.add(0, frame(sky(true))))
        repeat(4) { mutate(input, it) }
        assertEquals(expected, saved)
        assertEquals(0, snapshot(a).statistics.skyPixCount)
        assertEquals(input.frame, saved.frame)
        assertEquals(input.sourceRgbSha256, saved.sourceRgbSha256)
    }

    @Test fun emptyAndSingletonListsAreCopiedWithoutInventingValidStatistics() {
        // Malformed DTOs are not repaired by an accumulator; producer validation is separate.
        for (size in 0..1) {
            val input = sky().let { s -> s.copy(statistics = s.statistics.copy(
                hueSectorCounts = MutableList(size) { 1 }, hueSectorSaturationSums = MutableList(size) { .5 },
                encodedLumaBinCounts = MutableList(size) { 1 }), rowBandSkyPixCounts = MutableList(size) { 1 }) }
            val expected = frozen(input)
            val a = SceneAccumulator()
            assertTrue(a.add(0, frame(input)))
            (input.statistics.hueSectorCounts as MutableList<Int>).clear()
            (input.statistics.hueSectorSaturationSums as MutableList<Double>).clear()
            (input.statistics.encodedLumaBinCounts as MutableList<Int>).clear()
            (input.rowBandSkyPixCounts as MutableList<Int>).clear()
            assertEquals(expected, snapshot(a))
        }
    }

    @Test fun rawSkyDoesNotChangeEitherScoreVersionOrRecommendation() {
        val scorer = SceneMoodScorer()
        for (features in listOf(false, true)) {
            fun window(sky: SkyColorObservation?): SceneWindow {
                val a = SceneAccumulator(30_000)
                repeat(30) { assertTrue(a.add(it * 1_000L, frame(sky, features, it))) }
                return a.snapshot()
            }
            val base = window(null)
            for (environment in listOf(EnvironmentSnapshot(), EnvironmentSnapshot(
                road = RoadContext(Availability.MANUAL, RoadType.HIGHWAY),
                weather = WeatherSnapshot(Availability.MANUAL, temperatureC = 18.0,
                    precipitationMm = 0.0, condition = "강수 없음")))) {
                val expected = scorer.score(base, environment)
                assertEquals(if (features) "scene-v2.0" else "scene-v1.2", expected.ruleVersion)
                val request = RecommendationRequest(null, environment, nowEpochMs = 1_030_000,
                    observationEndEpochMs = 1_030_000, sceneWindow = base)
                val recommendation = MoodRecommendationEngine(Random(17)).recommend(request)
                for (observation in listOf(sky(), sky(true))) {
                    val changed = window(observation)
                    val actual = scorer.score(changed, environment)
                    assertEquals(expected, actual)
                    assertEquals(scorer.keywords(expected), scorer.keywords(actual))
                    assertEquals(recommendation, MoodRecommendationEngine(Random(17)).recommend(request.copy(sceneWindow = changed)))
                }
            }
        }
    }
}
