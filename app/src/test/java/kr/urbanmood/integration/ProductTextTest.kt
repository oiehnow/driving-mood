package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class ProductTextTest {
    @Test fun measuredZeroSignContributionKeepsItsReasonAndMissingRemainsUnapplied() {
        val summary = MoodScoreSummary("test-rule", 50_000, 1, 1, listOf(MoodScore("활기 넘침", 0f,
            listOf(MoodScoreContribution("sign-light", "간판 빛", delta = 0f,
                reason = "주변 빛과 완전히 중복되어 추가 기여 없음"),
                MoodScoreContribution("missing-sign", "미측정 간판", reason = "픽셀 측정 미지원")))))
        val text = scoreDetailsText(summary)
        assertTrue(text.contains("간판 빛: 0.0점 · 주변 빛과 완전히 중복되어 추가 기여 없음"))
        assertTrue(text.contains("미측정 간판: 미반영 · 픽셀 측정 미지원"))
        assertFalse(text.contains("미측정 간판: 0.0점"))
    }
    @Test fun ledgerShowsMissingEvidenceAsMissingAndOnlyDisplaysActualDeltas() {
        val summary = MoodScoreSummary("test-rule", 50_000, 20, 2, listOf(
            MoodScore("따뜻한", null, listOf(MoodScoreContribution("color", "색감", reason = "측정 미지원"))),
            MoodScore("활기 넘침", 8f, listOf(MoodScoreContribution("people", "사람 검출", 2f,
                delta = 8f, reason = "프레임 평균 · 그룹 상한 적용",
                evidence = listOf(RegionScoreReference(0, "p0", 4f), RegionScoreReference(1_000, "p1", 4f)))))
        ))
        val text = scoreDetailsText(summary)
        assertTrue(text.contains("따뜻한 미확인"))
        assertTrue(text.contains("색감: 미반영"))
        assertTrue(text.contains("사람 검출: +8.0점"))
        assertTrue(text.contains("영역 근거 2개"))
        assertFalse(text.contains("따뜻한 0"))
        assertFalse(text.contains("-8.0점"))
    }
    @Test fun manualInputRemainsExplicitWithoutBecomingAMusicKeywordOrDebugLog() {
        val result = RecommendationResult(Availability.MANUAL,
            moodKeywords = listOf("야간", "따뜻한", "수동 입력"),
            reasons = listOf("HSV fixture", "모델 ABI fixture"))
        val text = recommendationText(result)
        assertTrue(text.contains("야간 · 따뜻한"))
        assertTrue(text.contains("직접 입력한 환경"))
        assertTrue(text.contains("추천할 곡이 없어요."))
        assertFalse(text.contains("시연"))
        assertFalse(text.contains("HSV"))
        assertFalse(text.contains("ABI"))
    }

    @Test fun recommendationFailureIsShownAsFailure() {
        val text = recommendationText(RecommendationResult(Availability.ERROR))
        assertTrue(text.contains("음악을 추천하지 못했어요."))
        assertFalse(text.contains("추천할 분위기"))
    }

    @Test fun measuredStatisticsAreNotLabeledAsMissingOrLegacyPoints() {
        val basis = ContributionBasis.OBSERVED_FEATURE_STRENGTH
        val summary = MoodScoreSummary("scene-v2.0", 30_000, 30, 30, listOf(MoodScore("따뜻한", .2f, listOf(
            MoodScoreContribution("feature_warm", "색감 강도", weight = 70f, delta = .2f, reason = "실측", basis = basis),
            MoodScoreContribution("feature_area", "밝은 면적", observedValue = .0012f, unit = "화면 비율", reason = "실측", basis = basis)
        ))))
        val text = scoreDetailsText(summary)
        assertTrue(text.contains("따뜻한 0.2 강도"))
        assertTrue(text.contains("밝은 면적: 0.0012 화면 비율"))
        assertFalse(text.contains("밝은 면적: 미반영"))
        assertFalse(text.contains("0.2점"))
    }
}
