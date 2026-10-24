package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import java.util.Locale

private fun points(value: Float?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.KOREA, "%.1f", it) }
private fun scoreUnit(value: MoodScore) = if (value.contributions.any {
    it.delta != null && it.basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH
}) " 강도" else "점"

internal fun scoreSummaryText(value: MoodScoreSummary?): String {
    if (value == null) return "점수 근거 미확인 · 관측을 마치면 기여 항목을 확인할 수 있습니다."
    val basis = if (value.scores.any { score -> score.contributions.any { it.basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH } })
        "관측 특징" else "관측 점수"
    return "${value.sampledBucketCount}개 장면 · $basis\n" +
        value.scores.joinToString(" · ") { "${it.keyword} ${points(it.score)?.plus(scoreUnit(it)) ?: "미확인"}" }
}

internal fun scoreDetailsText(value: MoodScoreSummary?): String {
    if (value == null) return "누적 관측 근거가 아직 없습니다."
    return buildString {
        appendLine(scoreSummaryText(value))
        appendLine("관측 ${value.windowDurationMs / 1000}초 · 처리 프레임 ${value.receivedFrameCount}개 · 규칙 ${value.ruleVersion}")
        value.scores.forEach { score ->
            appendLine("\n${score.keyword} · ${points(score.score)?.plus(scoreUnit(score)) ?: "근거 부족"}")
            score.contributions.forEach { item ->
                val delta = points(item.delta)?.let { if (item.delta!! > 0) "+$it" else it }
                val observedBasis = item.basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH
                val unit = if (observedBasis) " 강도" else "점"
                val metricOnly = observedBasis && item.weight == 0f && item.delta == null && item.observedValue?.isFinite() == true
                val observed = item.observedValue?.takeIf { it.isFinite() }?.let { String.format(Locale.KOREA, "%.4f", it) }
                val displayed = if (metricOnly) "$observed ${item.unit.orEmpty()}" else delta?.plus(unit) ?: "미반영"
                appendLine("${item.label}: $displayed · ${item.reason}")
                if (observedBasis && !metricOnly) {
                    appendLine("관측 특징 강도 · 측정 품질 ${points(item.measurementQuality) ?: "미평가"}")
                }
                if (!metricOnly && observed != null) appendLine("관측값 $observed ${item.unit.orEmpty()}")
                item.source?.let { appendLine("출처: ${it.name}") }
                if (item.evidence.isNotEmpty()) appendLine("영역 근거 ${item.evidence.size}개")
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
