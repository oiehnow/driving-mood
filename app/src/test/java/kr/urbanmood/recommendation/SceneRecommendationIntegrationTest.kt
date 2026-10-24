package kr.urbanmood.recommendation

import android.net.Uri
import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Synthetic evidence + real recommendation/catalog pipeline. No camera or audio playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], manifest=Config.NONE)
class SceneRecommendationIntegrationTest {
    @Test fun measuredHueChangesActualTaggedTrackPreferenceWithoutClaimingSemantics() {
        val now = 1_792_762_200_000L
        val tracks = listOf(
            LocalTrack("warm", Uri.parse("content://test/warm"), "warm", tags=setOf("따뜻한")),
            LocalTrack("cool", Uri.parse("content://test/cool"), "cool", tags=setOf("차가운")),
        )
        fun warmSelections(hue: Float): Int {
            val engine = MoodRecommendationEngine(Random(12))
            val vision = VisionResult(now, AnalysisStatus.LOW_CONFIDENCE,
                colors=ColorFeatures(hueDegrees=hue, saturation=.8f),
                vehicleExcludedRatio=.1f, confidence=.1f, method=AnalysisMethod.HYBRID,
                colorMeasurementConfidence=.8f, vehicleMaskApplied=true)
            val request = RecommendationRequest(vision, EnvironmentSnapshot(zoneId="Asia/Seoul"),
                catalog=tracks, nowEpochMs=now)
            return (1..400).count { engine.recommend(request).candidates.first().id == "warm" }
        }
        // Same random stream, catalog and environment; only measured hue changes.
        assertTrue(warmSelections(30f) > warmSelections(220f) + 100)
    }
    @Test fun explicitMoodChangesActualCatalogWeightsAndClearingRestoresBaseline() {
        val now = 1_792_762_200_000L
        val tracks = listOf(
            LocalTrack("target",Uri.parse("content://test/target"),"target",tags=setOf("우울","활기 넘침")),
            LocalTrack("other",Uri.parse("content://test/other"),"other",tags=setOf("따뜻한")),
        )
        fun choices(moods: Set<String>): Int {
            val engine = MoodRecommendationEngine(Random(19))
            return (1..400).count {
                engine.recommend(RecommendationRequest(null,EnvironmentSnapshot(zoneId="Asia/Seoul"),
                    preferences=UserPreferences(preferredMoods=moods),catalog=tracks,nowEpochMs=now)).candidates.first().id == "target"
            }
        }
        val baseline = choices(emptySet())
        assertTrue(choices(setOf("우울")) > baseline + 100)
        assertTrue(choices(setOf("활기 넘침")) > baseline + 100)
        assertEquals(baseline,choices(setOf("unknown")))
        val engine = MoodRecommendationEngine(Random(19))
        val base = RecommendationRequest(null,EnvironmentSnapshot(zoneId="Asia/Seoul"),catalog=tracks,nowEpochMs=now)
        engine.recommend(base.copy(preferences=UserPreferences(preferredMoods=setOf("우울"))))
        val cleared = engine.recommend(base)
        assertFalse(cleared.moodKeywords.contains("우울"))
        assertFalse(cleared.youtubeQuery!!.contains("우울"))
    }
}
