package kr.urbanmood.contracts

import org.junit.Assert.*
import org.junit.Test

class VisionMeasurementPolicyTest {
    private val now = 50_000L
    private fun measured() = VisionResult(
        frameTimeEpochMs = now, status = AnalysisStatus.LOW_CONFIDENCE,
        colors = ColorFeatures(hueDegrees = 30f, saturation = .6f, brightness = .7f, luminousAreaRatio = .1f),
        vehicleExcludedRatio = .1f, method = AnalysisMethod.HYBRID,
        colorMeasurementConfidence = .6f, lightMeasurementConfidence = .6f, vehicleMaskApplied = true,
    )

    @Test fun lowOverallConfidenceDoesNotBlockIndependentlyValidMeasurements() {
        val v = measured().copy(confidence = .1f, semanticConfidence = null)
        assertTrue(VisionMeasurementPolicy.usableColor(v, now))
        assertTrue(VisionMeasurementPolicy.usableLight(v, now))
        assertEquals(AnalysisStatus.LOW_CONFIDENCE, v.status)
    }

    @Test fun legacyOrSemanticScoresCannotSupplyMissingMeasurementEvidence() {
        val v = measured().copy(status = AnalysisStatus.OK, confidence = 1f, semanticConfidence = 1f,
            colorMeasurementConfidence = null, lightMeasurementConfidence = null)
        assertFalse(VisionMeasurementPolicy.usableColor(v, now))
        assertFalse(VisionMeasurementPolicy.usableLight(v, now))
        assertFalse(VisionResult(now, AnalysisStatus.OK).vehicleMaskApplied)
    }

    @Test fun colorAndLightAreIndependent() {
        assertTrue(VisionMeasurementPolicy.usableColor(measured().copy(lightMeasurementConfidence = null), now))
        assertFalse(VisionMeasurementPolicy.usableLight(measured().copy(lightMeasurementConfidence = null), now))
        assertTrue(VisionMeasurementPolicy.usableLight(measured().copy(colorMeasurementConfidence = null), now))
    }

    @Test fun rejectsMissingMaskExcessVehiclesAndFailedAnalysis() {
        val rejected = listOf(measured().copy(vehicleMaskApplied = false),
            measured().copy(vehicleExcludedRatio = null), measured().copy(vehicleExcludedRatio = .4f),
            measured().copy(vehicleExcludedRatio = Float.NaN), measured().copy(status = AnalysisStatus.FAILED),
            measured().copy(status = AnalysisStatus.MODEL_NOT_READY), measured().copy(method = AnalysisMethod.NONE))
        rejected.forEach {
            assertFalse(VisionMeasurementPolicy.usableColor(it, now))
            assertFalse(VisionMeasurementPolicy.usableLight(it, now))
        }
        assertTrue(VisionMeasurementPolicy.usableColor(measured().copy(vehicleExcludedRatio = 0f), now))
    }

    @Test fun rejectsStaleFutureAndInvalidTimestamps() {
        listOf(now - 10_001, now + 1, 0, Long.MIN_VALUE).forEach {
            assertFalse(VisionMeasurementPolicy.usableColor(measured().copy(frameTimeEpochMs = it), now))
        }
        assertTrue(VisionMeasurementPolicy.usableColor(measured().copy(frameTimeEpochMs = now - 10_000), now))
    }

    @Test fun rejectsInvalidScoresWithoutClampingThemIntoValidity() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f, .499f).forEach {
            assertFalse(VisionMeasurementPolicy.usableColor(measured().copy(colorMeasurementConfidence = it), now))
            assertFalse(VisionMeasurementPolicy.usableLight(measured().copy(lightMeasurementConfidence = it), now))
        }
        assertTrue(VisionMeasurementPolicy.usableColor(measured().copy(colorMeasurementConfidence = .5f), now))
    }

    @Test fun rejectsAchromaticOrInvalidColorButAllowsIndependentLight() {
        listOf(ColorFeatures(hueDegrees = Float.NaN, saturation = .6f),
            ColorFeatures(hueDegrees = 360f, saturation = .6f),
            ColorFeatures(hueDegrees = 30f, saturation = .2f)).forEach {
            assertFalse(VisionMeasurementPolicy.usableColor(measured().copy(colors = it), now))
        }
        assertTrue(VisionMeasurementPolicy.usableLight(measured().copy(colors = measured().colors!!.copy(hueDegrees = null)), now))
    }

    @Test fun lightRequiresActualFiniteBrightnessAndAreaNotJustLegacyScore() {
        listOf<ColorFeatures?>(null, ColorFeatures(brightness = .5f),
            ColorFeatures(brightness = Float.NaN, luminousAreaRatio = .1f),
            ColorFeatures(brightness = .5f, luminousAreaRatio = 1.1f)).forEach {
            assertFalse(VisionMeasurementPolicy.usableLight(measured().copy(colors = it, urbanLightScore = 1f), now))
        }
    }
}
