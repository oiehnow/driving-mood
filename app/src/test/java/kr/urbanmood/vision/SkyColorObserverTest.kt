package kr.urbanmood.vision

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

/** Geometry/provenance tests use a fixed all-red oracle to isolate mask alignment. */
class SkyColorObserverTest {
    private val frame=SkyFrameIdentity(1,1000,50,FrameInputSource.LIVE_CAMERA)
    private val model="a".repeat(64)
    private fun rgb(w:Int=3,h:Int=3)=PixelFrame(w,h,IntArray(w*h){0xffff0000.toInt()})
    private fun mask(w:Int=3,h:Int=3,values:ByteArray=ByteArray(w*h){2},id:SkyFrameIdentity=frame)=SemanticSkyMask(w,h,values,id,model)
    private fun red(n:Int)=SkyColorStatistics(n,listOf(n)+List(11){0},listOf(n.toDouble())+List(11){0.0},0,n.toDouble(),n*.2126,listOf(n,0,0,0))
    private val oracle=SkyColorCalculator { _,selected -> red(selected.count { it }) }
    private val observer=SkyColorObserver(oracle)

    @Test fun unavailableIsNullButValidNoSkyIsMeasuredZero() {
        assertNull(SkyColorObserver(null).observe(rgb(),frame,mask()))
        assertNull(observer.observe(rgb(),frame,null))
        val zero=observer.observe(rgb(),frame,mask(values=ByteArray(9){1}))!!
        assertEquals(0,zero.statistics.skyPixCount);assertEquals(9,zero.framePixelCount)
        assertNull(zero.meanSaturation);assertNull(zero.meanEncodedLuma)
        assertEquals(listOf(0,0,0),zero.rowBandSkyPixCounts)
    }
    @Test fun originalAnalyzerRgbIsNotResampledAndNearestMaskIsExplicit() {
        val pixels=rgb(4,2);val before=pixels.argb.copyOf()
        val result=observer.observe(pixels,frame,mask(2,1,byteArrayOf(2,1)))!!
        assertEquals(4,result.statistics.skyPixCount);assertEquals(8,result.framePixelCount)
        assertEquals(4,result.rasterWidth);assertEquals(2,result.semanticRasterWidth)
        assertEquals("ANALYZER_INPUT_UPRIGHT_MAX640",result.rgbRasterSource)
        assertEquals(1.0,result.meanSaturation!!,0.0);assertEquals(.2126,result.meanEncodedLuma!!,1e-12)
        assertArrayEquals(before,pixels.argb)
        assertEquals(result,observer.observe(pixels,frame,mask(2,1,byteArrayOf(2,1))))
    }
    @Test fun allFiveIdentityFieldsBindMaskToRgbWithoutWallClockAgeHeuristic() {
        val changes=listOf(frame.copy(sequence=2),frame.copy(capturedAtEpochMs=1001),frame.copy(cameraTimestampNs=51),
            frame.copy(inputSource=FrameInputSource.ANDROID_RECORDED,mediaPresentationTimeUs=0))
        changes.forEach { assertNull(observer.observe(rgb(),frame,mask(id=it))) }
        val oldRecorded=SkyFrameIdentity(1,1,0,FrameInputSource.ANDROID_RECORDED,900_000)
        assertNotNull(observer.observe(rgb(),oldRecorded,mask(id=oldRecorded)))
        assertNull(observer.observe(rgb(),oldRecorded,mask(id=oldRecorded.copy(mediaPresentationTimeUs=900_001))))
    }
    @Test fun malformedDimensionsLabelsAndFrameMetadataFailClosed() {
        listOf(mask().copy(width=0),mask().copy(classes=ByteArray(8)),mask().copy(classes=ByteArray(9){150.toByte()}),
            mask().copy(modelSha256="unknown")).forEach { assertNull(observer.observe(rgb(),frame,it)) }
        listOf(frame.copy(sequence=-1),frame.copy(capturedAtEpochMs=-1),frame.copy(cameraTimestampNs=-1),
            frame.copy(mediaPresentationTimeUs=2),frame.copy(inputSource=FrameInputSource.ANDROID_RECORDED)).forEach {
            assertNull(observer.observe(rgb(),it,mask(id=it)))
        }
    }
    @Test fun invalidOrUnavailableColorFunctionDoesNotFabricateZero() {
        val n=9;val good=red(n)
        val bad=listOf(good.copy(skyPixCount=8),good.copy(hueSectorCounts=listOf(9)),
            good.copy(saturationSum=Double.NaN),good.copy(encodedLumaSum=10.0),
            good.copy(hueSectorSaturationSums=List(12){0.0}),good.copy(achromaticPixCount=1),
            good.copy(encodedLumaBinCounts=listOf(8,0,0,0)))
        bad.forEach { stats -> assertNull(SkyColorObserver { _,_ -> stats }.observe(rgb(),frame,mask())) }
        assertNull(SkyColorObserver { _,_ -> null }.observe(rgb(),frame,mask()))
        assertNull(SkyColorObserver { _,_ -> error("fixture failure") }.observe(rgb(),frame,mask()))
    }
    @Test fun geometryRotationPreservesGlobalStatsButNotRowBandsOrRegionHash() {
        val first=byteArrayOf(2,2,2,1,1,1,1,1,1)
        val halfTurn=first.reversedArray()
        val a=observer.observe(rgb(),frame,mask(values=first))!!
        val b=observer.observe(rgb(),frame,mask(values=halfTurn))!!
        assertEquals(a.statistics,b.statistics)
        assertEquals(listOf(3,0,0),a.rowBandSkyPixCounts);assertEquals(listOf(0,0,3),b.rowBandSkyPixCounts)
        assertNotEquals(a.skyMaskSha256,b.skyMaskSha256);assertEquals(a.sourceRgbSha256,b.sourceRgbSha256)
        val quarter=byteArrayOf(1,1,2,1,1,2,1,1,2)
        val c=observer.observe(rgb(),frame,mask(values=quarter))!!
        assertEquals(a.statistics,c.statistics);assertEquals(listOf(1,1,1),c.rowBandSkyPixCounts)
    }
    @Test fun returnedStatisticsOwnTheirListsAndContainNoPixelArrays() {
        val counts=mutableListOf(9).apply { repeat(11){add(0)} }
        val stats=red(9).copy(hueSectorCounts=counts)
        val result=SkyColorObserver { _,_ -> stats }.observe(rgb(),frame,mask())!!
        counts[0]=0
        assertEquals(9,result.statistics.hueSectorCounts[0])
        assertEquals(64,result.skyMaskSha256.length);assertEquals(64,result.sourceRgbSha256.length)
    }
}
