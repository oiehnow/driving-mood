package kr.urbanmood.contracts

/** Shared minimum acceptance boundary, not a claim of model accuracy.
 * C may add stricter scene/road checks. Never relax these by promoting status to OK.
 * Semantic evidence and the legacy overall confidence cannot enable pixel measurements.
 */
object VisionMeasurementPolicy {
    const val MAX_AGE_MS = 10_000L
    const val MIN_CONFIDENCE = 0.5f
    const val MAX_VEHICLE_RATIO_EXCLUSIVE = 0.4f

    fun usableColor(value: VisionResult, nowEpochMs: Long): Boolean =
        usableBase(value, nowEpochMs) && quality(value.colorMeasurementConfidence) &&
            value.colors?.let { c ->
                c.hueDegrees?.let { it.isFinite() && it >= 0f && it < 360f } == true &&
                    unit(c.saturation) && c.saturation!! > 0.2f
            } == true

    /** Brightness and luminous area are observations, never proof of commercial activity. */
    fun usableLight(value: VisionResult, nowEpochMs: Long): Boolean =
        usableBase(value, nowEpochMs) && quality(value.lightMeasurementConfidence) &&
            value.colors?.let { unit(it.brightness) && unit(it.luminousAreaRatio) } == true

    private fun usableBase(value: VisionResult, nowEpochMs: Long): Boolean =
        nowEpochMs > 0 && value.frameTimeEpochMs > 0 && value.frameTimeEpochMs <= nowEpochMs &&
            nowEpochMs - value.frameTimeEpochMs <= MAX_AGE_MS &&
            value.status in setOf(AnalysisStatus.OK, AnalysisStatus.LOW_CONFIDENCE) &&
            value.method != AnalysisMethod.NONE && value.vehicleMaskApplied &&
            unit(value.vehicleExcludedRatio) && value.vehicleExcludedRatio!! < MAX_VEHICLE_RATIO_EXCLUSIVE

    private fun unit(value: Float?): Boolean = value != null && value.isFinite() && value in 0f..1f
    private fun quality(value: Float?): Boolean = unit(value) && value!! >= MIN_CONFIDENCE
}
