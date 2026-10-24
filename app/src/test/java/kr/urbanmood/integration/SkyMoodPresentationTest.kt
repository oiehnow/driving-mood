package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class SkyMoodPresentationTest {
    private fun completed(result: RecommendationResult) = CompletedAnalysis(
        AnalysisSnapshot(7, 1000, EnvironmentSnapshot(), VisionResult(1000, AnalysisStatus.LOW_CONFIDENCE)), result)

    @Test fun lightStrengthAndSkyRulePointsNeverBecomeAMixedTotal() {
        val summary = MoodScoreSummary("test", 30000, 30, 30, listOf(MoodScore("따뜻한", 999f, listOf(
            MoodScoreContribution("feature_warm", "조명 색감", weight=70f, delta=.2f, reason="조명 관측", basis=ContributionBasis.OBSERVED_FEATURE_STRENGTH),
            MoodScoreContribution("sky_color_warm", "하늘 색감", weight=20f, delta=8f, reason="하늘 규칙", basis=ContributionBasis.SKY_COLOR_RULE)
        ))))
        val text = scoreDetailsText(summary)
        assertTrue(text.contains("조명 관측강도 0.2 / 하늘색 규칙점수 8.0점"))
        assertTrue(text.contains("하늘 색감: +8.0 규칙점"))
        assertTrue(text.contains("측정 품질 미평가 · 확률 아님"))
        assertFalse(text.contains("999.0")); assertFalse(text.contains("8.2"))
    }
    @Test fun missingSkyAndMeasuredZeroStayDistinctWithMetricOnlyEvidence() {
        val summary = MoodScoreSummary("test", 30000, 2, 2, listOf(
            MoodScore("따뜻한", null, listOf(MoodScoreContribution("sky_color_warm", "하늘색", reason="미측정", basis=ContributionBasis.SKY_COLOR_RULE))),
            MoodScore("차가운", 0f, listOf(MoodScoreContribution("sky_color_cool", "하늘색", delta=0f, reason="측정0", basis=ContributionBasis.SKY_COLOR_RULE),
                MoodScoreContribution("sky_color_coverage", "유효 구간", observedValue=.5f, unit="구간 비율", reason="기간 기준", basis=ContributionBasis.SKY_COLOR_RULE)))
        ))
        val text = scoreDetailsText(summary)
        assertTrue(text.contains("따뜻한 하늘색 규칙점수 미확인"))
        assertTrue(text.contains("차가운 하늘색 규칙점수 0.0점"))
        assertTrue(text.contains("유효 구간: 0.5000 구간 비율"))
    }
    @Test fun personalMusicChoiceCannotRewriteCommonMoodOrLighting() {
        val result = RecommendationResult(Availability.AVAILABLE, moodKeywords=listOf("차가운"), commonMoodKeywords=listOf("따뜻한"))
        val text = recommendationText(result)
        assertTrue(text.contains("주변 무드: 따뜻한\n추천 분위기: 차가운"))
        val light = LightingPreview.suggestion(completed(result))!!
        assertEquals("warm", light.presetId)
        assertEquals(result.commonMoodKeywords, light.moodKeywords)
        assertEquals(7L, light.requestId)
        assertEquals(LightingTarget.PREVIEW_ONLY, light.target)
    }
    @Test fun mixedCommonColorSelectsAPreviewAndDetachesItsKeywords() {
        val words = mutableListOf("따뜻한", "차가운", "다채로운")
        val result = RecommendationResult(Availability.AVAILABLE, commonMoodKeywords=words)
        val light = LightingPreview.suggestion(completed(result))!!
        assertEquals("mixed", light.presetId)
        assertEquals(words, light.moodKeywords)
        words.clear()
        assertEquals(listOf("따뜻한", "차가운", "다채로운"), light.moodKeywords)
    }
    @Test fun missingCommonEvidenceDoesNotBecomeAQuietMoodOrMusicDerivedLight() {
        val result = RecommendationResult(Availability.AVAILABLE, moodKeywords=listOf("따뜻한"))
        assertNull(LightingPreview.suggestion(completed(result)))
        assertTrue(recommendationText(result).startsWith("주변 무드: 판단 어려움"))
        assertFalse(recommendationText(result).contains("몽환"))
        assertFalse(recommendationText(result).contains("잔잔"))
    }
}
