package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class SceneMoodScorerTest {
    private val scorer = SceneMoodScorer()
    private fun region(id:String, kind:RegionEvidenceKind, source:RegionEvidenceSource=RegionEvidenceSource.OBJECT_DETECTOR) =
        RegionEvidence(id,.1f,.1f,.3f,.4f,id,kind,source)
    private fun frame(people:Int?=null, lights:Int?=null, hue:Float?=null, vehicle:Float=.1f, time:Long=1_000_000) =
        VisionResult(time,AnalysisStatus.LOW_CONFIDENCE,
            colors=ColorFeatures(hueDegrees=hue,saturation=.8f,brightness=.8f,luminousAreaRatio=.2f),
            vehicleExcludedRatio=vehicle,method=AnalysisMethod.HYBRID,vehicleMaskApplied=true,
            colorMeasurementConfidence=.8f,lightMeasurementConfidence=.8f,
            sceneObservations=SceneObservations(SceneObservationRuntime.TEST_FIXTURE,SourceInfo("fixture"),
                persons=people?.let { PersonObservation(it,measurementQuality=1f) },
                ambientLights=lights?.let { AmbientLightObservation(it,.2f,true,1f) },
                regions=(0 until (people ?: 0)).map { region("p$it",RegionEvidenceKind.PERSON) } +
                    (0 until (lights ?: 0)).map { region("l$it",RegionEvidenceKind.AMBIENT_LIGHT,RegionEvidenceSource.PIXEL_HEURISTIC) }))
    private fun window(vararg frames:VisionResult) = SceneWindow(frames.size*1_000L,receivedFrameCount=frames.size,
        samples=frames.mapIndexed { i,v -> SceneSample(i*1_000L,v.copy(frameTimeEpochMs=v.frameTimeEpochMs+i*1_000)) })
    private fun score(w:SceneWindow, key:String, env:EnvironmentSnapshot?=null) = scorer.score(w,env).scores.first { it.keyword==key }

    @Test fun missingIsNullButSupportedZeroIsNotMissingOrSad() {
        val missing = score(window(frame()),"활기 넘침")
        assertNull(missing.score)
        val zero = score(window(frame(people=0)),"활기 넘침")
        assertEquals(0f,zero.score!!,0f)
        assertFalse(scorer.keywords(scorer.score(window(frame(people=0)))).contains("우울"))
    }
    @Test fun personContributionDoesNotRequireColorMaskOrLightQuality() {
        val person = frame(people=3).copy(vehicleMaskApplied=false,colorMeasurementConfidence=null,lightMeasurementConfidence=null)
        assertEquals(33f,score(window(person),"활기 넘침").score!!,.001f)
    }
    @Test fun samePersonForFiftySecondsIsAverageOneNotFifty() {
        val w = window(*Array(50) { frame(people=1) })
        val result = score(w,"활기 넘침")
        assertEquals(11f,result.score!!,.001f)
        assertEquals(1f,result.contributions.first { it.id=="persons" }.observedValue!!,0f)
    }
    @Test fun duplicatedFrameDensityDoesNotBoostScore() {
        fun aggregate(step:Int):SceneWindow {
            val a = SceneAccumulator()
            for (t in 0 until 50_000 step step) a.add(t.toLong(),frame(people=2,lights=2,time=1_000_000L+t))
            return a.snapshot()
        }
        assertEquals(score(aggregate(1000),"활기 넘침").score,score(aggregate(100),"활기 넘침").score)
    }
    @Test fun alreadyExcludedAmbientLightIsNotPenalizedAgainForVehicleRatio() {
        val a = score(window(frame(lights=4,vehicle=.1f)),"활기 넘침")
        val b = score(window(frame(lights=4,vehicle=.8f)),"활기 넘침")
        assertEquals(a.score,b.score)
        assertTrue(b.contributions.none { it.id.contains("vehicle") && (it.delta ?: 0f)<0 })
        assertFalse(scorer.keywords(scorer.score(window(frame(lights=8)))).contains("활기 넘침"))
    }
    @Test fun regionAllocationsSumToActualGroupDeltaNotCapPerPerson() {
        val result = score(window(frame(people=4,lights=3)),"활기 넘침")
        val group = result.contributions.first { it.id=="persons" }
        assertEquals(44f,group.delta!!,.001f)
        assertEquals(4,group.evidence.size)
        assertEquals(group.delta!!.toDouble(),group.evidence.sumOf { it.allocatedDelta!!.toDouble() },.001)
        assertTrue(group.evidence.all { it.allocatedDelta==11f && it.sampleElapsedMs==0L })
    }
    @Test fun invalidOrSemanticPersonBoxesCannotReceiveDetectorPoints() {
        val f = frame(people=1)
        val wrong = f.copy(sceneObservations=f.sceneObservations!!.copy(regions=listOf(
            region("mask-person",RegionEvidenceKind.PERSON,RegionEvidenceSource.SEMANTIC_COMPONENT))))
        assertTrue(score(window(wrong),"활기 넘침").contributions.first { it.id=="persons" }.evidence.isEmpty())
    }
    @Test fun highwaySuppressesLightAndSignButPreservesIndependentPersons() {
        val f = frame(people=3,lights=8,hue=30f)
        val env = EnvironmentSnapshot(road=RoadContext(Availability.MANUAL,RoadType.HIGHWAY))
        assertEquals(33f,score(window(f),"활기 넘침",env).score!!,.001f)
        assertNull(score(window(f),"따뜻한",env).score)
    }
    @Test fun hueCreatesOnlyTemperatureAxesAndCounterColorIsAuditable() {
        val summary = scorer.score(window(frame(hue=30f),frame(hue=220f)))
        assertFalse(scorer.keywords(summary).contains("몽환적인"))
        assertTrue(summary.scores.first { it.keyword=="따뜻한" }.contributions.any { (it.delta ?: 0f)<0 })
        summary.scores.forEach { mood -> mood.score?.let {
            assertTrue(it in 0f..100f)
            assertEquals(it.toDouble(),mood.contributions.sumOf { c -> (c.delta ?: 0f).toDouble() },.001)
        } }
    }
    @Test fun scoresIgnorePostWindowAndUnsupportedQuality() {
        val w = SceneWindow(1000,samples=listOf(SceneSample(0,frame(people=1)),SceneSample(1000,frame(people=50,time=1_001_000))))
        assertEquals(11f,score(w,"활기 넘침").score!!,.001f)
        val invalid = frame(people=2).let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            persons=PersonObservation(2,measurementQuality=Float.NaN))) }
        assertNull(score(window(invalid),"활기 넘침").score)
    }
    @Test fun rawSemanticPeopleCanSupportLimitedAreaEvidenceWithoutFakeQualityOrCount() {
        val f = frame(people=0).let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION,
            semantic=SemanticSceneObservation(personAreaRatio=.05f),
            regions=listOf(region("mask-person",RegionEvidenceKind.PERSON,RegionEvidenceSource.SEMANTIC_COMPONENT)))) }
        val result = score(window(f),"활기 넘침")
        val mask = result.contributions.first { it.id=="semantic_person_area" }
        assertEquals(10f,mask.delta!!,.001f)
        assertNull(mask.measurementQuality)
        assertEquals("화면 비율",mask.unit)
        assertEquals(.05f,mask.observedValue!!,0f)
        assertEquals(0f,result.contributions.first { it.id=="persons" }.observedValue!!,0f)
        val detected = f.copy(sceneObservations=f.sceneObservations!!.copy(persons=PersonObservation(2,measurementQuality=1f)))
        assertEquals(22f,score(window(detected),"활기 넘침").score!!,.001f)
    }
    @Test fun semanticFallbackAndDetectorAcrossBucketsDoNotDoubleCount() {
        val detector = frame(people=5)
        val mask = frame().let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION,
            semantic=SemanticSceneObservation(personAreaRatio=.1f))) }
        assertEquals(37.5f,score(window(detector,mask),"활기 넘침").score!!,.001f) // mean(55,20)
    }
    @Test fun firstOldOrFutureCameraSampleIsRejectedAgainstExplicitEvaluationTime() {
        for (time in listOf(1L,2_000_000L)) {
            val f = frame(people=5,time=time).let { it.copy(sceneObservations=it.sceneObservations!!.copy(runtime=SceneObservationRuntime.ANDROID_CAMERA)) }
            val result = scorer.score(window(f),evaluationTimeEpochMs=1_000_000)
            assertNull(result.scores.first { it.keyword=="활기 넘침" }.score)
        }
    }
    @Test fun unmaskedVehicleBoxesAreNotFalselyReportedAsAlreadyExcluded() {
        val f = frame().let { it.copy(vehicleMaskApplied=false,sceneObservations=it.sceneObservations!!.copy(regions=listOf(
            region("car",RegionEvidenceKind.VEHICLE)))) }
        val contribution = score(window(f),"활기 넘침").contributions.first { it.id=="vehicle_exclusion" }
        assertTrue(contribution.evidence.isEmpty())
    }
    @Test fun currentFrameAndAccumulatedRegionSharesHaveDistinctSampleTimes() {
        val result = score(window(frame(people=1),frame(people=3)),"활기 넘침")
        val contribution = result.contributions.first { it.id=="persons" }
        assertEquals(22f,contribution.delta!!,.001f)
        assertEquals(setOf(0L,1000L),contribution.evidence.map { it.sampleElapsedMs }.toSet())
        assertEquals(22.0,contribution.evidence.sumOf { it.allocatedDelta!!.toDouble() },.001)
    }
    @Test fun legacySignAreaAloneDoesNotBoostHighwayUnknownOrUrbanRoad() {
        val f = frame(people=2,lights=2).let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            semantic=SemanticSceneObservation(signboardAreaRatio=.1f,signboardCandidateCount=1,measurementQuality=1f),
            regions=it.sceneObservations.regions+region("sign",RegionEvidenceKind.SIGNBOARD,RegionEvidenceSource.SEMANTIC_COMPONENT))) }
        assertNull(score(window(f),"활기 넘침").contributions.first { it.id=="semantic_signs" }.delta)
        val highway = EnvironmentSnapshot(road=RoadContext(Availability.MANUAL,RoadType.HIGHWAY))
        assertNull(score(window(f),"활기 넘침",highway).contributions.first { it.id=="semantic_signs" }.delta)
        val urban = EnvironmentSnapshot(road=RoadContext(Availability.MANUAL,RoadType.URBAN))
        assertNull(score(window(f),"활기 넘침",urban).contributions.first { it.id=="semantic_signs" }.delta)
    }
    @Test fun lowButMeasuredPersonAndLightQualityContributesWeaklyWithoutColorGate() {
        val f = frame(people=5,lights=8).let { it.copy(colorMeasurementConfidence=null,
            sceneObservations=it.sceneObservations!!.copy(
                persons=it.sceneObservations.persons!!.copy(measurementQuality=.35f),
                ambientLights=it.sceneObservations.ambientLights!!.copy(measurementQuality=.35f))) }
        assertTrue(score(window(f),"활기 넘침").score!! in 20f..34f)
        assertFalse(scorer.keywords(scorer.score(window(f))).contains("활기 넘침"))
    }
    private fun distributedLights() = (0..7).map { i ->
        val x = if(i%2==0) .1f else .7f
        val y = if(i%4<2) .1f else .7f
        RegionEvidence("light-$i",x,y,x+.1f,y+.1f,"실제 광원 후보",RegionEvidenceKind.AMBIENT_LIGHT,RegionEvidenceSource.PIXEL_HEURISTIC)
    }
    @Test fun distributedBrightCandidatesCanSupportVigorWithoutPersonEvidence() {
        val f = frame(lights=8).let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            ambientLights=it.sceneObservations.ambientLights!!.copy(measurementQuality=.8f),regions=distributedLights())) }
        val summary = scorer.score(window(f))
        assertTrue(scorer.keywords(summary).contains("활기 넘침"))
        assertNull(summary.scores.first { it.keyword=="활기 넘침" }.contributions.first { it.id=="persons" }.delta)
        val single = f.copy(sceneObservations=f.sceneObservations!!.copy(ambientLights=AmbientLightObservation(1,.8f,true,1f),regions=distributedLights().take(1)))
        assertFalse(scorer.keywords(scorer.score(window(single))).contains("활기 넘침"))
    }
    @Test fun rawSkyAreaAndBrightnessContributeWithUnknownQualityPreserved() {
        val f = frame().let { it.copy(sceneObservations=it.sceneObservations!!.copy(runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION,
            semantic=SemanticSceneObservation(skyAreaRatio=.5f,skyBrightness=.8f),
            regions=listOf(region("sky",RegionEvidenceKind.SKY,RegionEvidenceSource.SEMANTIC_COMPONENT)))) }
        val result = score(window(f),"청량한")
        assertEquals(8f,result.score!!,.001f)
        val raw = result.contributions.first { it.id=="sky_light_raw" }
        assertNull(raw.measurementQuality)
        assertEquals(8.0,raw.evidence.sumOf { it.allocatedDelta!!.toDouble() },.001)
    }
    @Test fun sceneColorAllocatesOnceAcrossRealLightReferencesWithoutClaimingPerLightColor() {
        val f = frame(lights=8,hue=30f).let { it.copy(sceneObservations=it.sceneObservations!!.copy(regions=distributedLights())) }
        val contribution = score(window(f),"따뜻한").contributions.first { it.id=="warm_color" }
        assertEquals(8,contribution.evidence.size)
        assertEquals(contribution.delta!!.toDouble(),contribution.evidence.sumOf { it.allocatedDelta!!.toDouble() },.001)
        assertTrue(contribution.reason.contains("개별 광원"))
    }
    @Test fun legacyRawSignIntersectionCannotAllocateWithoutPerRegionDeduplication() {
        val f = frame().let { it.copy(sceneObservations=it.sceneObservations!!.copy(runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION,
            semantic=SemanticSceneObservation(signboardAreaRatio=.1f,signboardBrightAreaRatio=.05f,signboardCandidateCount=1),
            regions=listOf(region("sign",RegionEvidenceKind.SIGNBOARD,RegionEvidenceSource.SEMANTIC_COMPONENT)))) }
        val urban = EnvironmentSnapshot(road=RoadContext(Availability.MANUAL,RoadType.URBAN))
        val result = score(window(f),"활기 넘침",urban)
        val raw = result.contributions.first { it.id=="semantic_sign_bright_raw" }
        assertNull(result.score)
        assertNull(raw.measurementQuality)
        assertTrue(raw.evidence.isEmpty())
        assertNull(score(window(f),"활기 넘침").score)
        val invalid = f.copy(sceneObservations=f.sceneObservations!!.copy(semantic=f.sceneObservations.semantic!!.copy(signboardBrightAreaRatio=.2f)))
        assertNull(score(window(invalid),"활기 넘침",urban).score)
        val evaluated = f.copy(sceneObservations=f.sceneObservations.copy(semantic=f.sceneObservations.semantic!!.copy(measurementQuality=1f)))
        assertNull(score(window(evaluated),"활기 넘침",urban).score)
        assertNull(score(window(f,evaluated),"활기 넘침",urban).score)
    }
    @Test fun rawAndEvaluatedSkyUseOneBucketDenominatorAndRejectKnownLowQuality() {
        val f = frame().let { it.copy(sceneObservations=it.sceneObservations!!.copy(runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION,
            semantic=SemanticSceneObservation(skyAreaRatio=.5f,skyBrightness=1f))) }
        fun quality(q:Float) = f.copy(sceneObservations=f.sceneObservations!!.copy(semantic=f.sceneObservations.semantic!!.copy(measurementQuality=q)))
        assertEquals(17.5f,score(window(f,quality(1f)),"청량한").score!!,.001f)
        assertNull(score(window(quality(.3f)),"청량한").score)
    }
    private fun signFrame(rows:List<SignLightEvidence>?, q:Float?=null):VisionResult {
        val ids = rows?.map { it.regionId }?.distinct() ?: listOf("sign")
        return frame().let { it.copy(sceneObservations=it.sceneObservations!!.copy(
            runtime=SceneObservationRuntime.DESKTOP_SEGMENTATION,
            semantic=SemanticSceneObservation(signboardAreaRatio=.8f,signboardCandidateCount=ids.size,
                measurementQuality=q,signboardBrightAreaRatio=.8f,signLightRegions=rows),
            regions=ids.map { id -> region(id,RegionEvidenceKind.SIGNBOARD,RegionEvidenceSource.SEMANTIC_COMPONENT) })) }
    }
    private fun signGroup(f:VisionResult,env:EnvironmentSnapshot?=null) = score(window(f),"활기 넘침",env)
        .contributions.first { it.id=="semantic_sign_bright_raw" }
    @Test fun uniqueSignBrightnessWorksWithoutLocationButNotKnownHighway() {
        val f = signFrame(listOf(SignLightEvidence("small",.01f,0f,.01f)))
        assertEquals(1f,signGroup(f).delta!!,.0001f)
        assertNull(signGroup(f).measurementQuality)
        assertEquals(.01f,signGroup(f).observedValue!!,.0001f)
        val highway = EnvironmentSnapshot(road=RoadContext(Availability.MANUAL,RoadType.HIGHWAY))
        assertNull(signGroup(f,highway).delta)
        val staleHighway = EnvironmentSnapshot(road=RoadContext(Availability.AVAILABLE,RoadType.HIGHWAY))
        assertEquals(1f,signGroup(f,staleHighway).delta!!,.0001f)
    }
    @Test fun fullyOverlappingSignAddsZeroToExistingAmbientScore() {
        val base = frame(lights=1)
        val f = signFrame(listOf(SignLightEvidence("sign",.04f,.04f,0f)))
        val combined = base.copy(sceneObservations=base.sceneObservations!!.copy(semantic=f.sceneObservations!!.semantic,
            regions=base.sceneObservations.regions+f.sceneObservations.regions))
        assertEquals(score(window(base),"활기 넘침").score,score(window(combined),"활기 넘침").score)
        val group = signGroup(combined)
        assertEquals(0f,group.delta!!,0f)
        assertEquals(0f,group.evidence.single().allocatedDelta!!,0f)
    }
    @Test fun partialOverlapAllocatesOnlyUniqueAreaProportionAndPreservesZeroRows() {
        val f = signFrame(listOf(SignLightEvidence("a",.03f,.02f,.01f),SignLightEvidence("b",.03f,0f,.03f),
            SignLightEvidence("dark",0f,0f,0f),SignLightEvidence("overlap",.02f,.02f,0f)))
        val group = signGroup(f)
        assertEquals(4f,group.delta!!,.0001f)
        val shares = group.evidence.associate { it.regionId to it.allocatedDelta!! }
        assertEquals(1f,shares.getValue("a"),.0001f)
        assertEquals(3f,shares.getValue("b"),.0001f)
        assertEquals(0f,shares.getValue("dark"),0f)
        assertEquals(0f,shares.getValue("overlap"),0f)
        assertEquals(group.delta!!.toDouble(),shares.values.sumOf { it.toDouble() },.0001)
    }
    @Test fun nullEmptyAndMeasuredZeroSignsRemainDistinct() {
        assertNull(signGroup(signFrame(null)).delta)
        val empty = signGroup(signFrame(emptyList()))
        assertEquals(0f,empty.delta!!,0f)
        assertTrue(empty.evidence.isEmpty())
        val zero = signGroup(signFrame(listOf(SignLightEvidence("dark",0f,0f,0f))))
        assertEquals(0f,zero.delta!!,0f)
        assertEquals(0f,zero.evidence.single().allocatedDelta!!,0f)
    }
    @Test fun excludedSignRetainsMeasuredZeroReferenceButCannotReceivePositivePoints() {
        fun excluded(f:VisionResult) = f.copy(sceneObservations=f.sceneObservations!!.copy(
            regions=f.sceneObservations.regions.map { it.copy(exclusionReason="실제 hard exclusion") }))
        val zero = signGroup(excluded(signFrame(listOf(SignLightEvidence("s",0f,0f,0f)))))
        assertEquals(0f,zero.delta!!,0f)
        assertEquals(0f,zero.evidence.single().allocatedDelta!!,0f)
        assertNull(signGroup(excluded(signFrame(listOf(SignLightEvidence("s",.01f,0f,.01f))))).delta)
    }
    @Test fun incompleteUnknownDuplicateAndInvalidRegionReferencesRejectWholeSignGroup() {
        val f = signFrame(listOf(SignLightEvidence("a",.01f,0f,.01f),SignLightEvidence("b",.01f,0f,.01f)))
        val o = f.sceneObservations!!
        val s = o.semantic!!
        val invalid = listOf(
            o.copy(semantic=s.copy(signLightRegions=s.signLightRegions!!.take(1))),
            o.copy(semantic=s.copy(signLightRegions=listOf(s.signLightRegions!![0],s.signLightRegions[0]))),
            o.copy(semantic=s.copy(signLightRegions=s.signLightRegions!!.map { it.copy(regionId="unknown") })),
            o.copy(semantic=s.copy(signboardCandidateCount=null)),
            o.copy(regions=o.regions.take(1)),
            o.copy(regions=o.regions.map { it.copy(right=Float.NaN) }))
        for(obs in invalid) {
            val group = signGroup(f.copy(sceneObservations=obs))
            assertNull(group.delta)
            assertTrue(group.evidence.isEmpty())
        }
    }
    @Test fun signRatiosRejectNonFiniteBoundsRelationsAndImpossibleArea() {
        val invalid = listOf(SignLightEvidence("s",Float.NaN,0f,0f),SignLightEvidence("s",.01f,Float.POSITIVE_INFINITY,0f),
            SignLightEvidence("s",.01f,0f,-.01f),SignLightEvidence("s",1.1f,0f,1.1f),
            SignLightEvidence("s",.01f,.02f,0f),SignLightEvidence("s",.03f,.01f,.01f),
            SignLightEvidence("s",.5f,0f,.5f)) // exceeds actual component bounding area
        for(row in invalid) assertNull("$row",signGroup(signFrame(listOf(row))).delta)
        val f = signFrame(listOf(SignLightEvidence("s",.02f,0f,.02f)))
        assertNull(signGroup(f.copy(sceneObservations=f.sceneObservations!!.copy(
            semantic=f.sceneObservations.semantic!!.copy(signboardAreaRatio=.01f)))).delta)
        assertNotNull(signGroup(signFrame(listOf(SignLightEvidence("s",.02f,.01f,.0100005f)))).delta)
    }
    @Test fun uniqueSignCapsQualityAndSharedBucketDenominatorStayBounded() {
        val rows = listOf(SignLightEvidence("a",.05f,0f,.05f),SignLightEvidence("b",.05f,0f,.05f))
        val raw = signFrame(rows)
        val known = signFrame(rows,1f)
        assertEquals(5f,score(window(raw),"활기 넘침").score!!,.0001f)
        assertEquals(10f,score(window(known),"활기 넘침").score!!,.0001f)
        assertEquals(7.5f,score(window(raw,known),"활기 넘침").score!!,.0001f)
        assertNull(score(window(signFrame(rows,.3f)),"활기 넘침").score)
        assertNull(score(window(signFrame(rows,Float.NaN)),"활기 넘침").score)
        val groups = score(window(raw,known),"활기 넘침").contributions.filter { it.id.startsWith("semantic_sign") }
        groups.forEach { g -> assertEquals(g.delta!!.toDouble(),g.evidence.sumOf { it.allocatedDelta!!.toDouble() },.0001) }
    }
}
