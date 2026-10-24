package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import kr.urbanmood.context.EnvironmentPolicy
import kotlin.math.abs
import kotlin.math.min

/** Shared desktop/app computation. Inputs are explicit; never reads the system clock or performs IO. */
class SceneMoodScorer {
    private data class Entry(val sample:SceneSample, val value:Float, val quality:Float?, val signal:Float,
        val regions:List<RegionEvidence> = emptyList(), val completeRegions:Boolean = false,
        val regionWeights:List<Float>? = null)

    fun score(window:SceneWindow, environment:EnvironmentSnapshot?=null, evaluationTimeEpochMs:Long?=null):MoodScoreSummary {
        val normalized = runCatching {
            SceneAccumulator(window.durationMs,window.bucketDurationMs).also { a ->
                window.samples.forEach { a.add(it.elapsedMs,it.vision) }
            }.snapshot()
        }.getOrElse { SceneWindow(0) }
        val samples = normalized.samples.filter {
            it.vision.status in setOf(AnalysisStatus.OK,AnalysisStatus.LOW_CONFIDENCE) && it.vision.method != AnalysisMethod.NONE &&
                (evaluationTimeEpochMs==null || it.vision.sceneObservations?.runtime==SceneObservationRuntime.DESKTOP_SEGMENTATION ||
                    EnvironmentPolicy.fresh(it.vision.frameTimeEpochMs,evaluationTimeEpochMs,normalized.durationMs+VisionMeasurementPolicy.MAX_AGE_MS))
        }
        val locationUsable = environment != null && evaluationTimeEpochMs != null && EnvironmentPolicy.usableLocation(environment.location,evaluationTimeEpochMs)
        val road = environment?.road
        val roadUsable = road?.availability == Availability.MANUAL || (locationUsable && road?.availability == Availability.AVAILABLE &&
            EnvironmentPolicy.fresh(road.source.observedAtEpochMs,evaluationTimeEpochMs!!,EnvironmentPolicy.LOCATION_MAX_AGE_MS))
        val highway = roadUsable && road?.type == RoadType.HIGHWAY

        val persons = samples.mapNotNull { sample ->
            val p = sample.vision.sceneObservations?.persons ?: return@mapNotNull null
            if (!positiveQuality(p.measurementQuality) || p.detectionCount < 0 ||
                (p.detectorCapacity != null && (p.detectorCapacity <= 0 || p.detectionCount > p.detectorCapacity))) return@mapNotNull null
            val regions = regions(sample,RegionEvidenceKind.PERSON,RegionEvidenceSource.OBJECT_DETECTOR)
            Entry(sample,p.detectionCount.toFloat(),p.measurementQuality!!,(p.detectionCount/5f).coerceAtMost(1f),regions,regions.size==p.detectionCount)
        }
        val semanticPersons = samples.mapNotNull { sample ->
            if (persons.any { it.sample.elapsedMs==sample.elapsedMs && it.value>0f }) return@mapNotNull null
            val observations = sample.vision.sceneObservations ?: return@mapNotNull null
            val p = observations.semantic ?: return@mapNotNull null
            // Only an explicitly supplied real desktop mask supports this fallback. Null quality remains null.
            if (observations.runtime!=SceneObservationRuntime.DESKTOP_SEGMENTATION || observations.source.name.isBlank() ||
                !unit(p.personAreaRatio) || (p.measurementQuality!=null && !quality(p.measurementQuality))) return@mapNotNull null
            val regions = regions(sample,RegionEvidenceKind.PERSON,RegionEvidenceSource.SEMANTIC_COMPONENT)
            Entry(sample,p.personAreaRatio!!,p.measurementQuality,(p.personAreaRatio/.1f).coerceAtMost(1f),regions,regions.isNotEmpty())
        }
        val personBuckets = (persons.map { it.sample.elapsedMs } + semanticPersons.map { it.sample.elapsedMs }).distinct().size
        val lights = if (highway) emptyList() else samples.mapNotNull { sample ->
            val p = sample.vision.sceneObservations?.ambientLights ?: return@mapNotNull null
            if (!p.vehiclesExcluded || !positiveQuality(p.measurementQuality) || p.candidateCount < 0) return@mapNotNull null
            val area = p.areaRatio?.takeIf { unit(it) }
            val regions = regions(sample,RegionEvidenceKind.AMBIENT_LIGHT,RegionEvidenceSource.PIXEL_HEURISTIC)
            val distribution = if (regions.size==p.candidateCount) distribution(regions) else 0f
            val signal = .4f*(p.candidateCount/8f).coerceAtMost(1f) + .3f*((area ?: 0f)/.2f).coerceAtMost(1f) + .3f*distribution
            Entry(sample,p.candidateCount.toFloat(),p.measurementQuality!!,signal,regions,regions.size==p.candidateCount)
        }
        val distributedLights = lights.map { e ->
            val area = e.sample.vision.sceneObservations!!.ambientLights!!.areaRatio
            val supported = e.value>=3f && unit(area) && area!!>=.02f && e.completeRegions && distribution(e.regions)>=.5f
            e.copy(value=if(supported) 1f else 0f,signal=0f,regions=emptyList(),completeRegions=false)
        }
        val color = if (highway) emptyList() else samples.filter {
            // Receipt-relative freshness is checked by the window, not by today's desktop clock.
            VisionMeasurementPolicy.usableColor(it.vision.copy(frameTimeEpochMs=1),1)
        }
        fun colorEntries(warm:Boolean) = color.map { sample ->
            val c = sample.vision.colors!!
            val matches = if (warm) c.hueDegrees!! < 65 || c.hueDegrees > 330 else c.hueDegrees!! >= 165 && c.hueDegrees < 255
            val regions = regions(sample,RegionEvidenceKind.AMBIENT_LIGHT,RegionEvidenceSource.PIXEL_HEURISTIC)
            val lights = sample.vision.sceneObservations?.ambientLights
            Entry(sample,c.hueDegrees!!,sample.vision.colorMeasurementConfidence!!,if (matches) c.saturation!! else 0f,
                regions,lights?.vehiclesExcluded==true && regions.isNotEmpty() && regions.size==lights.candidateCount)
        }
        val colorExplanation = "전체 화면 색감 기여를 실제 광원 참조에 설명용 배분하며 개별 광원 색을 측정한 값이 아님"
        val warm = contribution("warm_color","따뜻한 색감",70f,colorEntries(true),"색상각 °","차량 제외 색감의 따뜻한 영역×채도×품질; 감정/기온 아님. $colorExplanation")
        val cool = contribution("cool_color","차가운 색감",70f,colorEntries(false),"색상각 °","차량 제외 청록·파랑 영역×채도×품질; 몽환적 감정으로 변환하지 않음. $colorExplanation")
        fun counter(id:String, other:MoodScoreContribution, own:MoodScoreContribution):MoodScoreContribution {
            val delta = if (own.delta != null && other.delta != null) -min(own.delta,other.delta*20f/70f) else null
            return MoodScoreContribution(id,"반대 색감 근거",weight=-20f,delta=delta,
                source=other.source,reason="같은 창의 반대 색감 기여를 최대 20점 범위에서 상쇄; 차량 비율 감점 아님. $colorExplanation",
                evidence=if(delta!=null && other.delta!=null && other.delta>0f) other.evidence.map { it.copy(allocatedDelta=it.allocatedDelta?.times(delta/other.delta)) } else emptyList())
        }
        val uniqueSigns = if (highway) emptyList() else samples.mapNotNull { sample ->
            val observations = sample.vision.sceneObservations ?: return@mapNotNull null
            val p = observations.semantic ?: return@mapNotNull null
            if (observations.source.name.isBlank() || (p.measurementQuality!=null && !quality(p.measurementQuality))) return@mapNotNull null
            val signRegions = regions(sample,RegionEvidenceKind.SIGNBOARD,RegionEvidenceSource.SEMANTIC_COMPONENT,allowExcluded=true)
            val measurements = validatedSignLights(p,signRegions) ?: return@mapNotNull null
            val weights = signRegions.map { region -> measurements.getValue(region.id).uniqueBrightAreaRatio }
            val area = weights.sum()
            Entry(sample,area,p.measurementQuality,(area/if(p.measurementQuality==null) .05f else .1f).coerceAtMost(1f),
                signRegions,true,weights)
        }
        val signs = uniqueSigns.filter { it.quality!=null }
        val rawBrightSigns = uniqueSigns.filter { it.quality==null }
        val signBuckets = uniqueSigns.size
        val signReason = if(highway) "유효하게 확인된 고속도로의 간판 활력 기여 억제" else
            "실제 간판별 유효 밝은 픽셀에서 ambient 채택 픽셀을 뺀 unique 면적만 반영. 위치 미상 허용; 상점/영업 추정 아님. 완전한 영역별 측정만 허용하며 전체 간판면적/원시 합계 추가 가산 없음. 실측 unique 0은 밝기 없음 또는 중복·제외 후 추가 기여0, null은 미측정/불완전"
        val vigor = listOf(
            contribution("persons","프레임 사람 검출",55f,persons,"명/프레임","프레임 검출 수를 5에서 제한한 뒤 사람 근거 bucket 평균; 고유 인원 합산 아님. 색감 품질과 독립하며 낮은 유효 품질은 작은 기여로 반영",personBuckets),
            contribution("semantic_person_area","실제 사람 마스크 영역",20f,semanticPersons,"화면 비율","양의 유효 검출 수가 없는 구간만 실제 desktop 사람 마스크 면적을 최대20점으로 제한해 참고. 검출0은 부재가 아니며 mask를 인원수로 환산하지 않음. 품질 null은 미평가 그대로, 확률/품질값을 대입하지 않음",personBuckets),
            contribution("ambient_lights","차량 제외 주변 조명",35f,lights,"후보/프레임",if (highway) "고속도로 조명/안내판 활기 오인 방지로 미반영" else "후보 수(최대8) 40% + 빛 영역(최대20%) 30% + 실제 후보 중심의 2×2 구역 분포 30%, 품질 적용. 누락 부분 기여 없음; 차량 제외 비율 재감점 없음"),
            contribution("distributed_light_support","여러 조명의 공간 분포",0f,distributedLights,"충족 구간 비율","실제 후보3개 이상·면적2% 이상·2×2 구역 중2개 이상을 함께 충족한 구간 비율. 점수 중복 가산 없이 조명 중심 활력 후보의 보조 조건"),
            contribution("semantic_signs","중복 제외 간판 밝기",10f,signs,"화면 비율",signReason,signBuckets),
            contribution("semantic_sign_bright_raw","품질 미평가 중복 제외 간판 밝기",5f,rawBrightSigns,"화면 비율","$signReason. 품질 null 유지, 별도 최대5점 제한",signBuckets),
            MoodScoreContribution("vehicle_exclusion","차량 조명 제외",reason="이미 조명 계산에서 제외한 차량에 추가 감점하지 않습니다.",
                evidence=samples.filter { it.vision.vehicleMaskApplied }.flatMap { s ->
                    RegionEvidenceSource.entries.flatMap { origin -> regions(s,RegionEvidenceKind.VEHICLE,origin) }
                        .filter { !it.exclusionReason.isNullOrBlank() }.map { RegionScoreReference(s.elapsedMs,it.id) } }),
            MoodScoreContribution("semantic_context","하늘·건물·사람 마스크",reason="건물/사람 mask 면적은 검출 인원수나 영업 여부로 변환하지 않습니다.")
        )
        val allSky = samples.mapNotNull { sample ->
            val observations = sample.vision.sceneObservations ?: return@mapNotNull null
            val p = observations.semantic ?: return@mapNotNull null
            val rawSupported = p.measurementQuality==null && observations.runtime==SceneObservationRuntime.DESKTOP_SEGMENTATION && observations.source.name.isNotBlank()
            if ((!quality(p.measurementQuality) && !rawSupported) || !unit(p.skyAreaRatio) || !unit(p.skyBrightness)) return@mapNotNull null
            val regions = regions(sample,RegionEvidenceKind.SKY,RegionEvidenceSource.SEMANTIC_COMPONENT)
            Entry(sample,p.skyBrightness!!,p.measurementQuality,p.skyBrightness*(p.skyAreaRatio!!/.3f).coerceAtMost(1f),regions,regions.isNotEmpty())
        }
        val skyContribution = contribution("sky_light","하늘 마스크 밝기",25f,allSky.filter { it.quality!=null },"정규화 명도","실제 sky mask의 면적과 밝기만 사용; 맑음/날씨/현재 시각 추정 아님",allSky.size)
        val rawSkyContribution = contribution("sky_light_raw","품질 미평가 하늘 마스크 밝기",10f,allSky.filter { it.quality==null },"정규화 명도","실제 desktop sky 면적×밝기를 별도 최대10점으로 제한. 품질 null 유지; 맑음/날씨/현재 시각 추정 아님",allSky.size)
        val weather = environment?.weather
        val weatherUsable = weather?.availability == Availability.MANUAL || (locationUsable && weather?.availability == Availability.AVAILABLE &&
            EnvironmentPolicy.fresh(weather.source.observedAtEpochMs,evaluationTimeEpochMs!!,EnvironmentPolicy.WEATHER_MAX_AGE_MS))
        val temperature = weather?.temperatureC?.takeIf { it.isFinite() && it in -80.0..60.0 }
        val weatherDelta = if (weatherUsable && temperature != null && weather?.condition=="강수 없음")
            (1f-abs(temperature.toFloat()-18f)/12f).coerceIn(0f,1f)*35f else null
        val weatherContribution = MoodScoreContribution("mild_dry_weather","기온·강수 맥락",temperature?.toFloat(),"°C",35f,weatherDelta,
            source=weather?.source,reason=if (weather?.availability==Availability.MANUAL) "수동 날씨 입력; 18°C 중심 ±12°C의 음악 규칙이며 실제 관측/쾌적성 확률 아님" else "유효 기온과 강수 없음에 한해 적용; 강수 없음은 맑음이 아니며 18°C 중심 ±12°C는 초기 음악 규칙")
        val rain = weather?.precipitationMm?.takeIf { weatherUsable && it.isFinite() && it in 0.0..1000.0 }
        val freshPositive = (skyContribution.delta ?: 0f)+(rawSkyContribution.delta ?: 0f)+(weatherDelta ?: 0f)
        val rainDelta = rain?.let { -min(freshPositive,(it/10).coerceAtMost(1.0).toFloat()*15f) }
        val rainContribution = MoodScoreContribution("rain_counter","강수 반대 근거",rain?.toFloat(),"mm/지난1시간",-15f,rainDelta,
            source=weather?.source,reason="알려진 강수량만 청량 축의 양의 기여 내에서 상쇄; 비로 우울/두려움/천둥을 생성하지 않음")
        return MoodScoreSummary(RULE_VERSION,normalized.durationMs,window.receivedFrameCount.coerceAtLeast(normalized.receivedFrameCount),normalized.samples.size,
            listOf(mood("활기 넘침",vigor),mood("따뜻한",listOf(warm,counter("cool_counter",cool,warm))),
                mood("차가운",listOf(cool,counter("warm_counter",warm,cool))),mood("청량한",listOf(skyContribution,rawSkyContribution,weatherContribution,rainContribution))),
            listOf("초기 규칙 점수(0~100)이며 검증된 확률·운전자 감정·영업 상태가 아닙니다.",
                "${normalized.samples.size}개 시간 구간 대표를 사용합니다. 누락 구간을 0명으로 채우지 않으며 고유 인원수를 합산하지 않습니다.",
                "사람 검출 0은 부재 확정이 아니고, 근거 부족은 우울/차분함으로 바꾸지 않습니다.",
                "박스 ID는 해당 프레임 안에서만 유효하며 추적 ID가 아닙니다."))
    }

