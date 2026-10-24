package kr.urbanmood.integration

import android.content.Context
import kr.urbanmood.audio.LocalAudioPlayer
import kr.urbanmood.context.AndroidEnvironmentProvider
import kr.urbanmood.contracts.*
import kr.urbanmood.recommendation.MoodRecommendationEngine
import kr.urbanmood.vision.SemanticLightAnalyzer
import kr.urbanmood.vision.OrtSemanticFrameModel

/** Connects analysis, environment, recommendations and playback with explicit input provenance. */
class AppServices(context: Context, keyProvider: () -> String = { "" }) {
    val vision: VisionAnalyzer = SemanticLightAnalyzer(context.applicationContext) {
        OrtSemanticFrameModel(context.applicationContext)
    }
    val environment = AndroidEnvironmentProvider(context.applicationContext, apiKeyProvider = keyProvider)
    val recommendation: RecommendationEngine = MoodRecommendationEngine()
    val audio: AudioPlayer = LocalAudioPlayer(context.applicationContext)
    val audioAvailable = true
    val environmentAvailable = true
}
