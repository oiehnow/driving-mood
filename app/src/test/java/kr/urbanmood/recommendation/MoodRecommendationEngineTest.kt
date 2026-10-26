package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class MoodRecommendationEngineTest {
    private val now = Instant.parse("2026-10-23T14:30:00Z").toEpochMilli()
    private val source = SourceInfo("test fixture", now-60_000, now)
    private val env = EnvironmentSnapshot(now, "Asia/Seoul", LocationSnapshot(Availability.AVAILABLE,37.5,127.0,accuracyMeters=10f,source=source))
    private val engine = MoodRecommendationEngine(Random(7))
    private fun recommend(e: EnvironmentSnapshot = env, vision: VisionResult? = null) = engine.recommend(RecommendationRequest(vision,e,nowEpochMs=now))
    @Test fun missingDataStillHasMoodAndEmptyCatalog() {
        val r = recommend(EnvironmentSnapshot(zoneId="Asia/Seoul"))
        assertTrue(r.moodKeywords.contains("야간")); assertTrue(r.candidates.isEmpty())
        assertFalse(r.moodKeywords.contains("비")); assertNotNull(r.youtubeQuery)
        assertTrue(r.reasons.any { it.contains("음악 파일을 선택") })
    }
    @Test fun recentRainIsUsedButStaleRainIsExcluded() {
        val rain = WeatherSnapshot(Availability.AVAILABLE,condition="비",source=source)
        assertTrue(recommend(env.copy(weather=rain)).moodKeywords.contains("비"))
        assertFalse(recommend(env.copy(weather=rain.copy(source=source.copy(observedAtEpochMs=now-10_000_000)))).moodKeywords.contains("비"))
        assertTrue(recommend(env.copy(location=LocationSnapshot(),weather=rain)).moodKeywords.contains("비"))
    }
    @Test fun receivedRainContributesPointsEvenAfterLocationExpires() {
        val rain = WeatherSnapshot(Availability.AVAILABLE, 18.0, 2.0, "비", source)
        val result = recommend(env.copy(location = env.location.copy(
            source = source.copy(observedAtEpochMs = now - 150_000)), weather = rain))
        val contribution = result.scoreSummary!!.scores.flatMap { it.contributions }
            .single { it.id == "weather_rain" }
        assertEquals(30f, contribution.delta!!, .001f)
        assertTrue("차분한" in result.commonMoodKeywords)
    }
    @Test fun manualWeatherIsAlwaysIdentified() {
        val r = recommend(env.copy(weather=WeatherSnapshot(Availability.MANUAL,condition="비")))
        assertEquals(Availability.MANUAL,r.availability)
        assertTrue(r.moodKeywords.contains("수동 입력")); assertTrue(r.confidence!! <= .35f)
        assertTrue(r.reasons.any { it.contains("실제 관측값이 아닙니다") })
        assertFalse(r.youtubeQuery!!.contains("수동 입력"))
    }
    @Test fun highwaySuppressesBrightUrbanActivityAndUnknownDoesNotBecomeHighway() {
        val bright = VisionResult(now,AnalysisStatus.OK,1f,vehicleExcludedRatio=.1f,confidence=.9f,method=AnalysisMethod.HYBRID)
        val r = recommend(env.copy(road=RoadContext(Availability.AVAILABLE,RoadType.HIGHWAY,source=source)),bright)
        assertTrue(r.moodKeywords.contains("고속도로")); assertFalse(r.moodKeywords.contains("경쾌한"))
        assertFalse(recommend(vision=bright).moodKeywords.contains("고속도로"))
    }
    @Test fun vehicleHeavyOrStaleVisionDoesNotCreateLivelyMood() {
        val bright = VisionResult(now,AnalysisStatus.OK,1f,vehicleExcludedRatio=.8f,confidence=.9f,method=AnalysisMethod.HYBRID)
        assertFalse(recommend(vision=bright).moodKeywords.contains("경쾌한"))
        assertFalse(recommend(vision=bright.copy(frameTimeEpochMs=now-30_000,vehicleExcludedRatio=.0f)).moodKeywords.contains("경쾌한"))
        assertFalse(recommend(vision=bright.copy(status=AnalysisStatus.MODEL_NOT_READY)).moodKeywords.contains("경쾌한"))
    }
    @Test fun currentAndRecentGoLastButWholeCatalogRemainsForNaturalCompletion() {
        val order = CandidateOrder.order(listOf("a","b","c","d"), {it}, listOf("a","b"), Random(1)) {1.0}
        assertEquals(setOf("c","d"),order.take(2).toSet())
        assertEquals(listOf("a","b"),order.takeLast(2))
        assertEquals(4,order.size)
    }
    @Test fun sameInputCanVaryAndEmptySingleCatalogsAreValid() {
        val rng = Random(4)
        val first = (1..30).map { CandidateOrder.order(listOf("a","b","c"), {it}, emptyList(),rng){1.0}.first() }.toSet()
        assertTrue(first.size > 1)
        assertEquals(emptyList<String>(), CandidateOrder.order(emptyList<String>(), {it},emptyList(),rng){1.0})
        assertEquals(listOf("a"), CandidateOrder.order(listOf("a","a"), {it},listOf("a"),rng){1.0})
    }
    @Test fun morningBoundaryUsesRequestedTimezone() {
        val morning = now + 7*60*60_000
        val r = engine.recommend(RecommendationRequest(null,env,nowEpochMs=morning))
        assertTrue(r.moodKeywords.contains("아침")); assertFalse(r.moodKeywords.contains("야간"))
    }
    @Test fun missingVehicleEvidenceAndInvalidScoresAreNotLivelyEvidence() {
        val v = VisionResult(now,AnalysisStatus.OK,1f,confidence=.9f,method=AnalysisMethod.HEURISTIC)
        assertFalse(recommend(vision=v).moodKeywords.contains("경쾌한"))
        assertFalse(recommend(vision=v.copy(urbanLightScore=10f,vehicleExcludedRatio=.0f)).moodKeywords.contains("경쾌한"))
    }
    @Test fun brightnessWithoutSemanticEvidenceNeverCreatesLivelyMood() {
        // A high legacy confidence and OK status do not identify the light source as a shop.
        val bright = VisionResult(now, AnalysisStatus.OK, urbanLightScore=1f,
            colors=ColorFeatures(brightness=.95f, luminousAreaRatio=.7f),
            vehicleExcludedRatio=.05f, confidence=.95f, method=AnalysisMethod.HYBRID)
        val result = recommend(vision=bright)
        assertFalse(result.moodKeywords.contains("경쾌한"))
        assertFalse(result.youtubeQuery!!.contains("경쾌한"))
    }
    private fun measuredColor() = VisionResult(now, AnalysisStatus.LOW_CONFIDENCE,
        urbanLightScore=.8f,
        colors=ColorFeatures(hueDegrees=30f, saturation=.8f, brightness=.8f, luminousAreaRatio=.2f),
        vehicleExcludedRatio=.1f, confidence=.1f, method=AnalysisMethod.HYBRID,
        reasons=listOf("fixture: 차량 제외 후 색 측정, 상점 의미 미평가"),
        colorMeasurementConfidence=.8f, lightMeasurementConfidence=.8f,
        semanticConfidence=null, vehicleMaskApplied=true)

    @Test fun lowSemanticConfidenceDoesNotDiscardValidMeasuredColor() {
        val warm = recommend(vision=measuredColor())
        val cool = recommend(vision=measuredColor().copy(colors=measuredColor().colors!!.copy(hueDegrees=220f)))
        assertTrue(warm.moodKeywords.contains("따뜻한"))
        assertFalse(warm.moodKeywords.contains("몽환적인"))
        assertTrue(cool.moodKeywords.contains("차가운"))
        assertFalse(cool.moodKeywords.contains("몽환적인"))
        assertFalse(cool.moodKeywords.contains("따뜻한"))
        assertTrue(warm.youtubeQuery!!.contains("따뜻한"))
        assertTrue(cool.youtubeQuery!!.contains("차가운"))
        assertFalse(warm.moodKeywords.contains("경쾌한"))
        assertTrue(warm.reasons.any { it.contains("색감") })
        assertTrue(warm.reasons.any { it.contains("의미") && it.contains("미평가") })
    }
    @Test fun legacyOverallConfidenceNeverFillsMissingMeasurementQuality() {
        val r = recommend(vision=measuredColor().copy(status=AnalysisStatus.OK,
            confidence=1f, colorMeasurementConfidence=null))
        assertFalse(r.moodKeywords.contains("따뜻한"))
        assertFalse(r.moodKeywords.contains("경쾌한"))
    }
    @Test fun measurementRequiresActualMaskPassAndUsableRemainingArea() {
        for (v in listOf(measuredColor().copy(vehicleMaskApplied=false),
            measuredColor().copy(vehicleExcludedRatio=null),
            measuredColor().copy(vehicleExcludedRatio=.8f))) {
            val r = recommend(vision=v)
            assertFalse(r.moodKeywords.contains("따뜻한"))
            assertFalse(r.moodKeywords.contains("경쾌한"))
        }
    }
    @Test fun missingStaleFutureAndFailedMeasurementsNeverAffectColorMood() {
        for (v in listOf(null, measuredColor().copy(frameTimeEpochMs=now-30_000),
            measuredColor().copy(frameTimeEpochMs=now+1),
            measuredColor().copy(status=AnalysisStatus.FAILED),
            measuredColor().copy(status=AnalysisStatus.MODEL_NOT_READY),
            measuredColor().copy(method=AnalysisMethod.NONE))) {
            assertFalse(recommend(vision=v).moodKeywords.contains("따뜻한"))
        }
    }
    @Test fun invalidOrInsufficientColorMeasurementsDoNotProduceColorMood() {
        val valid = measuredColor()
        for (v in listOf(valid.copy(colorMeasurementConfidence=Float.NaN),
            valid.copy(colorMeasurementConfidence=1.1f), valid.copy(colorMeasurementConfidence=.2f),
            valid.copy(colors=null), valid.copy(colors=valid.colors!!.copy(hueDegrees=Float.NaN)),
            valid.copy(colors=valid.colors!!.copy(saturation=Float.POSITIVE_INFINITY)),
            valid.copy(colors=valid.colors!!.copy(saturation=0f)))) {
            assertFalse(recommend(vision=v).moodKeywords.contains("따뜻한"))
        }
    }
    @Test fun colorAndLightQualityAreIndependentAndBrightnessDoesNotSetEnergy() {
        val v = measuredColor()
        assertTrue(recommend(vision=v.copy(lightMeasurementConfidence=null)).moodKeywords.contains("따뜻한"))
        assertFalse(recommend(vision=v.copy(colorMeasurementConfidence=null)).moodKeywords.contains("따뜻한"))
        val dim = recommend(vision=v.copy(urbanLightScore=.1f))
        val bright = recommend(vision=v.copy(urbanLightScore=.95f))
        assertEquals(dim.moodKeywords, bright.moodKeywords)
    }
    @Test fun highwaySuppressesTrafficColorEvenWithGoodMeasurementAndSemanticScores() {
        val road = RoadContext(Availability.AVAILABLE, RoadType.HIGHWAY,
            source=source.copy(name="도로 fixture"))
        val r = recommend(env.copy(road=road), measuredColor().copy(semanticConfidence=1f))
        assertTrue(r.moodKeywords.contains("고속도로"))
        assertFalse(r.moodKeywords.contains("따뜻한"))
        assertFalse(r.moodKeywords.contains("경쾌한"))
        assertTrue(r.reasons.any { it.contains("도로 fixture") })
    }
    @Test fun staleOrFutureRoadSourceIsNotReportedAsCurrentHighway() {
        for (time in listOf(now-500_000, now+1)) {
            val road = RoadContext(Availability.AVAILABLE, RoadType.HIGHWAY,
                source=source.copy(observedAtEpochMs=time))
            val r = recommend(env.copy(road=road), measuredColor())
            assertFalse(r.moodKeywords.contains("고속도로"))
            assertTrue(r.reasons.any { it.contains("도로 종류 미확인") })
        }
    }
    @Test fun manualHighwayRemainsManualAndDoesNotGainSemanticCertainty() {
        val r = recommend(env.copy(road=RoadContext(Availability.MANUAL, RoadType.HIGHWAY,
            source=SourceInfo("수동 도로"))), measuredColor())
        assertEquals(Availability.MANUAL, r.availability)
        assertTrue(r.moodKeywords.contains("수동 입력"))
        assertTrue(r.confidence!! <= .35f)
        assertFalse(r.moodKeywords.contains("따뜻한"))
    }
    @Test fun repeatedHistoryUsesMostRecentOccurrence() {
        val order = CandidateOrder.order(listOf("a","b"),{it},listOf("a","b","a"),Random(1)){1.0}
        assertEquals(listOf("b","a"),order)
    }

    @Test fun cyanBlueAddsColdWithoutDreamyOnlyInsideExplicitHueBand() {
        val valid = measuredColor()
        for (hue in listOf(165f,180f,220f,240f,254.99f)) {
            val r = recommend(vision=valid.copy(colors=valid.colors!!.copy(hueDegrees=hue)))
            assertTrue("hue=$hue",r.moodKeywords.contains("차가운"))
            assertFalse(r.moodKeywords.contains("몽환적인"))
            assertFalse(r.moodKeywords.contains("우울"))
            assertFalse(r.moodKeywords.contains("활기 넘침"))
        }
        for (hue in listOf(0f,164.99f,255f,300f,359f)) {
            assertFalse(recommend(vision=valid.copy(colors=valid.colors!!.copy(hueDegrees=hue))).moodKeywords.contains("차가운"))
        }
    }
    @Test fun coldStillRequiresQualityFreshnessMaskAndNonHighway() {
        val cold = measuredColor().let { it.copy(colors=it.colors!!.copy(hueDegrees=220f)) }
        for (v in listOf(cold.copy(colorMeasurementConfidence=null),cold.copy(vehicleMaskApplied=false),
            cold.copy(vehicleExcludedRatio=.4f),cold.copy(frameTimeEpochMs=now-10_001),
            cold.copy(colors=cold.colors!!.copy(saturation=.2f)),cold.copy(status=AnalysisStatus.FAILED))) {
            assertFalse(recommend(vision=v).moodKeywords.contains("차가운"))
        }
        assertFalse(recommend(env.copy(road=RoadContext(Availability.MANUAL,RoadType.HIGHWAY)),cold).moodKeywords.contains("차가운"))
    }
    @Test fun desiredMoodsComeOnlyFromValidatedExplicitSelectionAndNotActualEmotion() {
        val preference = UserPreferences(preferredMoods=setOf("우울","활기 넘침","unknown-mood"))
        val r = engine.recommend(RecommendationRequest(measuredColor(),env,preference,nowEpochMs=now))
        assertTrue(r.moodKeywords.containsAll(listOf("우울","활기 넘침")))
        assertFalse(r.moodKeywords.contains("unknown-mood"))
        assertFalse(r.youtubeQuery!!.contains("unknown-mood"))
        assertTrue(r.reasons.any { it.contains("사용자가 원하는 분위기") && it.contains("우울") })
        assertTrue(r.reasons.any { it.contains("실제 감정") && it.contains("아닙니다") })
        assertEquals(recommend(vision=measuredColor()).confidence,r.confidence)
    }
    @Test fun explicitTargetOverridesConflictingAutomaticMoodButRetainsEvidenceAndRoadContext() {
        val warm = engine.recommend(RecommendationRequest(measuredColor(),env,
            UserPreferences(preferredMoods=setOf("차가운")),nowEpochMs=now))
        assertTrue(warm.moodKeywords.contains("차가운"))
        assertFalse(warm.moodKeywords.contains("따뜻한"))
        assertFalse(warm.youtubeQuery!!.contains("따뜻한"))
        assertTrue(warm.reasons.any { it.contains("따뜻한") && it.contains("색감") })
        assertTrue(warm.reasons.any { it.contains("우선") })
        val r = engine.recommend(RecommendationRequest(measuredColor(),env.copy(road=RoadContext(Availability.MANUAL,RoadType.HIGHWAY)),
            UserPreferences(preferredMoods=setOf("활기 넘침")),nowEpochMs=now))
        assertTrue(r.moodKeywords.containsAll(listOf("고속도로","활기 넘침")))
        assertTrue(r.reasons.any { it.contains("사용자가 원하는 분위기") })
    }
    @Test fun clearingOrReplacingSelectionNeverRetainsOldMoodOrSource() {
        fun selection(vararg mood: String) = engine.recommend(RecommendationRequest(measuredColor(),env,
            UserPreferences(preferredMoods=mood.toSet()),nowEpochMs=now))
        assertTrue(selection("우울").moodKeywords.contains("우울"))
        val changed = selection("활기 넘침")
        assertFalse(changed.moodKeywords.contains("우울"))
        val cleared = selection()
        assertFalse(cleared.moodKeywords.contains("활기 넘침"))
        assertFalse(cleared.moodKeywords.contains("우울"))
        assertFalse(cleared.reasons.any { it.contains("사용자가 원하는 분위기:") })
        assertTrue(cleared.moodKeywords.contains("따뜻한"))
        assertEquals(cleared.moodKeywords,selection("arbitrary-value").moodKeywords)
    }
    @Test fun sharedDesiredVocabularyWeightsTaggedChoicesAndClearingRemovesBoost() {
        for (mood in MoodVocabulary.options) {
            val selected = setOf(mood)
            assertEquals(7.0,MoodTagWeights.weight(selected,emptySet(),emptySet(),selected),0.0)
            assertEquals(1.0,MoodTagWeights.weight(selected,emptySet(),emptySet(),emptySet()),0.0)
        }
        assertEquals(1.0,MoodTagWeights.weight(setOf("unknown"),emptySet(),emptySet(),setOf("unknown")),0.0)
        fun selectedFirst(desired: Set<String>): Int {
            val random = Random(19)
            return (1..400).count {
                CandidateOrder.order(listOf("우울","neutral"),{ it },emptyList(),random) { tag ->
                    MoodTagWeights.weight(setOf(tag),emptySet(),emptySet(),desired)
                }.first() == "우울"
            }
        }
        assertTrue(selectedFirst(setOf("우울")) > selectedFirst(emptySet()) + 100)
    }
    @Test fun desiredTargetStillPreservesRecentTrackAvoidanceAndAllPlayableCandidates() {
        val order = CandidateOrder.order(listOf("활기 넘침","other"),{it},listOf("활기 넘침"),Random(19)) {
            MoodTagWeights.weight(setOf(it),emptySet(),emptySet(),setOf("활기 넘침"))
        }
        assertEquals(listOf("other","활기 넘침"),order)
    }
    @Test fun hueAloneNeverCreatesDreamyMoodWithoutExplicitPreference() {
        val valid = measuredColor()
        for (hue in listOf(80f,180f,220f,300f)) {
            val result = recommend(vision=valid.copy(colors=valid.colors!!.copy(hueDegrees=hue)))
            assertFalse("hue=$hue",result.moodKeywords.contains("몽환적인"))
        }
        val selected = engine.recommend(RecommendationRequest(null,env,
            UserPreferences(preferredMoods=setOf("몽환적인")),nowEpochMs=now))
        assertTrue(selected.moodKeywords.contains("몽환적인"))
    }
    @Test fun missingObservationsDoNotBecomeCalmOrSadEvidence() {
        val result = recommend(EnvironmentSnapshot(zoneId="Asia/Seoul"))
        for (mood in listOf("우울","차분한","잔잔한","편안한")) assertFalse(result.moodKeywords.contains(mood))
    }
    @Test fun offlineUnknownCaptureTimeDoesNotUseCurrentTimeButKeepsObservedColor() {
        val v = measuredColor().copy(frameTimeEpochMs=0,sceneObservations=SceneObservations(
            SceneObservationRuntime.DESKTOP_SEGMENTATION,SourceInfo("실제 offline RGB와 mask")))
        val result = engine.recommend(RecommendationRequest(v,EnvironmentSnapshot(zoneId="Asia/Seoul"),nowEpochMs=now))
        assertFalse(result.moodKeywords.any { it in setOf("야간","아침","낮","저녁") })
        assertTrue(result.moodKeywords.contains("따뜻한"))
        assertTrue(result.reasons.any { it.contains("현재 시각으로 대체하지") })
    }
    @Test fun accumulatedWindowDrivesScoreAndUserTargetDoesNotRewriteEvidenceLedger() {
        val frames = (0..49).map { i -> SceneSample(i*1_000L,measuredColor().copy(frameTimeEpochMs=now-49_000+i*1_000)) }
        val window = SceneWindow(50_000,receivedFrameCount=50,samples=frames)
        val request = RecommendationRequest(null,env,sceneWindow=window,nowEpochMs=now)
        val actual = engine.recommend(request)
        val selected = engine.recommend(request.copy(preferences=UserPreferences(preferredMoods=setOf("우울"))))
        assertEquals(50,actual.scoreSummary!!.sampledBucketCount)
        assertTrue(actual.moodKeywords.contains("따뜻한"))
        assertEquals(actual.scoreSummary,selected.scoreSummary)
        assertTrue(selected.moodKeywords.contains("우울"))
        assertFalse(selected.moodKeywords.contains("따뜻한"))
    }
    @Test fun explicitMissingTimeOverridesEvaluationClockWithoutDiscardingWindow() {
        val request = RecommendationRequest(null,env,nowEpochMs=now,timeContextAvailable=false,
            sceneWindow=SceneWindow(50_000,samples=listOf(SceneSample(0,measuredColor()))))
        val result = engine.recommend(request)
        assertFalse(result.moodKeywords.any { it in setOf("야간","아침","낮","저녁") })
        assertTrue(result.moodKeywords.contains("따뜻한"))
        assertTrue(result.reasons.any { it.contains("현재 시각으로 대체하지") })
        val empty = engine.recommend(request.copy(sceneWindow=SceneWindow(50_000)))
        assertFalse(empty.moodKeywords.any { it in setOf("야간","아침","낮","저녁") })
    }
}
