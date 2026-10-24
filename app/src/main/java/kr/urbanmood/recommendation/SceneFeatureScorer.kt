package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import kr.urbanmood.context.EnvironmentPolicy
import kotlin.math.abs

/** Scene2: measured RGB area strength, evidence limits and music interpretation are separate.
 * No model of source identity, emission, commerce, emotion or calibrated probability. */
internal class SceneFeatureScorer {
    private data class Frame(val sample:SceneSample,val feature:LightFeatureObservation,val pixels:Double,
        val groups:List<Pair<LightGroupFeature,RegionEvidence>>?) {
        val stats get() = feature.summary
        val area get() = stats.pixelCount/pixels
        val known get() = stats.pixelCount-stats.categoryCounts[7]
        val colorSupported get() = area>=MIN_COLOR_AREA && known>0 && known.toDouble()/stats.pixelCount>=.5 && stats.saturationSum>0
        val warmShare get() = stats.redYellowSaturationSum.toDouble()/stats.saturationSum
        val coolShare get() = stats.cyanBlueSaturationSum.toDouble()/stats.saturationSum
        val mixed get() = colorSupported && warmShare>=.2 && coolShare>=.2 && warmShare+coolShare>=.6
        val warm get() = colorSupported && warmShare>=.6
        val cool get() = colorSupported && coolShare>=.6
        val distributed get() = groups?.filter { it.first.pixels.pixelCount>0 }?.map {
            val r=it.second
            (if((r.left+r.right)/2>=.5f) 1 else 0)+(if((r.top+r.bottom)/2>=.5f) 2 else 0)
        }?.distinct()?.size?.let { it>=2 } == true
        val personsObserved get():Boolean {
            val o=sample.vision.sceneObservations!!
            val p=o.persons
            // Semantic area remains independent ledger evidence, not a detected person.
            return p!=null && p.detectionCount>0 && p.measurementQuality?.let { it.isFinite() && it>0 && it<=1 }==true &&
                (p.detectorCapacity==null || p.detectorCapacity>=p.detectionCount)
        }
    }

