package kr.urbanmood.integration

import kr.urbanmood.contracts.VisionResult

/** Separate observations from scene meaning without claiming recommendation integration. */
internal fun visionQualityText(value: VisionResult): String {
    fun score(value: Float?) = value?.takeIf { it.isFinite() && it in 0f..1f }
        ?.let { "${(it * 100).toInt()}%" } ?: "미평가"
    return "색감 측정 품질 ${score(value.colorMeasurementConfidence)} · 광량 측정 품질 ${score(value.lightMeasurementConfidence)}" +
        "\n장소 의미 확실성 ${score(value.semanticConfidence)}" +
        "\n차량·불빛 제외 처리 ${if (value.vehicleMaskApplied) "수행됨" else "미확인"}"
}
