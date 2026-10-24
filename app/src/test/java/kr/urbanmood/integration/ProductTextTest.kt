package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class ProductTextTest {
    @Test fun manualInputRemainsExplicitWithoutBecomingAMusicKeywordOrDebugLog() {
        val result = RecommendationResult(Availability.MANUAL,
            moodKeywords = listOf("야간", "따뜻한", "수동 입력"),
            reasons = listOf("HSV fixture", "모델 ABI fixture"))
        val text = recommendationText(result)
        assertTrue(text.contains("야간 · 따뜻한"))
        assertTrue(text.contains("수동 입력한 환경"))
        assertTrue(text.contains("음원을 선택"))
        assertFalse(text.contains("시연"))
        assertFalse(text.contains("HSV"))
        assertFalse(text.contains("ABI"))
    }

    @Test fun recommendationFailureIsShownAsFailure() {
        val text = recommendationText(RecommendationResult(Availability.ERROR))
        assertTrue(text.contains("준비하지 못했습니다"))
        assertFalse(text.contains("추천할 분위기"))
    }
}
