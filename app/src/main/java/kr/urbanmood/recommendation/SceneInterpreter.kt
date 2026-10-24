package kr.urbanmood.recommendation

import kr.urbanmood.contracts.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** C-local interpretation boundary: B measurements + existing road evidence -> limited mood cues.
 * Does not classify businesses, infer a road from GPS, or promote the analyzer's status.
 */
internal object SceneInterpreter {
    fun interpret(vision: VisionResult?, road: RoadContext, roadUsable: Boolean, nowEpochMs: Long, zone: ZoneId): SceneInterpretation {
        val roadName = if (!roadUsable) "도로 종류 미확인" else when (road.type) {
            RoadType.HIGHWAY -> "고속도로"
            RoadType.URBAN -> "도심"
            RoadType.RESIDENTIAL -> "주거지"
            RoadType.OTHER -> "기타 도로"
            RoadType.UNKNOWN -> "도로 종류 미확인"
        }
        val reasons = mutableListOf(
            "도로 출처: ${road.source.name} · $roadName · 관측 ${time(road.source.observedAtEpochMs, zone)}" +
                if (road.source.cached) " · 이전 조회 정보" else ""
        )
        if (vision == null) {
            reasons += "최근 영상 정보가 없어 조명 분위기는 추천에 반영하지 않았습니다."
            return SceneInterpretation(reasons = reasons)
        }
        val v = vision
        val method = when (v.method) {
            AnalysisMethod.NONE -> "분석 정보 없음"
            AnalysisMethod.HEURISTIC -> "화면의 색감·밝기 측정"
            AnalysisMethod.MODEL -> "영상 모델 분석"
            AnalysisMethod.HYBRID -> "영상 모델 분석과 색감·밝기 측정"
        }
        reasons += "영상 출처: $method · 수신 ${time(v.frameTimeEpochMs, zone)}"
        val fresh = nowEpochMs > 0 && v.frameTimeEpochMs > 0 && v.frameTimeEpochMs <= nowEpochMs &&
            nowEpochMs - v.frameTimeEpochMs <= VisionMeasurementPolicy.MAX_AGE_MS
        if (!fresh || v.method == AnalysisMethod.NONE ||
            v.status !in setOf(AnalysisStatus.OK, AnalysisStatus.LOW_CONFIDENCE)) {
            reasons += "영상 분석 결과가 없거나 오래됐거나 시각이 맞지 않아 추천에 반영하지 않았습니다."
            return SceneInterpretation(reasons = reasons)
        }

        // These are the shared v2 acceptance rules. Neither overall nor semantic confidence
        // may stand in for an independently evaluated measurement quality.
        val colorUsable = VisionMeasurementPolicy.usableColor(v, nowEpochMs)
        val lightUsable = VisionMeasurementPolicy.usableLight(v, nowEpochMs)
        reasons += "색감 측정 품질: ${quality(v.colorMeasurementConfidence)} · 광량 측정 품질: ${quality(v.lightMeasurementConfidence)}"
        reasons += "차량 영역 제외: ${if (v.vehicleMaskApplied) "처리됨 (차량 빛·반사가 일부 남을 수 있음)" else "확인되지 않음"} · 전체 화면 중 제외 비율 ${quality(v.vehicleExcludedRatio)}"
        reasons += if (unit(v.semanticConfidence)) {
            "장소 해석 신뢰도: ${v.semanticConfidence} · 영업 여부나 상권의 활력을 뜻하지 않습니다."
        } else {
            "장소 의미: 미평가 — 상점의 종류나 영업 여부는 확인할 수 없습니다."
        }
        // B supplies user-facing observation warnings; keep their uncertainty intact.
        reasons += v.reasons.filter { it.isNotBlank() }.distinct().map { "영상 관측: $it" }

        if (lightUsable) {
            reasons += "차량 제외 후 밝기 ${v.colors!!.brightness} · 빛나는 영역 비율 ${v.colors.luminousAreaRatio}. 밝기만으로 활기찬 장소라고 판단하지 않습니다."
        } else {
            reasons += "밝기를 충분히 확인하지 못해 추천에 반영하지 않았습니다."
        }

        if (roadUsable && road.type == RoadType.HIGHWAY) {
            reasons += "고속도로에서는 차량 불빛이 섞일 수 있어 조명의 색감과 밝기를 음악 분위기에 반영하지 않습니다."
            return SceneInterpretation(reasons = reasons)
        }
        if (!colorUsable) {
            reasons += "차량을 제외한 색감을 충분히 확인하지 못해 추천에 반영하지 않았습니다."
            return SceneInterpretation(reasons = reasons)
        }

        val hue = v.colors!!.hueDegrees!!
        val moods = when {
            hue < 65f || hue > 330f -> listOf("따뜻한")
            hue >= 165f && hue < 255f -> listOf("차가운")
            else -> emptyList()
        }
        reasons += "차량을 제외한 화면의 색감을 확인했습니다. 색상 하나로 몽환적인 감정이나 장소의 종류·영업 상태를 판단하지 않습니다."
        if ("차가운" in moods) reasons += "측정한 파랑·청록 계열 색감에서 '차가운' 음악 분위기를 추가했습니다. 기온이나 운전자의 실제 감정을 뜻하지 않습니다."
        return SceneInterpretation(moods, reasons, v.colorMeasurementConfidence!! * .1f)
    }

    private fun unit(value: Float?): Boolean = value != null && value.isFinite() && value in 0f..1f
    private fun quality(value: Float?): String = if (unit(value)) value.toString() else "확인되지 않음"
    private fun time(value: Long?, zone: ZoneId): String = value?.takeIf { it > 0 }?.let {
        Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    } ?: "시각 미확인"
}

internal data class SceneInterpretation(
    val moodKeywords: List<String> = emptyList(),
    val reasons: List<String>,
    val confidenceContribution: Float = 0f,
)
