package kr.urbanmood.integration

import kr.urbanmood.contracts.*

internal fun recommendationText(value: RecommendationResult): String {
    if (value.availability == Availability.ERROR) return "추천을 준비하지 못했습니다. 잠시 후 다시 시도하세요."
    val words = value.moodKeywords.filterNot { it == "수동 입력" }
    return (words.joinToString(" · ").ifEmpty { "추천할 분위기를 기다리고 있습니다." }) +
        (if (value.availability == Availability.MANUAL) "\n수동 입력한 환경을 반영했습니다." else "") +
        (if (value.candidates.isEmpty()) "\n음원을 선택하면 어울리는 곡을 추천합니다." else "")
}

internal fun roadLabel(value: RoadType): String = when (value) {
    RoadType.HIGHWAY -> "고속도로"
    RoadType.URBAN -> "도심"
    RoadType.RESIDENTIAL -> "주거 지역"
    RoadType.OTHER -> "기타"
    RoadType.UNKNOWN -> "알 수 없음"
}
