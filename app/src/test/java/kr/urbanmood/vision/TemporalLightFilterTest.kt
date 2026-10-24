package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

class TemporalLightFilterTest {
    private fun measurement(score:Float?,signature:Float=.1f,vehicle:Float=0f,confidence:Float=.4f) =
        LightMeasurement(score,MeasuredColors(null,null,null,null,.01f),vehicle,confidence,
            emptyList(),emptyList(),FloatArray(48){signature})
    @Test fun risingJitterSmoothsButNewFrameIdentityIsPreserved() {
        val filter=TemporalLightFilter()
        filter.apply(measurement(.2f),1_000_000_000L,1)
        val next=measurement(.4f).copy(colors=MeasuredColors(null,240f,.9f,1f,.02f))
        val output=filter.apply(next,1_250_000_000L,2)
        assertTrue(output.score!! in .21f.. .39f); assertEquals(next.colors,output.colors)
    }
    @Test fun vanishedLightsAndQualityFailureNeverKeepPreviousHighScore() {
        val filter=TemporalLightFilter()
        filter.apply(measurement(.8f),1_000_000_000L,1)
        assertEquals(0f,filter.apply(measurement(0f),1_250_000_000L,2).score!!,0f)
        assertNull(filter.apply(measurement(null),1_500_000_000L,3).score)
    }
    @Test fun restartTimeGapTimestampReversalAndSceneCutResetHistory() {
        val cases=listOf(Triple(1_250_000_000L,1L,.1f),Triple(3_000_000_000L,2L,.1f),
            Triple(900_000_000L,2L,.1f),Triple(1_250_000_000L,2L,.8f))
        for((time,sequence,signature) in cases) {
            val filter=TemporalLightFilter(); filter.apply(measurement(.1f),1_000_000_000L,1)
            assertEquals(.8f,filter.apply(measurement(.8f,signature),time,sequence).score!!,0f)
        }
    }
    @Test fun occlusionChangeAndExplicitResetDiscardPriorMeasurement() {
        val filter=TemporalLightFilter(); filter.apply(measurement(.2f),1_000_000_000L,1)
        assertEquals(.6f,filter.apply(measurement(.6f,vehicle=.5f),1_250_000_000L,2).score!!,0f)
        filter.reset()
        assertEquals(.9f,filter.apply(measurement(.9f),1_500_000_000L,3).score!!,0f)
    }
}
