package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import java.util.Locale

internal data class DashboardMetric(val keyword: String, val value: String, val unit: String)

/** Present measured contributions without combining unlike units or turning missing input into zero. */
internal fun dashboardMetrics(summary: MoodScoreSummary?, keywords: List<String>): List<DashboardMetric> {
    val names = (keywords.filter { it != "수동 입력" } + listOf("활기 넘침", "따뜻한", "차가운"))
        .map { if (it == "활기 넘침" && summary?.scores?.any { score -> score.keyword == "시각적 활력" } == true) "시각적 활력" else it }
        .distinct().take(3)
    return names.map { name ->
        val score = summary?.scores?.firstOrNull { it.keyword == name }
        val preferred = if (name in listOf("따뜻한", "차가운"))
            listOf(ContributionBasis.SKY_COLOR_RULE, ContributionBasis.OBSERVED_FEATURE_STRENGTH, ContributionBasis.LEGACY_HEURISTIC)
        else listOf(ContributionBasis.OBSERVED_FEATURE_STRENGTH, ContributionBasis.SKY_COLOR_RULE, ContributionBasis.LEGACY_HEURISTIC)
        val basis = preferred.firstOrNull { kind ->
            score?.contributions?.any { it.basis == kind && it.delta?.isFinite() == true } == true
        }
        val amount = basis?.let { kind -> score!!.contributions.filter { it.basis == kind }
            .mapNotNull { it.delta?.takeIf(Float::isFinite) }.sum() }
        DashboardMetric(name, amount?.let { String.format(Locale.KOREA, if (it != 0f && kotlin.math.abs(it) < .1f) "%.2f" else "%.1f", it) } ?: "—",
            when (basis) {
                ContributionBasis.OBSERVED_FEATURE_STRENGTH -> "관측 강도"
                ContributionBasis.SKY_COLOR_RULE -> "하늘색 점수"
                ContributionBasis.LEGACY_HEURISTIC -> "규칙 점수"
                null -> "관측 대기"
            })
    }
}
