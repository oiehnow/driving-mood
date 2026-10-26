package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import java.util.Locale

private fun points(value: Float?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.KOREA, "%.1f", it) }
private fun scoreUnit(value: MoodScore) = if (value.contributions.any { it.basis == ContributionBasis.WEATHER_RULE }) " 날씨점" else if (value.contributions.any {
    it.delta != null && it.basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH
}) " 강도" else "점"

/** Unlike units are shown separately; a displayed total must not add rule points to area strength. */
private fun displayedScore(value: MoodScore): String {
    if (value.contributions.none { it.basis == ContributionBasis.SKY_COLOR_RULE })
        return points(value.score)?.plus(scoreUnit(value)) ?: "미확인"
    return listOf(
        ContributionBasis.OBSERVED_FEATURE_STRENGTH to "조명 관측강도",
        ContributionBasis.LEGACY_HEURISTIC to "기존 규칙점수",
        ContributionBasis.SKY_COLOR_RULE to "하늘색 규칙점수",
        ContributionBasis.WEATHER_RULE to "날씨 규칙점수",
    ).mapNotNull { (basis, label) ->
        val contributions = value.contributions.filter { it.basis == basis }
        if (contributions.isEmpty()) return@mapNotNull null
        val amounts = contributions.mapNotNull { it.delta?.takeIf(Float::isFinite) }
        "$label ${if (amounts.isEmpty()) "미확인" else points(amounts.sum()) + if (basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH) "" else "점"}"
    }.joinToString(" / ")
}

internal fun scoreSummaryText(value: MoodScoreSummary?): String {
    if (value == null) return "점수 근거 미확인 · 관측을 마치면 기여 항목을 확인할 수 있습니다."
    val basis = if (value.scores.any { score -> score.contributions.any { it.basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH } })
        "관측 특징" else "관측 점수"
    return "${value.sampledBucketCount}개 장면 · $basis\n" +
        value.scores.joinToString(" · ") { "${it.keyword} ${displayedScore(it)}" }
}

internal fun scoreDetailsText(value: MoodScoreSummary?): String {
    if (value == null) return "누적 관측 근거가 아직 없습니다."
    return buildString {
        appendLine(scoreSummaryText(value))
        appendLine("관측 ${value.windowDurationMs / 1000}초 · 처리 프레임 ${value.receivedFrameCount}개 · 규칙 ${value.ruleVersion}")
        value.scores.forEach { score ->
            appendLine("\n${score.keyword} · ${displayedScore(score)}")
            score.contributions.forEach { item ->
                val delta = points(item.delta)?.let { if (item.delta!! > 0) "+$it" else it }
                val observedBasis = item.basis == ContributionBasis.OBSERVED_FEATURE_STRENGTH
                val skyBasis = item.basis == ContributionBasis.SKY_COLOR_RULE
                val unit = if (observedBasis) " 강도" else if (skyBasis) " 규칙점" else if (item.basis == ContributionBasis.WEATHER_RULE) " 날씨점" else "점"
                val metricOnly = (observedBasis || skyBasis) && item.weight == 0f && item.delta == null && item.observedValue?.isFinite() == true
                val observed = item.observedValue?.takeIf { it.isFinite() }?.let { String.format(Locale.KOREA, "%.4f", it) }
                val displayed = if (metricOnly) "$observed ${item.unit.orEmpty()}" else delta?.plus(unit) ?: "미반영"
                appendLine("${item.label}: $displayed · ${item.reason}")
                if (observedBasis && !metricOnly) {
                    appendLine("관측 특징 강도 · 측정 품질 ${points(item.measurementQuality) ?: "미평가"}")
                }
                if (skyBasis && !metricOnly) appendLine("하늘색 기반 규칙 · 측정 품질 ${points(item.measurementQuality) ?: "미평가"} · 확률 아님")
                if (!metricOnly && observed != null) appendLine("관측값 $observed ${item.unit.orEmpty()}")
                item.source?.let { appendLine("출처: ${it.name}") }
                if (item.evidence.isNotEmpty()) appendLine("영역 근거 ${item.evidence.size}개")
            }
        }
        value.notes.forEach { appendLine(it) }
    }.trim()
}

internal fun recommendationText(value: RecommendationResult): String {
    if (value.availability == Availability.ERROR) return "음악을 추천하지 못했어요. 다시 시도해 주세요."
    val words = value.moodKeywords.filterNot { it == "수동 입력" }
    val common = value.commonMoodKeywords.filterNot { it == "수동 입력" }
    return "주변 무드: ${common.joinToString(" · ").ifEmpty { "판단 어려움" }}\n" +
        "추천 분위기: ${words.joinToString(" · ").ifEmpty { "추천 정보 없음" }}" +
        (if (value.availability == Availability.MANUAL) "\n직접 입력한 환경을 반영했어요." else "") +
        (if (value.candidates.isEmpty()) "\n추천할 곡이 없어요." else "")
}

internal fun roadLabel(value: RoadType): String = when (value) {
    RoadType.HIGHWAY -> "고속도로"
    RoadType.URBAN -> "도심"
    RoadType.RESIDENTIAL -> "주거 지역"
    RoadType.OTHER -> "기타"
    RoadType.UNKNOWN -> "알 수 없음"
}
