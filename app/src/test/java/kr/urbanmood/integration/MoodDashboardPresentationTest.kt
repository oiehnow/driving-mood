package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class MoodDashboardPresentationTest {
    @Test fun missingDataIsNotDisplayedAsMeasuredZero() {
        assertTrue(dashboardMetrics(null, emptyList()).all { it.value == "—" })
    }

    @Test fun mixedUnitsAreNotSummedAndMeasuredZeroIsPreserved() {
        val summary = MoodScoreSummary("test", 30_000, 3, 3, listOf(
            MoodScore("따뜻한", 22f, listOf(
                MoodScoreContribution("light", "조명", delta = 2f, reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH),
                MoodScoreContribution("sky", "하늘", delta = 20f, reason = "", basis = ContributionBasis.SKY_COLOR_RULE))),
            MoodScore("활기 넘침", 0f, listOf(MoodScoreContribution("people", "사람", delta = 0f, reason = "")))
        ))
        val metrics = dashboardMetrics(summary, listOf("따뜻한"))
        assertEquals("20.0", metrics[0].value)
        assertEquals("하늘색 점수", metrics[0].unit)
        assertEquals("0.0", metrics[1].value)
        assertEquals("—", metrics[2].value)
    }
    @Test fun activityUsesMeasuredVisualStrengthInsteadOfSmallPersonOnlyRule() {
        val summary = MoodScoreSummary("test", 30_000, 3, 3, listOf(
            MoodScore("시각적 활력", 1.7f, listOf(MoodScoreContribution("visual", "주변 빛", delta = 1.7f,
                reason = "", basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH))),
            MoodScore("활기 넘침", .02f, listOf(MoodScoreContribution("people", "사람", delta = .02f, reason = "")))
        ))
        val metric = dashboardMetrics(summary, listOf("활기 넘침")).first()
        assertEquals("시각적 활력", metric.keyword)
        assertEquals("1.7", metric.value)
        assertEquals("관측 강도", metric.unit)
    }
}
