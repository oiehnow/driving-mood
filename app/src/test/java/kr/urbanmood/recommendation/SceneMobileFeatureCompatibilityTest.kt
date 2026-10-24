package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

/** Compatibility only: synthetic measured stats; no Android model execution. */
class SceneMobileFeatureCompatibilityTest {
    private val scorer=SceneMoodScorer()
    private val stats=LightPixelStats(100,listOf(100,0,0,0,0,0,0,0),listOf(100,0,0,0),80f,80f,0f)
    private fun feature(version:String="semantic-contrast-mobile-v1")=LightFeatureObservation(version,100,100,stats,
        listOf(LightGroupFeature("actual",LightGroupKind.LOCAL_CONTRAST,stats)),
        LightFeatureQuality(flags=listOf("SEMANTIC_MASK_QUALITY_UNEVALUATED")))
    private fun window(f:LightFeatureObservation,runtime:SceneObservationRuntime=SceneObservationRuntime.TEST_FIXTURE)=
        SceneWindow(30_000,receivedFrameCount=30,samples=(0 until 30).map { i ->
            SceneSample(i*1000L,VisionResult(1_000_000+i*1000L,AnalysisStatus.LOW_CONFIDENCE,method=AnalysisMethod.HYBRID,
                sceneObservations=SceneObservations(runtime,SourceInfo("의미분할 통계 계약 fixture"),
                    regions=listOf(RegionEvidence("actual",0f,0f,1f,1f,"관측",RegionEvidenceKind.AMBIENT_LIGHT,RegionEvidenceSource.PIXEL_HEURISTIC)),
                    lightFeatures=f))) })

    @Test fun mobileVersionHasIdenticalStrengthAndInterpretationToSameSupportedStats() {
        val mobile=scorer.score(window(feature()))
        val reference=scorer.score(window(feature("semantic-contrast-rgb-v3")))
        assertEquals(reference,mobile)
        assertEquals(listOf("따뜻한"),scorer.keywords(mobile))
        val contribution=mobile.scores.first().contributions.first()
        assertEquals(.56f,contribution.delta!!,.00001f)
        assertNull(contribution.measurementQuality)
        assertFalse(mobile.notes.any { it.contains("개발 후보") })
        assertTrue(mobile.notes.contains("의미 마스크 품질 미평가"))
    }
    @Test fun onlyExactMobileVersionIsAcceptedAndBadStatsStillFailClosed() {
        val good=feature()
        listOf(good.copy(measurementVersion="semantic-contrast-mobile-v2"),
            good.copy(summary=stats.copy(categoryCounts=listOf(100))),
            good.copy(groups=emptyList()),
            good.copy(groups=listOf(good.groups!!.single().copy(regionId="missing")))).forEach { invalid ->
            val result=scorer.score(window(invalid))
            assertNull(result.scores.first().score)
            assertTrue(scorer.keywords(result).isEmpty())
            assertTrue(result.notes.any { it.contains("새 관측 미반영") })
        }
    }
    @Test fun mobileZeroIsMeasuredZeroWithUnknownColorNotMissingFeature() {
        val zero=LightPixelStats(0,List(8){0},List(4){0},0f,0f,0f)
        val result=scorer.score(window(feature().copy(summary=zero,groups=emptyList())))
        assertEquals(0f,result.scores.first { it.keyword=="시각적 활력" }.score!!,0f)
        assertNull(result.scores.first { it.keyword=="따뜻한" }.score)
        assertFalse(result.notes.any { it.contains("새 관측 미반영") })
        assertTrue(scorer.keywords(result).isEmpty())
    }
    @Test fun recordedMobileUsesCaptureEndAndKeepsRequestedMusicSeparate() {
        val request=RecommendationRequest(null,EnvironmentSnapshot(),nowEpochMs=99_000_000,
            observationEndEpochMs=1_030_000,sceneWindow=window(feature(),SceneObservationRuntime.ANDROID_RECORDED))
        val engine=MoodRecommendationEngine()
        val actual=engine.recommend(request)
        val chosen=engine.recommend(request.copy(preferences=UserPreferences(preferredMoods=setOf("차가운"))))
        assertTrue(actual.moodKeywords.contains("따뜻한"))
        assertTrue(chosen.moodKeywords.contains("차가운"))
        assertFalse(chosen.moodKeywords.contains("따뜻한"))
        assertEquals(actual.scoreSummary,chosen.scoreSummary)
        assertFalse(engine.recommend(request.copy(observationEndEpochMs=null)).moodKeywords.contains("따뜻한"))
    }
}