    fun score(window:SceneWindow,samples:List<SceneSample>,environment:EnvironmentSnapshot?,evaluationTime:Long?):MoodScoreSummary {
        val issues=mutableListOf<String>()
        val frames=samples.mapNotNull { sample ->
            val observation=sample.vision.sceneObservations ?: return@mapNotNull null
            val f=observation.lightFeatures ?: return@mapNotNull null
            validate(sample,f)?.also { issues += "${sample.elapsedMs}ms 새 관측 미반영: $it"; return@mapNotNull null }
            val lookup=observation.regions.associateBy { it.id }
            Frame(sample,f,f.rasterWidth.toDouble()*f.rasterHeight,f.groups?.map { it to lookup.getValue(it.regionId) })
        }
        val expected=if(window.durationMs>0 && window.bucketDurationMs>0) ((window.durationMs-1)/window.bucketDurationMs+1).toInt() else 0
        val denominator=expected.coerceAtLeast(1)
        val highway=environment?.road?.let { r -> r.type==RoadType.HIGHWAY && (r.availability==Availability.MANUAL ||
            (evaluationTime!=null && r.availability==Availability.AVAILABLE && EnvironmentPolicy.usableLocation(environment.location,evaluationTime) &&
                EnvironmentPolicy.fresh(r.source.observedAtEpochMs,evaluationTime,EnvironmentPolicy.LOCATION_MAX_AGE_MS))) } == true
        val colors=frames.filter { it.known>0 }
        fun strength(id:String,label:String,weight:Float,items:List<Frame>,amount:(LightPixelStats)->Double):MoodScoreContribution {
            if(items.isEmpty()) return MoodScoreContribution(id,label,weight=weight,reason="계산 가능한 픽셀 근거 없음",basis=ContributionBasis.OBSERVED_FEATURE_STRENGTH)
            val shares=items.map { amount(it.stats)/it.pixels*weight/items.size }
            val canAllocate=items.all { it.groups!=null }
            val evidence=if(!canAllocate) emptyList() else items.zip(shares).flatMap { (frame,total) ->
                val groups=frame.groups.orEmpty()
                val mass=amount(frame.stats)
                val last=groups.indexOfLast { amount(it.first.pixels)>0.0 }
                var allocated=0f
                groups.mapIndexed { index,pair ->
                    val part=amount(pair.first.pixels)
                    val delta=if(part==0.0 || mass==0.0) 0f else if(index==last) total.toFloat()-allocated else (total*part/mass).toFloat()
                    allocated+=delta
                    RegionScoreReference(frame.sample.elapsedMs,pair.first.regionId,delta)
                }
            }
            val quality=items.map { it.feature.quality.heuristicQuality }
            return MoodScoreContribution(id,label,items.map { amount(it.stats)/it.pixels }.average().toFloat(),"전체 화면 대비 실제 픽셀·채도 비율",weight,
                shares.sum().toFloat(),if(quality.all { unit(it) }) quality.map { it!! }.average().toFloat() else null,
                source=source(items),reason="화면 기준 관측 강도 · 유효 ${items.size}개 구간 평균 · 품질 별도. "+
                    if(canAllocate) "실제 영역별 측정량으로 배분" else "영역별 배분 미지원",
                evidence=evidence,basis=ContributionBasis.OBSERVED_FEATURE_STRENGTH)
        }
        fun metric(id:String,label:String,value:Float?,unit:String,reason:String)=MoodScoreContribution(id,label,value,unit,
            reason=reason,basis=ContributionBasis.OBSERVED_FEATURE_STRENGTH)
        fun support(id:String,label:String,count:Int,reason:String)=metric(id,label,if(frames.isEmpty()) null else count.toFloat()/denominator,
            "전체 기간 구간 중 조건 충족 비율",reason)
        val common=listOf(
            metric("feature_coverage","유효 관측 구간",frames.size.toFloat()/denominator,"전체 기간 구간 비율","전체 실제 기간 기준"),
            metric("feature_area","관측된 밝은 면적",frames.takeIf { it.isNotEmpty() }?.map { it.area }?.average()?.toFloat(),"화면 비율","중복 없는 관측 픽셀 합집합"),
            metric("feature_color_coverage","관측 픽셀 중 색 분류 가능 범위",frames.filter { it.stats.pixelCount>0 }.takeIf { it.isNotEmpty() }
                ?.map { it.known.toDouble()/it.stats.pixelCount }?.average()?.toFloat(),"관측 픽셀 비율","세 채널 상한 근접 픽셀은 색 미확인")) +
            listOf("빨강","노랑","초록","청록","파랑","자홍","무채색에 가까움","세 채널 상한 근접·색 미확인").mapIndexed { index,label ->
                metric("feature_rgb_category_$index","기록된 RGB: $label",frames.takeIf { it.isNotEmpty() }
                    ?.map { it.stats.categoryCounts[index]/it.pixels }?.average()?.toFloat(),"전체 화면 대비 해당 범주 픽셀 비율",
                    "실제 픽셀 분포의 구간 평균")
            } + (0..3).map { channels ->
                metric("feature_rgb_upper_$channels","RGB 상한 근접 채널 $channels 개",frames.takeIf { it.isNotEmpty() }
                    ?.map { it.stats.upperChannelCounts[channels]/it.pixels }?.average()?.toFloat(),"전체 화면 대비 해당 픽셀 비율",
                    "RGB 상한 근접 채널 수별 분포")
            }
        val warm=strength("feature_warm","빨강·노랑 범위 색감",70f,colors) { it.redYellowSaturationSum.toDouble() }
        val cool=strength("feature_cool","청록·파랑 범위 색감",70f,colors) { it.cyanBlueSaturationSum.toDouble() }
        val bright=strength("feature_light_area","밝은 장면 관측 면적",35f,frames) { it.pixelCount.toDouble() }
        val warmSupport=support("interpret_warm","따뜻한 색감 지속 근거",frames.count { it.warm },"면적≥0.1%, 색 분류 가능≥절반, 빨강·노랑 비중≥60%")
        val coolSupport=support("interpret_cool","차가운 색감 지속 근거",frames.count { it.cool },"면적≥0.1%, 색 분류 가능≥절반, 청록·파랑 채도합 비중≥60%인 실제 구간.")
        val mixed=support("interpret_mixed","따뜻하고 차가운 색 혼재",frames.count { it.mixed },"두 색군 각각≥20%, 합계≥60%")
        val visual=support("interpret_visual","시각적 활력 지속 근거",if(highway) 0 else frames.count { it.area>=.02 && it.distributed },
            if(highway) "고속도로: 자동 시각적 활력 후보 제한" else "면적≥2%, 양수 영역 중심이2×2 중2구역 이상에 분포")
        val peopleLight=support("interpret_people_light","사람·밝은 영역 동시 관측",if(highway) 0 else frames.count {
            it.area>=MIN_COLOR_AREA && it.distributed && it.personsObserved },
            "같은 구간에 유효한 사람 검출과 면적≥0.1%의 분포된 밝은 영역 관측")
        val qualityNotes=frames.flatMap { frame -> val q=frame.feature.quality
            buildList {
                if(q.heuristicQuality==null) add("관측 품질 미평가")
                else if(!unit(q.heuristicQuality)) add("품질 숫자 오류 · 픽셀 강도는 별도 표시")
                if(q.inspectedAreaRatio!=null && !unit(q.inspectedAreaRatio)) add("관측 범위 비율이 유효하지 않습니다.")
                addAll(q.flags.mapNotNull { FLAG_LABELS[it] })
                if(frame.stats.upperChannelCounts[1]+frame.stats.upperChannelCounts[2]>0) add("일부 RGB 채널 상한 근접 · 기록된 색 범주 유지")
                if(frame.stats.upperChannelCounts[3]>0) add("세 채널 상한 근접 · 해당 픽셀 색 미확인")
            }
        }.distinct()
        val notes=mutableListOf("관측 ${frames.size}/$expected 구간 · 강도와 품질 별도 표시",
            "기간의 절반 이상에서 이어진 색 구성·밝은 영역·사람 동시 관측을 음악 후보에 반영합니다.",
            "관측 범위: 촬영 RGB의 대비와 밝은 표면. 빛의 종류·영업 여부는 미확인입니다.")
        if((mixed.observedValue ?: 0f)>=.5f) notes += "따뜻한 색과 차가운 색이 함께 관측되어 혼재 후보로 두 음악 태그를 참고합니다."
        notes.addAll(qualityNotes);notes.addAll(issues.distinct())
        return MoodScoreSummary(RULE_VERSION,window.durationMs,window.receivedFrameCount,window.samples.size,
            listOf(MoodScore("따뜻한",warm.delta,listOf(warm,warmSupport,mixed)+common),
                MoodScore("차가운",cool.delta,listOf(cool,coolSupport,mixed)),MoodScore("시각적 활력",bright.delta,listOf(bright,visual,peopleLight))),notes)
    }

