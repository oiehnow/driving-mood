package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

/** Authored procedural fixtures: regression behavior, NOT real-video accuracy evaluation. */
class LightMeasurerTest {
    private val core=LightMeasurer()
    private fun frame(background:Int=0xff202020.toInt(), draw:(IntArray,Int,Int)->Unit = {_,_,_->}):PixelFrame {
        val w=200; val h=120; val p=IntArray(w*h) { background }; draw(p,w,h); return PixelFrame(w,h,p)
    }
    private fun paint(p:IntArray,w:Int,l:Int,t:Int,r:Int,b:Int,color:Int) {
        for(y in t until b) for(x in l until r) p[y*w+x]=color
    }
    private fun sign(color:Int=0xffffa030.toInt())=frame { p,w,_ -> paint(p,w,20,30,55,46,color) }

    @Test fun blackHasNoInventedColor() {
        val result=core.measure(frame(0xff000000.toInt()),emptyList(),false)
        assertEquals(0f,result.score!!,0f); assertNull(result.colors.hue)
        assertNull(result.colors.brightness); assertEquals(0f,result.vehicleRatio,0f)
        assertTrue(result.reasons.any { "어두워" in it }); assertTrue(result.confidence<.2f)
    }
    @Test fun planarColoredLightIsMeasuredFromPixels() {
        val warm=core.measure(sign(),emptyList(),false)
        val cool=core.measure(sign(0xff3060ff.toInt()),emptyList(),false)
        assertTrue(warm.score!!>.1f)
        assertEquals(35*16f/(200*120),warm.colors.luminousArea,.00001f)
        assertEquals(0xffffa030.toInt(),warm.colors.argb)
        assertTrue(warm.colors.hue!! in 30f..40f); assertTrue(cool.colors.hue!! in 220f..230f)
        assertTrue(warm.confidence<=.55f)
    }
    @Test fun detectedVehiclesRemoveLightsWithoutAddingOverlappingAreasTwice() {
        val image=sign()
        val car=ObjectBox(.09f,.23f,.29f,.40f,"car",.9f)
        val unmasked=core.measure(image,emptyList(),false)
        val once=core.measure(image,listOf(car),false)
        val twice=core.measure(image,listOf(car,car),false)
        assertTrue(unmasked.score!!>once.score!!); assertEquals(0f,once.score!!,0f)
        assertEquals(once.vehicleRatio,twice.vehicleRatio,0f)
        assertTrue(once.vehicleRatio in .02f.. .3f)
    }
    @Test fun vehicleReflectionMaskIsNotCountedAsVehicleArea() {
        val image=frame { p,w,_ -> paint(p,w,25,60,45,110,0xffff4040.toInt()) }
        val result=core.measure(image,listOf(ObjectBox(.10f,.25f,.25f,.43f,"car",.8f)),false)
        assertEquals(0f,result.score!!,0f)
        assertTrue(result.vehicleRatio<.1f)
    }
    @Test fun trafficLightDoesNotInflateVehicleRatioOrLightScore() {
        val result=core.measure(sign(),listOf(ObjectBox(.09f,.23f,.29f,.40f,"traffic light",.9f)),false)
        assertEquals(0f,result.vehicleRatio,0f); assertEquals(0f,result.score!!,0f)
    }
    @Test fun brightLowerRoadDoesNotLookLikeRetail() {
        val image=frame { p,w,_ -> paint(p,w,0,100,200,110,0xffff9020.toInt()) }
        assertEquals(0f,core.measure(image,emptyList(),false).score!!,0f)
    }
    @Test fun whiteLightPairWithoutVehicleDetectionIsUncertain() {
        val image=frame { p,w,_ ->
            paint(p,w,70,48,78,54,0xffffffff.toInt()); paint(p,w,100,48,108,54,0xffffffff.toInt())
        }
        val result=core.measure(image,emptyList(),false)
        assertEquals(0f,result.score!!,0f); assertEquals(0f,result.vehicleRatio,0f)
        assertTrue(result.reasons.any { "나란한" in it })
    }
    @Test fun broadDisplayAndVerticalStreakAreNotStoreEvidence() {
        val image=frame { p,w,_ ->
            paint(p,w,20,25,180,45,0xffffa020.toInt()); paint(p,w,5,48,9,90,0xffffa020.toInt())
        }
        val result=core.measure(image,emptyList(),false)
        assertEquals(0f,result.score!!,0f); assertTrue(result.reasons.any { "전광판" in it })
    }
    @Test fun overexposureIsMissingScoreNotNormalZero() {
        val result=core.measure(frame(0xffffffff.toInt()),emptyList(),false)
        assertNull(result.score); assertEquals(0f,result.confidence,0f)
    }
    @Test fun largeClippedNeutralCoreIsNotPositiveShopEvidence() {
        val result=core.measure(sign(0xffffffff.toInt()),emptyList(),false)
        assertEquals(0f,result.score!!,0f)
        assertTrue(result.reasons.any { "흰색 간판도 빠질" in it })
    }
    @Test fun invalidBoxesCannotProduceNanOrOutOfBoundsRegions() {
        val result=core.measure(sign(),listOf(ObjectBox(Float.NaN,0f,1f,1f,"car",.8f),
            ObjectBox(-2f,-3f,2f,4f,"truck",.9f)),false)
        assertEquals(1f,result.vehicleRatio,0f); assertEquals(0f,result.score!!,0f)
        assertTrue(result.regions.all { it.left in 0f..1f && it.right in 0f..1f && it.top in 0f..1f && it.bottom in 0f..1f })
    }
    @Test fun detectorCapacityLowersEvidenceAndConfidence() {
        val normal=core.measure(sign(),emptyList(),false)
        val limited=core.measure(sign(),emptyList(),true)
        assertTrue(limited.score!!<normal.score!!); assertTrue(limited.confidence<normal.confidence)
        assertTrue(limited.reasons.any { "일부 차량 불빛이 남을" in it })
    }
    @Test fun circularRedHueDoesNotAverageToCyan() {
        val image=frame { p,w,_ ->
            paint(p,w,20,30,38,46,0xffff2000.toInt()); paint(p,w,38,30,56,46,0xffff0020.toInt())
        }
        val hue=core.measure(image,emptyList(),false).colors.hue!!
        assertTrue(hue>=0f && hue<360f)
        assertTrue(hue<10f || hue>350f)
    }
    @Test fun achromaticLightHasNoHue() {
        val result=core.measure(sign(0xffeeeeee.toInt()),emptyList(),false)
        assertNull(result.colors.hue); assertEquals(0f,result.colors.saturation!!,0f)
    }
    @Test(timeout=4000) fun fragmentedNoiseHasBoundedPairComparisons() {
        val pixels=IntArray(320*240) { i -> if((i%320)%3<2 && (i/320)%3==0) 0xffffffff.toInt() else 0xff101010.toInt() }
        val result=core.measure(PixelFrame(320,240,pixels),emptyList(),false)
        assertTrue(result.reasons.any { "흩어져" in it }); assertEquals(0f,result.score!!,0f)
    }
}
