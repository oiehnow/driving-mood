package kr.urbanmood.integration

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

class VisionQualityTextTest {
    @Test fun missingEvidenceIsNotDisplayedAsZeroOrLegacyConfidence() {
        val text = visionQualityText(VisionResult(1, AnalysisStatus.LOW_CONFIDENCE, confidence = .9f))
        assertTrue(text.contains("색감 측정 품질 미평가"))
        assertTrue(text.contains("장소 의미 확실성 미평가"))
        assertTrue(text.contains("제외 처리 미확인"))
        assertFalse(text.contains("90%"))
    }

    @Test fun measuredColorRemainsSeparateFromUnsupportedMeaning() {
        val text = visionQualityText(VisionResult(1, AnalysisStatus.LOW_CONFIDENCE,
            colorMeasurementConfidence = .6f, lightMeasurementConfidence = Float.NaN, vehicleMaskApplied = true))
        assertTrue(text.contains("색감 측정 품질 60%"))
        assertTrue(text.contains("광량 측정 품질 미평가"))
        assertTrue(text.contains("장소 의미 확실성 미평가"))
        assertTrue(text.contains("영업/상권 활력 판단 아님"))
    }
}