    private fun validate(sample:SceneSample,f:LightFeatureObservation):String? {
        val full=f.rasterWidth.toLong()*f.rasterHeight
        if(f.measurementVersion !in MEASUREMENT_VERSIONS || f.rasterWidth<=0 || f.rasterHeight<=0 || full>Int.MAX_VALUE) return "미지원 측정 버전/격자 오류"
        if(!validStats(f.summary,full)) return "픽셀 histogram/채도합 오류"
        val groups=f.groups ?: return null // Summary still exists when region measurement is unsupported.
        val all=sample.vision.sceneObservations!!.regions
        if(groups.map { it.regionId }.distinct().size!=groups.size) return "중복 영역 ID"
        for(g in groups) {
            val r=all.singleOrNull { it.id==g.regionId } ?: return "영역 참조 누락/중복"
            if(g.regionId.isBlank() || !listOf(r.left,r.top,r.right,r.bottom).all { unit(it) } || r.right<=r.left || r.bottom<=r.top ||
                !validStats(g.pixels,full) || g.pixels.pixelCount.toDouble()/full>(r.right-r.left)*(r.bottom-r.top)+TOL ||
                (r.exclusionReason!=null && g.pixels.pixelCount>0)) return "영역 bounds/픽셀/제외 관계 오류"
        }
        if(groups.sumOf { it.pixels.pixelCount.toLong() }!=f.summary.pixelCount.toLong()) return "불완전한 영역 합계"
        for(i in 0..7) if(groups.sumOf { it.pixels.categoryCounts[i].toLong() }!=f.summary.categoryCounts[i].toLong()) return "색상별 영역 합계 불일치"
        for(i in 0..3) if(groups.sumOf { it.pixels.upperChannelCounts[i].toLong() }!=f.summary.upperChannelCounts[i].toLong()) return "상한 채널별 영역 합계 불일치"
        for(select in listOf<(LightPixelStats)->Float>({it.saturationSum},{it.redYellowSaturationSum},{it.cyanBlueSaturationSum}))
            if(abs(groups.sumOf { select(it.pixels).toDouble() }-select(f.summary))>full*TOL) return "영역별 채도합 불일치"
        return null
    }
    private fun validStats(s:LightPixelStats,full:Long):Boolean {
        if(s.pixelCount<0 || s.pixelCount>full || s.categoryCounts.size!=8 || s.upperChannelCounts.size!=4 ||
            (s.categoryCounts+s.upperChannelCounts).any { it<0 } || s.categoryCounts.sumOf { it.toLong() }!=s.pixelCount.toLong() ||
            s.upperChannelCounts.sumOf { it.toLong() }!=s.pixelCount.toLong() || s.categoryCounts[7]!=s.upperChannelCounts[3]) return false
        val sums=listOf(s.saturationSum,s.redYellowSaturationSum,s.cyanBlueSaturationSum)
        if(sums.any { !it.isFinite() || it<0 }) return false
        val known=s.pixelCount-s.categoryCounts[7]
        val chromatic=known-s.categoryCounts[6]
        return s.saturationSum<=known+full*TOL && s.redYellowSaturationSum+s.cyanBlueSaturationSum<=s.saturationSum+full*TOL &&
            s.redYellowSaturationSum+s.cyanBlueSaturationSum<=chromatic+full*TOL &&
            (s.pixelCount!=0 || sums.all { it==0f })
    }
    private fun source(frames:List<Frame>):SourceInfo {
        val sources=frames.map { it.sample.vision.sceneObservations!!.source }.distinct()
        return if(sources.size==1) sources.first() else SourceInfo(sources.map { it.name }.distinct().joinToString(" / "),detail="실제 관측 시간 구간별 출처")
    }
    private fun unit(x:Float?):Boolean=x!=null && x.isFinite() && x in 0f..1f
    companion object {
        const val RULE_VERSION="scene-v2.0"
        private val MEASUREMENT_VERSIONS=setOf("accepted-light-rgb-v1","semantic-contrast-rgb-v3")
        private val FLAG_LABELS=mapOf("SOURCE_TYPE_UNCONFIRMED" to "밝은 영역의 종류 미확인",
            "SEMANTIC_MASK_QUALITY_UNEVALUATED" to "의미 마스크 품질 미평가",
            "RESEARCH_SELECTION_NOT_PRODUCT" to "연구 관측 입력",
            "VEHICLE_TRAFFIC_REFLECTION_MISSES_REMAIN" to "차량·교통등·반사 누락 가능",
            "DAYLIGHT_AND_EMISSION_NOT_SEPARATED" to "햇빛과 자발광 미구분",
            "LIMITED_PIXEL_SAMPLE" to "적은 픽셀 표본", "DETECTOR_AT_CAPACITY" to "검출 상한 도달",
            "HIGH_EXCLUDED_OR_REJECTED_BRIGHT_RATIO" to "밝은 영역 제외·미채택 비율 높음",
            "INVALID_DETECTOR_RECORDS" to "검출 입력 오류", "NO_ACCEPTED_PIXELS" to "채택 픽셀0",
            "NO_OBSERVED_PIXELS" to "관측 픽셀0")
        private const val MIN_COLOR_AREA=.001
        private const val TOL=1e-6
        fun keywords(summary:MoodScoreSummary):List<String> {
            fun supported(id:String)=summary.scores.flatMap { it.contributions }.any { it.id==id && (it.observedValue ?: 0f)>=.5f }
            return buildList {
                if(supported("interpret_warm") || supported("interpret_mixed")) add("따뜻한")
                if(supported("interpret_cool") || supported("interpret_mixed")) add("차가운")
                if(supported("interpret_visual") || supported("interpret_people_light")) add("활기 넘침")
            }
        }
    }
}
