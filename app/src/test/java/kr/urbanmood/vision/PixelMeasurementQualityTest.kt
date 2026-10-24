package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

/** Deterministic pixel regressions, not photographs or recognition accuracy estimates. */
class PixelMeasurementQualityTest {
    private val measurer=LightMeasurer()
    private fun patch(background:Int=0xff202020.toInt(), color:Int=0xffe09030.toInt()):PixelFrame {
        val pixels=IntArray(200*120) { background }
        for(y in 30 until 46) for(x in 20 until 55) pixels[y*200+x]=color
        return PixelFrame(200,120,pixels)
    }
    @Test fun measuredColorCanBeUsableWithoutClaimingSemanticConfidence() {
        val result=measurer.measure(patch(),emptyList(),false)
        assertTrue(result.confidence<=.55f)
        assertTrue(result.pixelQuality.colorConfidence>=.6f)
        assertTrue(result.pixelQuality.lightConfidence>=.6f)
        // Local contrast can reject the uniform interior; quality uses accepted pixels only.
        assertTrue(result.pixelQuality.acceptedPixelCount in 24..560)
        assertTrue(result.pixelQuality.validPixelRatio in .15f..1f)
    }
    @Test fun vehicleMaskRemovesTheOnlyPatchAndDisablesMeasurementUse() {
        val r=measurer.measure(patch(),listOf(ObjectBox(.09f,.23f,.29f,.40f,"car",.9f)),false)
        assertEquals(0,r.pixelQuality.acceptedPixelCount)
        assertEquals(0f,r.pixelQuality.colorConfidence,0f)
        assertEquals(0f,r.pixelQuality.lightConfidence,0f)
    }
    @Test fun lowLightAndClippedChannelDoNotGetUsefulQuality() {
        val dark=measurer.measure(patch(background=0xff000000.toInt()),emptyList(),false)
        val clipped=measurer.measure(patch(color=0xffff9030.toInt()),emptyList(),false)
        assertTrue(dark.pixelQuality.lightConfidence<.6f)
        assertTrue(clipped.pixelQuality.colorConfidence<.6f)
        assertEquals(1f,clipped.pixelQuality.candidateClippedRatio,0f)
    }
    @Test fun detectorCapacityAndMalformedBoxesCannotReceiveUsefulQuality() {
        val full=measurer.measure(patch(),emptyList(),true)
        assertTrue(full.pixelQuality.lightConfidence<.6f)
        val invalid=measurer.measure(patch(),listOf(ObjectBox(Float.NaN,0f,.5f,.5f,"car",.8f)),false)
        assertEquals(0f,invalid.pixelQuality.lightConfidence,0f)
    }
    @Test fun noCandidateTinyInputAndAllWhiteAreNotReliableZeroMeasurements() {
        for(frame in listOf(PixelFrame(1,1,intArrayOf(0xffe09030.toInt())),
            PixelFrame(200,120,IntArray(24000){0xffffffff.toInt()}),
            PixelFrame(200,120,IntArray(24000){0xff202020.toInt()}))) {
            val q=measurer.measure(frame,emptyList(),false).pixelQuality
            assertEquals(0f,q.lightConfidence,0f);assertEquals(0f,q.colorConfidence,0f)
        }
    }
    @Test fun balancedOpposingHuesReduceOnlyColorQuality() {
        val image=patch(color=0xffe03030.toInt())
        for(y in 30 until 46) for(x in 20 until 55) if((x+y)%2==0) image.argb[y*200+x]=0xff30e0e0.toInt()
        val result=measurer.measure(image,emptyList(),false)
        assertNull(result.colors.hue)
        assertTrue(result.pixelQuality.lightConfidence>=.6f)
        assertTrue(result.pixelQuality.colorConfidence<.6f)
    }
    @Test fun currentQualityIsNotCarriedFromAnEarlierGoodFrame() {
        val filter=TemporalLightFilter()
        val good=measurer.measure(patch(),emptyList(),false)
        val bad=measurer.measure(patch(),emptyList(),true)
        filter.apply(good,1_000_000_000,1)
        val output=filter.apply(bad,1_250_000_000,2)
        assertEquals(bad.pixelQuality,output.pixelQuality)
        assertTrue(output.pixelQuality.lightConfidence<.6f)
    }
    @Test fun dominantRoadReflectionCannotLendQualityToAnIsolatedPatch() {
        val image=patch()
        // Bright reflected band qualifies throughout, not only at its local-contrast edge.
        for(y in 100 until 120) for(x in 0 until 200) image.argb[y*200+x]=0xffff9030.toInt()
        val result=measurer.measure(image,emptyList(),false)
        assertTrue(result.pixelQuality.acceptedPixelCount>0)
        assertTrue(result.pixelQuality.lightConfidence<.5f)
        assertTrue(result.pixelQuality.colorConfidence<.5f)
    }
    @Test fun lightPairElsewhereMakesRemainingPatchConservative() {
        val image=patch()
        for(y in 48 until 54) for(x in 70 until 78) image.argb[y*200+x]=0xffffffff.toInt()
        for(y in 48 until 54) for(x in 100 until 108) image.argb[y*200+x]=0xffffffff.toInt()
        val result=measurer.measure(image,emptyList(),false)
        assertTrue(result.pixelQuality.acceptedPixelCount>0)
        assertTrue(result.pixelQuality.lightConfidence<.5f)
    }
}
