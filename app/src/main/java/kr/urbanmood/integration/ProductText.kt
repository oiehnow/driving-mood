package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import java.util.Locale

private fun points(value: Float?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.KOREA, "%.1f", it) }

internal fun scoreSummaryText(value: MoodScoreSummary?): String {
    if (value == null) return "점수 근거 미확인 · 관측을 마치면 기여 항목을 확인할 수 있습니다."
    return "${value.sampledBucketCount}개 시간 구간의 관측 · 사람 수를 시간에 걸쳐 합산하지 않습니다.\n" +
        value.scores.joinToString(" · ") { "${it.keyword} ${points(it.score)?.plus("점") ?: "미확인"}" } +
        "\n규칙 기반 근거 점수이며 감정이나 정확도 확률이 아닙니다."
}

internal fun scoreDetailsText(value: MoodScoreSummary?): String {
    if (value == null) return "누적 관측 근거가 아직 없습니다."
    return buildString {
        appendLine(scoreSummaryText(value))
        appendLine("관측 ${value.windowDurationMs / 1000}초 · 처리 프레임 ${value.receivedFrameCount}개 · 규칙 ${value.ruleVersion}")
        value.scores.forEach { score ->
            appendLine("\n${score.keyword} · ${points(score.score)?.plus("점") ?: "근거 부족"}")
            score.contributions.forEach { item ->
                val delta = points(item.delta)?.let { if (item.delta!! > 0) "+$it" else it }
                appendLine("${item.label}: ${delta?.plus("점") ?: "미반영"} · ${item.reason}")
                item.observedValue?.takeIf { it.isFinite() }?.let { appendLine("관측값 ${points(it)} ${item.unit.orEmpty()}") }
                item.source?.let { appendLine("출처: ${it.name}") }
                if (item.evidence.isNotEmpty()) appendLine("프레임 내 영역 참조 ${item.evidence.size}개 · 그룹 기여의 분배이며 중복 가산하지 않습니다.")
            }
        }
        value.notes.forEach { appendLine(it) }
    }.trim()
}

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
