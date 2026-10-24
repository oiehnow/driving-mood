package kr.urbanmood.integration

import kr.urbanmood.contracts.LightingPreviewOutput

object LightingPreview {
    val presets = listOf(
        LightingPreviewOutput(null, "warm", "앰버", 0xffffbd77.toInt(), .55f, "직접 선택"),
        LightingPreviewOutput(null, "cool", "아이스 블루", 0xff88cfff.toInt(), .5f, "직접 선택"),
        LightingPreviewOutput(null, "soft", "소프트 민트", 0xff9de4cb.toInt(), .4f, "직접 선택"),
        LightingPreviewOutput(null, "mixed", "라일락", 0xffc6a0e8.toInt(), .45f, "직접 선택"),
    )
    fun suggestion(result: CompletedAnalysis): LightingPreviewOutput? =
        suggestion(result.snapshot.requestId, result.recommendation.commonMoodKeywords)

    internal fun suggestion(requestId: Long, commonMoodKeywords: List<String>): LightingPreviewOutput? {
        val keywords = commonMoodKeywords.toList()
        val preset = when {
            "다채로운" in keywords || ("따뜻한" in keywords && "차가운" in keywords) -> presets[3]
            "따뜻한" in keywords -> presets[0]
            "차가운" in keywords || "청량한" in keywords -> presets[1]
            else -> return null
        }
        return preset.copy(requestId = requestId, reason = "공통 주변 무드 기반 · 고정 프리셋 미리보기", moodKeywords = keywords)
    }
}
