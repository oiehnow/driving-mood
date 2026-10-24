package kr.urbanmood.integration

import kr.urbanmood.contracts.LightingPreviewOutput

object LightingPreview {
    val presets = listOf(
        LightingPreviewOutput(null, "warm", "앰버", 0xffffbd77.toInt(), .55f, "직접 선택"),
        LightingPreviewOutput(null, "cool", "아이스 블루", 0xff88cfff.toInt(), .5f, "직접 선택"),
        LightingPreviewOutput(null, "soft", "소프트 민트", 0xff9de4cb.toInt(), .4f, "직접 선택"),
    )
    fun suggestion(result: CompletedAnalysis): LightingPreviewOutput? {
        val keywords = result.recommendation.moodKeywords
        val preset = when {
            "따뜻한" in keywords -> presets[0]
            "차가운" in keywords || "청량한" in keywords -> presets[1]
            else -> return null
        }
        return preset.copy(requestId = result.snapshot.requestId, reason = "추천 무드")
    }
}
