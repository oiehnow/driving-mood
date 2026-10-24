package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test
import kr.urbanmood.contracts.LightGroupKind

class SemanticContrastObserverTest {
    private fun frame(color:Int=0xff181818.toInt())=PixelFrame(80,60,IntArray(80*60){color})
    private fun patch(frame:PixelFrame,color:Int,left:Int=20,top:Int=20,width:Int=3,height:Int=3) {
        for(y in top until top+height)for(x in left until left+width)frame.argb[y*frame.width+x]=color
    }
    @Test fun uniformSemanticBuildingIsMeasuredZeroNotBrightFacade() {
        val result=SemanticContrastObserver().observe(frame(0xffe0c0a0.toInt()),ByteArray(80*60){1},emptyList(),false,"zero",true)
        assertEquals(0,result.features.summary.pixelCount)
        assertTrue(result.features.groups!!.isEmpty())
        assertNull(result.features.quality.heuristicQuality)
        assertEquals(1f,result.features.quality.inspectedAreaRatio)
    }
    @Test fun smallColoredPatchSurvivesWithoutMinimumLegacyBlobSize() {
        val frame=frame();patch(frame,0xff00c8ff.toInt())
        val result=SemanticContrastObserver().observe(frame,ByteArray(80*60){1},emptyList(),false,"tiny",true)
        assertEquals(9,result.features.summary.pixelCount)
        assertTrue(result.features.summary.cyanBlueSaturationSum>0)
        assertTrue(result.features.groups!!.any { it.kind==LightGroupKind.LOCAL_CONTRAST })
        assertEquals(9,result.features.groups.sumOf { it.pixels.pixelCount })
        assertTrue(result.features.quality.flags.contains("SOME_CHANNELS_NEAR_UPPER_LIMIT"))
        assertEquals(0,result.features.summary.categoryCounts[7])
    }
    @Test fun detectorVehicleAndBelowReflectionColumnCannotReenter() {
        val frame=frame();patch(frame,0xff00c8ff.toInt(),30,15);patch(frame,0xffff4411.toInt(),30,40)
        val result=SemanticContrastObserver().observe(frame,ByteArray(80*60){1},listOf(ObjectBox(.32f,.18f,.50f,.40f,"car",.9f)),false,"vehicle",true)
        assertEquals(0,result.features.summary.pixelCount)
        assertTrue(result.vehicleAreaRatio>0)
    }
    @Test fun semanticTrafficDilationAndRoadClassesDoNotEnterSupport() {
        val frame=frame();patch(frame,0xffff5500.toInt(),30,20)
        val sem=ByteArray(80*60){1};for(y in 18..24)for(x in 28..34)sem[y*80+x]=136.toByte()
        assertEquals(0,SemanticContrastObserver().observe(frame,sem,emptyList(),false,"traffic").features.summary.pixelCount)
        assertEquals(0,SemanticContrastObserver().observe(frame,ByteArray(80*60){6},emptyList(),false,"road").features.summary.pixelCount)
    }
    @Test fun allChannelUpperPixelsRemainUnknownAndGroupsPartitionActualMasks() {
        val frame=frame();patch(frame,0xffffffff.toInt());patch(frame,0xffee5500.toInt(),50,30,10,8)
        val result=SemanticContrastObserver().observe(frame,ByteArray(80*60){43},emptyList(),true,"mixed",true)
        val stats=result.features.summary;val groups=result.features.groups!!
        assertTrue(stats.categoryCounts[7]>0);assertEquals(stats.categoryCounts[7],stats.upperChannelCounts[3])
        assertEquals(stats.pixelCount,groups.sumOf { it.pixels.pixelCount })
        assertEquals(stats.pixelCount,result.compactMask!!.count { it }+result.broadMask!!.count { it })
        assertFalse(result.compactMask.indices.any { result.compactMask[it] && result.broadMask[it] })
        assertEquals(result.regions.map { it.id }.toSet(),groups.map { it.regionId }.toSet())
        assertTrue(result.features.quality.flags.contains("DETECTOR_AT_CAPACITY"))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidClassMapFailsRatherThanInventingLabels() {
        SemanticContrastObserver().observe(frame(),ByteArray(80*60){255.toByte()},emptyList(),false,"invalid")
    }
    @Test fun letterboxPreservesOrientationAndCropsExactUnpaddedMembership() {
        val f=PixelFrame(4,2,intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffffff.toInt(),0xff111111.toInt(),0xff222222.toInt(),0xff333333.toInt(),0xff444444.toInt()))
        val prepared=SemanticModelInput.prepare(f)
        assertEquals(320,prepared.pixels.width);assertEquals(160,prepared.pixels.height);assertEquals(80,prepared.layout.top)
        assertEquals(0xffff0000.toInt(),prepared.pixels.argb.first())
        assertEquals(0xff444444.toInt(),prepared.pixels.argb.last())
        assertEquals(114f/255f,prepared.tensor.first())
        assertEquals(1f,prepared.tensor[80*320])
        val square=ByteArray(320*320) { (it/320%150).toByte() }
        val cropped=prepared.layout.cropClassMap(square)
        assertEquals(80,cropped.first().toInt() and 255)
        assertEquals(239%150,cropped.last().toInt() and 255)
        val portrait=SemanticModelInput.prepare(PixelFrame(3,6,IntArray(18){0xff13579b.toInt()}))
        assertEquals(80,portrait.layout.left);assertEquals(0,portrait.layout.top)
    }
}
