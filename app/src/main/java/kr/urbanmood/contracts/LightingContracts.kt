package kr.urbanmood.contracts

enum class LightingTarget { PREVIEW_ONLY }
/** A UI preset output, never a measured vehicle setting or a vehicle command. */
data class LightingPreviewOutput(
    val requestId: Long?,
    val presetId: String,
    val name: String,
    val colorArgb: Int,
    val brightness: Float,
    val reason: String,
    val target: LightingTarget = LightingTarget.PREVIEW_ONLY,
    val moodKeywords: List<String> = emptyList(), // shared observed-scene keywords, not a new lighting score
)
