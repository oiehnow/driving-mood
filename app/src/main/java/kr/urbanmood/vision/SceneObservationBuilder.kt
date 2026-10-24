package kr.urbanmood.vision

import kr.urbanmood.contracts.*

/** Reuses this frame's detections; does not run another model or track people.
 * Person boxes never enter the vehicle mask or change color/light measurement quality.
 */
internal object SceneObservationBuilder {
    const val PERSON_SCORE_THRESHOLD = .5f // Uncalibrated conservative acceptance setting.
    private val vehicles = setOf("car", "bus", "truck", "motorcycle", "train", "bicycle")

    fun build(boxes: List<ObjectBox>, atCapacity: Boolean, measurement: LightMeasurement,
              frameTimeEpochMs: Long, frameKey: String,
              inputSource: FrameInputSource = FrameInputSource.LIVE_CAMERA,
              mediaPresentationTimeUs: Long? = null): SceneObservations {
        val valid = boxes.mapNotNull { box ->
            if (!listOf(box.left,box.top,box.right,box.bottom,box.confidence).all { it.isFinite() } ||
                box.confidence !in .25f..1f) return@mapNotNull null
            val clipped = box.copy(left=box.left.coerceIn(0f,1f),top=box.top.coerceIn(0f,1f),
                right=box.right.coerceIn(0f,1f),bottom=box.bottom.coerceIn(0f,1f))
            clipped.takeIf { it.right>it.left && it.bottom>it.top }
        }
        val people = valid.filter { it.label=="person" && it.confidence>=PERSON_SCORE_THRESHOLD }
        val regions = mutableListOf<RegionEvidence>()
        valid.forEachIndexed { index, b ->
            val kind = when {
                b.label=="person" && b.confidence>=PERSON_SCORE_THRESHOLD -> RegionEvidenceKind.PERSON
                b.label in vehicles -> RegionEvidenceKind.VEHICLE
                else -> return@forEachIndexed
            }
            regions += RegionEvidence("$frameKey:det:$index",b.left,b.top,b.right,b.bottom,
                if(kind==RegionEvidenceKind.PERSON) "사람으로 추정되는 영역" else "차량으로 추정되는 영역",
                kind,RegionEvidenceSource.OBJECT_DETECTOR,
                if(kind==RegionEvidenceKind.VEHICLE) "조명 계산 제외" else null)
        }
        measurement.candidateRegions.forEachIndexed { index,b ->
            regions += RegionEvidence("$frameKey:light:$index",b.left,b.top,b.right,b.bottom,
                "주변 조명 후보 · 상점 여부 미확인",RegionEvidenceKind.AMBIENT_LIGHT,
                RegionEvidenceSource.PIXEL_HEURISTIC)
        }
        // These are operational quality flags, NOT detector score averages or accuracy.
        // Dark/glared images and model capacity can conceal people independently of light sampling.
        val personQuality = when {
            valid.size!=boxes.size -> 0f
            atCapacity -> .35f
            measurement.pixelQuality.darkPixelRatio>.90f || measurement.pixelQuality.clippedPixelRatio>.25f -> .35f
            else -> .65f
        }
        val features = measurement.lightFeatures?.let { measured ->
            val flags = mutableListOf("SOURCE_TYPE_UNCONFIRMED", "UNCALIBRATED_HEURISTIC_QUALITY")
            if (measured.summary.pixelCount == 0) flags += "NO_ACCEPTED_PIXELS"
            else if (measured.summary.pixelCount < 24) flags += "LIMITED_PIXEL_SAMPLE"
            if (measured.summary.upperChannelCounts[1] + measured.summary.upperChannelCounts[2] > 0)
                flags += "SOME_CHANNELS_NEAR_UPPER_LIMIT"
            if (measured.summary.upperChannelCounts[3] > 0) flags += "ALL_CHANNELS_NEAR_UPPER_LIMIT"
            if (measured.summary.categoryCounts.take(6).count { it > 0 } > 1) flags += "MULTIPLE_RECORDED_COLOR_CATEGORIES"
            if (atCapacity) flags += "DETECTOR_AT_CAPACITY"
            if (measurement.pixelQuality.excludedBrightRatio > .70f) flags += "HIGH_EXCLUDED_OR_REJECTED_BRIGHT_RATIO"
            if (valid.size != boxes.size) flags += "INVALID_DETECTOR_RECORDS"
            LightFeatureObservation(MeasuredLightFeatures.VERSION, measured.width, measured.height,
                measured.summary,
                if (measured.groups.size == measurement.candidateRegions.size) measured.groups.mapIndexed { index, stats ->
                    LightGroupFeature("$frameKey:light:$index", LightGroupKind.LEGACY_UNCLASSIFIED_CANDIDATE, stats)
                } else null,
                LightFeatureQuality(measurement.pixelQuality.lightConfidence, measurement.pixelQuality.validPixelRatio,
                    flags.toList(), measurement.pixelQuality.reasons.toList() +
                        "색 분포와 면적은 실제 관측값입니다. 불빛의 종류나 원래 색을 확정한 값은 아닙니다."))
        }
        val recorded = inputSource == FrameInputSource.ANDROID_RECORDED
        return SceneObservations(
            runtime=if (recorded) SceneObservationRuntime.ANDROID_RECORDED else SceneObservationRuntime.ANDROID_CAMERA,
            source=SourceInfo(if (recorded) "녹화 영상의 실제 프레임 관측" else "카메라 객체·주변 조명 관측", observedAtEpochMs=frameTimeEpochMs,
                detail="EfficientDet-Lite0 ${MediaPipeVehicleDetector.MODEL_SHA256}; light-candidates-v1; person-quality-v1; ${MeasuredLightFeatures.VERSION}" +
                    if (recorded) "; actual decoder PTS us=$mediaPresentationTimeUs; frame time=recording-start anchor + normalized media time" else ""),
            persons=PersonObservation(people.size,unionArea(people),PERSON_SCORE_THRESHOLD,25,atCapacity,personQuality),
            ambientLights=AmbientLightObservation(measurement.candidateRegions.size,
                measurement.colors.luminousArea,true,measurement.pixelQuality.lightConfidence),
            semantic=null,
            notes=listOf("사람 검출 수는 이 프레임의 박스 수이며 실제 인원수나 고유 인원수가 아닙니다.",
                "검출 0은 사람이 없다는 뜻이 아닙니다. 조명 후보는 상점 수가 아닙니다.",
                "관측 품질은 검출 상한·어두움·과노출을 반영한 초기 규칙이며 정확도나 확률이 아닙니다."),
            regions=regions.toList(), lightFeatures=features)
    }

    /** Exact normalized rectangle union, no pixel quantization or double-counted overlap. */
    private fun unionArea(boxes: List<ObjectBox>): Float {
        val xs=boxes.flatMap { listOf(it.left,it.right) }.distinct().sorted()
        var area=0.0
        for(i in 0 until xs.size-1) {
            val intervals=boxes.filter { it.left<xs[i+1] && it.right>xs[i] }.sortedBy { it.top }
            var end=0f; var length=0f
            for(b in intervals) {
                if(b.bottom>end) { length+=b.bottom-maxOf(end,b.top); end=b.bottom }
            }
            area+=(xs[i+1]-xs[i]).toDouble()*length
        }
        return area.toFloat().coerceIn(0f,1f)
    }
}