    fun keywords(summary:MoodScoreSummary):List<String> = summary.scores.filter { mood ->
        if (mood.keyword=="활기 넘침") {
            val distributed = mood.contributions.any { it.id=="distributed_light_support" && (it.observedValue ?: 0f)>=.5f }
            val lightPoints = mood.contributions.firstOrNull { it.id=="ambient_lights" }?.delta ?: 0f
            (mood.score ?: 0f)>=35f || (distributed && lightPoints>=20f)
        } else (mood.score ?: -1f)>=if(mood.keyword=="청량한") 20f else 15f
    }.map { it.keyword }

    private fun mood(keyword:String, contributions:List<MoodScoreContribution>):MoodScore {
        val values = contributions.mapNotNull { it.delta }
        return MoodScore(keyword,if (values.isEmpty()) null else values.sum().coerceIn(0f,100f),contributions)
    }
    private fun contribution(id:String,label:String,weight:Float,entries:List<Entry>,unit:String,reason:String,normalizationCount:Int=entries.size):MoodScoreContribution {
        if (entries.isEmpty()) return MoodScoreContribution(id,label,weight=weight,reason="$reason · 유효 근거 미확인/미반영")
        // Unknown semantic-mask quality uses a separately bounded raw-measurement rule,
        // not an invented quality estimate; the returned quality is still null.
        val amounts = entries.map {
            val raw = it.signal*weight
            (if (it.quality==null) raw else raw*it.quality)/normalizationCount.coerceAtLeast(1)
        }
        val delta = amounts.sum()
        val canAllocate = entries.zip(amounts).filter { it.second!=0f }.all { it.first.completeRegions && it.first.regions.isNotEmpty() }
        val evidence = if (!canAllocate) emptyList() else entries.zip(amounts).flatMap { (e,amount) ->
            if (e.regions.isEmpty() || (amount==0f && e.regionWeights==null)) emptyList() else {
                val weights = e.regionWeights ?: List(e.regions.size) { 1f }
                val total = weights.sum()
                val lastPositive = weights.indexOfLast { it>0f }
                var allocated = 0f
                e.regions.mapIndexed { i,region ->
                    val share = when {
                        weights[i]==0f || total==0f -> 0f
                        i==lastPositive -> amount-allocated
                        else -> amount*weights[i]/total
                    }
                    allocated += share
                    RegionScoreReference(e.sample.elapsedMs,region.id,share)
                }
            }
        }
        val sources = entries.mapNotNull { it.sample.vision.sceneObservations?.source }.distinct()
        val source = if (sources.size==1) sources.first() else SourceInfo(if (sources.isEmpty()) "영상 색감 측정" else sources.map { it.name }.distinct().joinToString(" / "),detail="시간 구간별 근거")
        return MoodScoreContribution(id,label,entries.map { it.value }.average().toFloat(),unit,weight,delta,
            if (entries.any { it.quality==null }) null else entries.map { it.quality!! }.average().toFloat(),source,
            "$reason · 유효 ${entries.size}개 구간 평균. " + if (evidence.isNotEmpty())
                (if(entries.any { it.regionWeights!=null }) "구간 기여를 실제 unique 밝은 면적에 비례 분배(측정0 영역은0); " else "구간 기여를 해당 프레임 실제 영역에 균등 분배; ") + "합계는 그룹 기여와 같습니다."
            else "전체 화면 근거 또는 영역 참조 불완전으로 박스별 점수는 만들지 않습니다.",evidence)
    }
    private fun validatedSignLights(semantic:SemanticSceneObservation,regions:List<RegionEvidence>):Map<String,SignLightEvidence>? {
        val list = semantic.signLightRegions ?: return null
        val count = semantic.signboardCandidateCount ?: return null
        if(count<0 || list.size!=count || regions.size!=count || list.map { it.regionId }.toSet().size!=count ||
            list.map { it.regionId }.toSet()!=regions.map { it.id }.toSet()) return null
        val byId = list.associateBy { it.regionId }
        for(region in regions) {
            val m = byId.getValue(region.id)
            if(!unit(m.brightAreaRatio) || !unit(m.ambientOverlapAreaRatio) || !unit(m.uniqueBrightAreaRatio) ||
                m.ambientOverlapAreaRatio>m.brightAreaRatio+SIGN_TOLERANCE ||
                m.uniqueBrightAreaRatio>m.brightAreaRatio+SIGN_TOLERANCE ||
                abs(m.brightAreaRatio-m.ambientOverlapAreaRatio-m.uniqueBrightAreaRatio)>SIGN_TOLERANCE ||
                m.brightAreaRatio>(region.right-region.left)*(region.bottom-region.top)+SIGN_TOLERANCE ||
                (region.exclusionReason!=null && m.uniqueBrightAreaRatio!=0f)) return null
        }
        val bright = list.sumOf { it.brightAreaRatio.toDouble() }
        if(bright>1.0+SIGN_TOLERANCE) return null
        if(semantic.signboardAreaRatio!=null && (!unit(semantic.signboardAreaRatio) || bright>semantic.signboardAreaRatio+SIGN_TOLERANCE)) return null
        // Pixel disjointness/exclusion is established by the producer's real masks, never inferred from boxes.
        return byId
    }
    private fun regions(sample:SceneSample,kind:RegionEvidenceKind,source:RegionEvidenceSource,allowExcluded:Boolean=false):List<RegionEvidence> {
        val all = sample.vision.sceneObservations?.regions.orEmpty()
        val uniqueIds = all.groupingBy { it.id }.eachCount()
        return all.filter { it.kind==kind && it.source==source && it.id.isNotBlank() && uniqueIds[it.id]==1 &&
            unit(it.left) && unit(it.top) && unit(it.right) && unit(it.bottom) && it.right>it.left && it.bottom>it.top &&
            (allowExcluded || kind==RegionEvidenceKind.VEHICLE || it.exclusionReason==null) }
    }
    private fun unit(value:Float?):Boolean = value!=null && value.isFinite() && value in 0f..1f
    private fun distribution(regions:List<RegionEvidence>):Float = regions.map {
        (if ((it.left+it.right)/2f>=.5f) 1 else 0) + (if ((it.top+it.bottom)/2f>=.5f) 2 else 0)
    }.distinct().size/4f
    private fun quality(value:Float?):Boolean = unit(value) && value!!>=.5f
    private fun positiveQuality(value:Float?):Boolean = unit(value) && value!!>0f
    companion object {
        const val RULE_VERSION = "scene-v1.2"
        private const val SIGN_TOLERANCE = 1e-6f
    }
}
