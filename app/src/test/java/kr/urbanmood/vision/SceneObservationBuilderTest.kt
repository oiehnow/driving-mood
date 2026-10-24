package kr.urbanmood.vision

import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test

/** Procedural regression fixtures, not real-world model accuracy tests. */
class SceneObservationBuilderTest {
    private fun measure(boxes:List<ObjectBox> = emptyList()):LightMeasurement {
        val pixels=IntArray(200*120) { 0xff202020.toInt() }
        for(y in 30 until 46) for(x in 20 until 55) pixels[y*200+x]=0xffffa030.toInt()
        return LightMeasurer().measure(PixelFrame(200,120,pixels),boxes,false)
    }
    private fun build(boxes:List<ObjectBox>,capacity:Boolean=false,key:String="frame") =
        SceneObservationBuilder.build(boxes,capacity,measure(boxes),1000,key)

    @Test fun personOverlapUsesUnionAndDoesNotMaskTheLights() {
        val a=ObjectBox(.0f,.0f,.5f,.5f,"person",.9f)
        val b=a.copy(left=.25f,right=.75f)
        val result=build(listOf(a,b))
        assertEquals(2,result.persons!!.detectionCount)
        assertEquals(.375f,result.persons!!.areaRatio!!,.000001f)
        assertEquals(measure().colors,measure(listOf(a,b)).colors)
        assertEquals(measure().pixelQuality,measure(listOf(a,b)).pixelQuality)
        assertEquals(0f,measure(listOf(a,b)).vehicleRatio,0f)
        assertTrue(result.regions.filter { it.kind==RegionEvidenceKind.PERSON }.all { it.exclusionReason==null })
    }
    @Test fun invalidOutsideAndBelowThresholdBoxesAreNotCounted() {
        val a=ObjectBox(-.2f,-.2f,.2f,.2f,"person",.5f)
        val result=build(listOf(a,a.copy(confidence=.49f),a.copy(left=Float.NaN),a.copy(left=1.1f,right=1.2f)))
        assertEquals(1,result.persons!!.detectionCount)
        assertEquals(.04f,result.persons!!.areaRatio!!,.000001f)
        assertEquals(0f,result.persons!!.measurementQuality!!,0f)
        assertTrue(result.regions.all { it.left>=0 && it.top>=0 && it.right<=1 && it.bottom<=1 })
    }
    @Test fun successfulZeroAndCapacityAreExplicitAndSemanticIsUnsupported() {
        val zero=build(emptyList())
        assertEquals(0,zero.persons!!.detectionCount)
        assertEquals(0f,zero.persons!!.areaRatio!!,0f)
        assertNull(zero.semantic)
        assertEquals(25,zero.persons!!.detectorCapacity)
        assertEquals(.5f,zero.persons!!.scoreThreshold!!,0f)
        val full=build(emptyList(),true)
        assertTrue(full.persons!!.capacityReached!!)
        assertTrue(full.persons!!.measurementQuality!!<zero.persons!!.measurementQuality!!)
    }
    @Test fun lightsAndVehicleEvidenceHaveDistinctSourcesAndLocalIds() {
        val car=ObjectBox(.7f,.7f,.9f,.9f,"car",.9f)
        val first=build(listOf(car),key="first")
        val second=build(listOf(car),key="second")
        val lights=first.regions.filter { it.kind==RegionEvidenceKind.AMBIENT_LIGHT }
        assertEquals(first.ambientLights!!.candidateCount,lights.size)
        assertEquals(1,lights.size)
        assertTrue(lights.all { it.source==RegionEvidenceSource.PIXEL_HEURISTIC })
        assertTrue(first.regions.filter { it.kind==RegionEvidenceKind.VEHICLE }.all {
            it.source==RegionEvidenceSource.OBJECT_DETECTOR && it.exclusionReason=="조명 계산 제외" })
        assertTrue(first.ambientLights!!.vehiclesExcluded)
        assertTrue(first.regions.map { it.id }.intersect(second.regions.map { it.id }.toSet()).isEmpty())
    }
}
