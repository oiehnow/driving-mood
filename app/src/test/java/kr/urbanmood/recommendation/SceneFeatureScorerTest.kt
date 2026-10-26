package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SceneFeatureScorerTest {
    private val scorer=SceneMoodScorer()
    private fun stats(warm:Int=100,cool:Int=0,unknown:Int=0,neutral:Int=0):LightPixelStats = LightPixelStats(
        warm+cool+unknown+neutral,listOf(warm,0,0,cool,0,0,neutral,unknown),listOf(warm+cool+neutral,0,0,unknown),
        (warm+cool)*.8f,warm*.8f,cool*.8f)
    private fun frame(s:LightPixelStats=stats(),quality:Float?=null,groups:Boolean=true,runtime:SceneObservationRuntime=SceneObservationRuntime.TEST_FIXTURE):VisionResult {
        val region=RegionEvidence("measured",.01f,.01f,.99f,.99f,"실제 관측 영역",RegionEvidenceKind.OTHER,RegionEvidenceSource.PIXEL_HEURISTIC)
        return VisionResult(1_000_000,AnalysisStatus.LOW_CONFIDENCE,method=AnalysisMethod.HYBRID,
            colors=ColorFeatures(hueDegrees=220f,saturation=1f,brightness=1f,luminousAreaRatio=.8f),
            vehicleMaskApplied=true,vehicleExcludedRatio=.9f,colorMeasurementConfidence=0f,lightMeasurementConfidence=0f,
            sceneObservations=SceneObservations(runtime,SourceInfo("실제 입력 계약 fixture"),
                ambientLights=AmbientLightObservation(100,.8f,true,1f),
                semantic=SemanticSceneObservation(signboardAreaRatio=.8f,measurementQuality=1f),regions=listOf(region),
                lightFeatures=LightFeatureObservation("accepted-light-rgb-v1",100,100,s,
                    if(groups) listOf(LightGroupFeature("measured",LightGroupKind.LOCAL_CONTRAST,s)) else null,
                    LightFeatureQuality(quality))))
    }
    private fun window(vararg input:VisionResult,duration:Long=input.size*1_000L)=SceneWindow(duration,receivedFrameCount=input.size,
        samples=input.mapIndexed { i,v -> SceneSample(i*1_000L,v.copy(frameTimeEpochMs=v.frameTimeEpochMs+i*1_000)) })
    private fun mood(summary:MoodScoreSummary,word:String)=summary.scores.first { it.keyword==word }
    private fun score(f:VisionResult)=scorer.score(window(f))

    @Test fun qualityAndLegacyExclusionNeverEraseMeasuredNewColorArea() {
        val scores=listOf(null,0f,.2f,1f,Float.NaN).map { score(frame(quality=it)) }
        scores.forEach { assertEquals(.56f,mood(it,"따뜻한").score!!,.00001f) }
        assertEquals(ContributionBasis.OBSERVED_FEATURE_STRENGTH,mood(scores[0],"따뜻한").contributions.first().basis)
        assertNull(mood(scores[0],"따뜻한").contributions.first().measurementQuality)
        assertEquals(0f,mood(scores[1],"따뜻한").contributions.first().measurementQuality!!,0f)
        assertNull(mood(scores.last(),"따뜻한").contributions.first().measurementQuality)
        assertTrue(scores.last().notes.any { it.contains("품질 숫자") })
    }
    @Test fun onePixelHasSmallRealStrengthButCannotBecomeAnAutomaticMood() {
        val result=score(frame(stats(warm=1)))
        assertEquals(.0056f,mood(result,"따뜻한").score!!,.000001f)
        assertTrue(scorer.keywords(result).isEmpty())
    }
    @Test fun mixedColorsSurviveUndefinedLegacyHueAndUseActualRegionalSums() {
        val s=stats(100,200)
        val first=frame(s)
        val o=first.sceneObservations!!
        val regions=listOf(o.regions[0].copy(id="red",left=0f,right=.4f),o.regions[0].copy(id="blue",left=.6f,right=1f))
        val f=first.copy(colors=first.colors!!.copy(hueDegrees=null),sceneObservations=o.copy(regions=regions,
            lightFeatures=o.lightFeatures!!.copy(groups=listOf(LightGroupFeature("red",LightGroupKind.LOCAL_CONTRAST,stats(100)),
                LightGroupFeature("blue",LightGroupKind.EXTENDED_BRIGHT_SURFACE,stats(0,200))))))
        val result=scorer.score(window(*Array(30) { f }))
        assertTrue(scorer.keywords(result).containsAll(listOf("따뜻한","차가운")))
        assertTrue(result.notes.any { it.contains("혼재") })
        val warm=mood(result,"따뜻한").contributions.first()
        assertEquals(.56f,warm.delta!!,.00001f)
        assertTrue(warm.evidence.filter { it.regionId=="blue" }.all { it.allocatedDelta==0f })
        assertEquals(warm.delta!!.toDouble(),warm.evidence.sumOf { it.allocatedDelta!!.toDouble() },.00001)
    }
    @Test fun missingZeroAndAllChannelUnknownStayDistinct() {
        val zero=score(frame(stats(0),groups=false))
        assertEquals(0f,mood(zero,"시각적 활력").score!!,0f)
        assertNull(mood(zero,"따뜻한").score)
        val unknown=score(frame(stats(0,unknown=100)))
        assertEquals(.35f,mood(unknown,"시각적 활력").score!!,.00001f)
        assertNull(mood(unknown,"차가운").score)
        assertTrue(unknown.notes.any { it.contains("세 채널") })
        val neutral=score(frame(stats(0,neutral=100)))
        assertEquals(0f,mood(neutral,"따뜻한").score!!,0f)
        assertTrue(scorer.keywords(neutral).isEmpty())
    }
    @Test fun oneChannelUpperLimitPreservesColorCategory() {
        val s=stats().copy(upperChannelCounts=listOf(0,100,0,0))
        val result=score(frame(s))
        assertEquals(.56f,mood(result,"따뜻한").score!!,.00001f)
        assertTrue(scorer.keywords(result).contains("따뜻한"))
        assertTrue(result.notes.any { it.contains("일부 RGB 채널") })
    }
    @Test fun malformedNewInputDoesNotFallBackToLegacyStrongLightOrColor() {
        val valid=frame()
        val f=valid.sceneObservations!!.lightFeatures!!
        val invalid=listOf(f.copy(rasterWidth=0),f.copy(measurementVersion=""),f.copy(measurementVersion="unknown-future-version"),
            f.copy(summary=f.summary.copy(categoryCounts=listOf(100))),f.copy(summary=f.summary.copy(redYellowSaturationSum=Float.NaN)),
            f.copy(summary=f.summary.copy(cyanBlueSaturationSum=90f)),f.copy(summary=f.summary.copy(upperChannelCounts=listOf(0,0,0,100))),
            f.copy(groups=emptyList()),f.copy(groups=f.groups!!+f.groups[0]),f.copy(groups=listOf(f.groups!![0].copy(regionId="missing"))))
        invalid.forEach { bad ->
            val result=score(valid.copy(sceneObservations=valid.sceneObservations.copy(lightFeatures=bad)))
            assertEquals("scene-v2.0",result.ruleVersion)
            assertTrue(result.scores.all { it.score==null })
            assertTrue(scorer.keywords(result).isEmpty())
            assertTrue(result.notes.any { it.contains("미반영") })
        }
    }
    @Test fun unsupportedGroupsKeepSummaryButCannotInventRegionalDistribution() {
        val result=score(frame(stats(warm=1000),groups=false))
        assertEquals(5.6f,mood(result,"따뜻한").score!!,.00001f)
        assertTrue(mood(result,"따뜻한").contributions.first().evidence.isEmpty())
        assertFalse(scorer.keywords(result).contains("활기 넘침"))
    }
    @Test fun interpretationNeedsActualPersistenceAcrossRequestedWindow() {
        val f=frame()
        assertFalse(scorer.keywords(scorer.score(window(*Array(14) { f },duration=30_000))).contains("따뜻한"))
        assertTrue(scorer.keywords(scorer.score(window(*Array(15) { f },duration=30_000))).contains("따뜻한"))
        assertFalse(scorer.keywords(scorer.score(window(*Array(15) { f },duration=60_000))).contains("따뜻한"))
        assertTrue(scorer.keywords(scorer.score(window(*Array(30) { f },duration=60_000))).contains("따뜻한"))
    }
    @Test fun oldInputsRemainV12AndMixedWindowsCannotBackfillMissingNewObservations() {
        val newer=frame()
        val older=newer.copy(sceneObservations=newer.sceneObservations!!.copy(lightFeatures=null))
        assertEquals("scene-v1.2",score(older).ruleVersion)
        val mixed=scorer.score(window(newer,older,older))
        assertEquals("scene-v2.0",mixed.ruleVersion)
        assertFalse(scorer.keywords(mixed).contains("따뜻한"))
        assertEquals(.35f,mood(mixed,"시각적 활력").score!!,.00001f)
        assertFalse(mixed.scores.flatMap { it.contributions }.any { it.id=="ambient_lights" || it.id=="semantic_signs" })
    }
    @Test fun highwayLimitsVisualMusicInterpretationButRetainsMeasuredColorAndArea() {
        val initial=frame(stats(1000))
        val o=initial.sceneObservations!!
        val regions=listOf(o.regions[0].copy(id="left",left=0f,right=.4f),o.regions[0].copy(id="right",left=.6f,right=1f))
        val f=initial.copy(sceneObservations=o.copy(regions=regions,lightFeatures=o.lightFeatures!!.copy(groups=regions.map {
            LightGroupFeature(it.id,LightGroupKind.EXTENDED_BRIGHT_SURFACE,stats(500)) })))
        val normal=scorer.score(window(f))
        val highway=scorer.score(window(f),EnvironmentSnapshot(road=RoadContext(Availability.MANUAL,RoadType.HIGHWAY)))
        assertTrue(scorer.keywords(normal).contains("활기 넘침"))
        assertFalse(scorer.keywords(highway).contains("활기 넘침"))
        assertEquals(mood(normal,"따뜻한").score,mood(highway,"따뜻한").score)
        assertEquals(mood(normal,"시각적 활력").score,mood(highway,"시각적 활력").score)
        assertEquals(35f*.1f,mood(normal,"시각적 활력").score!!,.00001f) // no group-count points
    }
    @Test fun recordedCaptureEndOverridesLaterProcessingClockAndFutureAnchorIsRejected() {
        val f=frame(runtime=SceneObservationRuntime.ANDROID_RECORDED)
        val request=RecommendationRequest(null,EnvironmentSnapshot(),nowEpochMs=99_000_000,sceneWindow=window(*Array(30) { f }),observationEndEpochMs=1_030_000)
        val engine=MoodRecommendationEngine(Random(1))
        val result=engine.recommend(request)
        assertTrue(result.moodKeywords.contains("따뜻한"))
        assertTrue(result.reasons.any { it.contains("제공된 촬영") })
        assertFalse(engine.recommend(request.copy(observationEndEpochMs=100_000_000)).moodKeywords.contains("따뜻한"))
        val unknown=engine.recommend(request.copy(timeContextAvailable=false,observationEndEpochMs=null))
        assertTrue(unknown.moodKeywords.contains("따뜻한"))
        assertFalse(unknown.moodKeywords.any { it in setOf("아침","낮","저녁","야간") })
    }
    @Test fun desiredMoodOverridesMusicKeywordsWithoutChangingScene2Ledger() {
        val engine=MoodRecommendationEngine(Random(1))
        val r=RecommendationRequest(null,EnvironmentSnapshot(),nowEpochMs=1_030_000,sceneWindow=window(*Array(30) { frame() }))
        val actual=engine.recommend(r)
        val chosen=engine.recommend(r.copy(preferences=UserPreferences(preferredMoods=setOf("차가운"))))
        assertTrue(actual.moodKeywords.contains("따뜻한"))
        assertEquals(actual.scoreSummary,chosen.scoreSummary)
        assertFalse(chosen.moodKeywords.contains("따뜻한"))
        assertTrue(chosen.moodKeywords.contains("차가운"))
    }
    @Test fun accumulatorDetachesAllFeatureListsInBothDirections() {
        val cats=stats().categoryCounts.toMutableList()
        val channels=stats().upperChannelCounts.toMutableList()
        val flags=mutableListOf("source-unconfirmed","limited-view")
        val initial=frame()
        val o=initial.sceneObservations!!
        val f=o.lightFeatures!!
        val groups=f.groups!!.toMutableList()
        val input=initial.copy(sceneObservations=o.copy(lightFeatures=f.copy(summary=f.summary.copy(categoryCounts=cats,upperChannelCounts=channels),
            groups=groups,quality=f.quality.copy(flags=flags))))
        val a=SceneAccumulator(60_000)
        a.add(0,input)
        cats[0]=999;channels[0]=999;flags.clear();groups.clear()
        val first=a.snapshot().samples[0].vision.sceneObservations!!.lightFeatures!!
        assertEquals(100,first.summary.categoryCounts[0]);assertEquals(100,first.summary.upperChannelCounts[0])
        assertEquals(1,first.groups!!.size);assertEquals(2,first.quality.flags.size)
        (first.summary.categoryCounts as MutableList<Int>)[0]=999
        (first.groups[0].pixels.categoryCounts as MutableList<Int>)[0]=999
        (first.quality.flags as MutableList<String>).clear()
        val again=a.snapshot().samples[0].vision.sceneObservations!!.lightFeatures!!
        assertEquals(100,again.summary.categoryCounts[0]);assertEquals(100,again.groups!![0].pixels.categoryCounts[0]);assertEquals(2,again.quality.flags.size)
    }
    @Test fun peopleOnlyEvidenceSurvivesNewLightZeroAndKeepsItsOwnUnits() {
        val f=frame(stats(0),groups=false).let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            persons=PersonObservation(5,measurementQuality=.65f))) }
        val summary=score(f)
        assertEquals(35.75f,mood(summary,"활기 넘침").score!!,.00001f)
        assertEquals(0f,mood(summary,"시각적 활력").score!!,0f)
        assertTrue(scorer.keywords(summary).contains("활기 넘침"))
        assertTrue(mood(summary,"활기 넘침").contributions.none { it.id=="ambient_lights" || it.id.startsWith("semantic_sign") })
    }
    @Test fun skyAndWeatherRemainIndependentWhenNewColorIsPresent() {
        val f=frame().let { it.copy(sceneObservations=it.sceneObservations!!.copy(runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION,
            semantic=SemanticSceneObservation(skyAreaRatio=.6f,skyBrightness=.8f))) }
        val env=EnvironmentSnapshot(weather=WeatherSnapshot(Availability.MANUAL,temperatureC=18.0,precipitationMm=0.0,condition="강수 없음"))
        val summary=scorer.score(window(f),env)
        val fresh=mood(summary,"청량한")
        assertEquals(8f,fresh.score!!,.00001f)
        assertTrue(fresh.contributions.any { it.id=="sky_light_raw" && it.delta==8f })
        assertTrue(fresh.contributions.none { it.basis==ContributionBasis.WEATHER_RULE })
        val weather = summary.scores.single { row -> row.keyword=="청량한" && row.contributions.all { it.basis==ContributionBasis.WEATHER_RULE } }
        assertEquals(35f,weather.score!!,.00001f)
        assertTrue(weather.contributions.any { it.id=="weather_mild" && it.delta==25f })
        assertTrue(scorer.keywords(summary).containsAll(listOf("청량한","따뜻한")))
    }
    @Test fun semanticPersonPixelCannotBecomeDetectedPeopleLightSupport() {
        val initial=frame(stats(100),runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION)
        val o=initial.sceneObservations!!
        val regions=listOf(o.regions[0].copy(id="left",left=0f,right=.4f),o.regions[0].copy(id="right",left=.6f,right=1f))
        listOf(null,.65f).forEach { quality ->
            val f=initial.copy(sceneObservations=o.copy(persons=null,regions=regions,
                semantic=SemanticSceneObservation(personAreaRatio=.0001f,measurementQuality=quality),
                lightFeatures=o.lightFeatures!!.copy(groups=regions.map { LightGroupFeature(it.id,LightGroupKind.LOCAL_CONTRAST,stats(50)) })))
            val summary=scorer.score(window(*Array(30) { f }))
            assertFalse(scorer.keywords(summary).contains("활기 넘침"))
            assertTrue(mood(summary,"활기 넘침").contributions.any { it.id=="semantic_person_area" && (it.delta ?: 0f)>0 })
            assertEquals(0f,mood(summary,"시각적 활력").contributions.first { it.id=="interpret_people_light" }.observedValue!!,0f)
        }
    }
    @Test fun peopleAndDistributedLightUseConcurrentSupportNotAddingDifferentScoreUnits() {
        val initial=frame(stats(100))
        val o=initial.sceneObservations!!
        val regions=listOf(o.regions[0].copy(id="left",left=0f,right=.4f),o.regions[0].copy(id="right",left=.6f,right=1f))
        val f=initial.copy(sceneObservations=o.copy(persons=PersonObservation(1,measurementQuality=.65f),regions=regions,
            lightFeatures=o.lightFeatures!!.copy(groups=regions.map { LightGroupFeature(it.id,LightGroupKind.LOCAL_CONTRAST,stats(50)) })))
        val summary=scorer.score(window(*Array(30) { f }))
        assertEquals(7.15f,mood(summary,"활기 넘침").score!!,.00001f)
        assertEquals(.35f,mood(summary,"시각적 활력").score!!,.00001f)
        assertTrue(scorer.keywords(summary).contains("활기 넘침"))
        val separated=window(*Array(30) { i -> if(i<15) f.copy(sceneObservations=f.sceneObservations!!.copy(persons=null))
            else frame(stats(0),groups=false).let { it.copy(sceneObservations=it.sceneObservations!!.copy(persons=PersonObservation(1,measurementQuality=.65f))) } })
        assertFalse(scorer.keywords(scorer.score(separated)).contains("활기 넘침"))
    }
}
