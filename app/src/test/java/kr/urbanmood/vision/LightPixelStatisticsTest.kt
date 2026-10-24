package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

class LightPixelStatisticsTest {
    @Test fun recordedCategoriesKeepSingleAndDoubleUpperChannelsAndSeparateAllThree() {
        val a = LightPixelStatistics()
        val rgb = intArrayOf(0xffff0000.toInt(),0xffffff00.toInt(),0xff00ff00.toInt(),
            0xff00ffff.toInt(),0xff0000ff.toInt(),0xffff00ff.toInt(),0xff646464.toInt(),0xffffffff.toInt())
        for (i in 0..5) a.add(rgb[i],i*60f,1f)
        a.add(rgb[6],0f,0f);a.add(rgb[7],0f,0f)
        val s=a.snapshot()
        assertEquals(8,s.pixelCount);assertEquals(List(8) { 1 },s.categoryCounts)
        assertEquals(listOf(1,3,3,1),s.upperChannelCounts)
        assertEquals(6f,s.saturationSum,0f)
        assertEquals(2f,s.redYellowSaturationSum,0f);assertEquals(2f,s.cyanBlueSaturationSum,0f)
    }

    @Test fun exactHsvBoundariesAndLowSaturationAreNotApproximatedByCoarseBins() {
        // Exercise exact float boundaries, some of which cannot be represented by 8-bit RGB.
        val a=LightPixelStatistics()
        for (h in listOf(64.99f,65f,164.99f,165f,254.99f,255f,330f,330.01f))
            a.add(0xffc86464.toInt(),h,1f)
        a.add(0xffc86464.toInt(),0f,.199f)
        a.add(0xffc86464.toInt(),0f,.20f)
        val s=a.snapshot()
        assertEquals(2.2f,s.redYellowSaturationSum,.00001f)
        assertEquals(2f,s.cyanBlueSaturationSum,0f)
        assertEquals(8.399f,s.saturationSum,.00001f)
        assertEquals(1,s.categoryCounts[6])
    }

    private fun scene(extra:(IntArray)->Unit={}):PixelFrame {
        val p=IntArray(200*120) { 0xff202020.toInt() }
        for(y in 30 until 46) for(x in 20 until 55) p[y*200+x]=0xffe68c30.toInt()
        extra(p)
        return PixelFrame(200,120,p)
    }
    @Test fun actualAcceptedGroupsPartitionRawSummaryWithoutRetainingPixelSelections() {
        val frame=scene { p -> for(y in 30 until 46) for(x in 65 until 100) p[y*200+x]=0xff0064ff.toInt() }
        val m=LightMeasurer().measure(frame,emptyList(),false)
        val f=m.lightFeatures!!
        assertNull(m.pixelEvidence)
        assertEquals(m.pixelQuality.acceptedPixelCount,f.summary.pixelCount)
        assertEquals(m.candidateRegions.size,f.groups.size)
        assertEquals(2,f.groups.size)
        assertTrue(f.summary.redYellowSaturationSum>0);assertTrue(f.summary.cyanBlueSaturationSum>0)
        for(i in 0..7) assertEquals(f.summary.categoryCounts[i],f.groups.sumOf { it.categoryCounts[i] })
        for(i in 0..3) assertEquals(f.summary.upperChannelCounts[i],f.groups.sumOf { it.upperChannelCounts[i] })
        assertEquals(f.summary.saturationSum,f.groups.sumOf { it.saturationSum.toDouble() }.toFloat(),.0001f)
        assertEquals(f.summary.redYellowSaturationSum,f.groups.sumOf { it.redYellowSaturationSum.toDouble() }.toFloat(),.0001f)
        assertEquals(f.summary.cyanBlueSaturationSum,f.groups.sumOf { it.cyanBlueSaturationSum.toDouble() }.toFloat(),.0001f)
    }
    @Test fun unrelatedRejectedGlareChangesQualityButNotAcceptedPixelFeatures() {
        val core=LightMeasurer()
        val base=core.measure(scene(),emptyList(),false)
        val withGlare=core.measure(scene { p ->
            for(y in 20 until 75) for(x in 100 until 190) p[y*200+x]=0xffffffff.toInt()
        },emptyList(),false)
        assertEquals(base.lightFeatures,withGlare.lightFeatures)
        assertTrue(withGlare.pixelQuality.lightConfidence<base.pixelQuality.lightConfidence)
    }
    @Test fun vehicleMaskedPixelsAndSuccessfulZeroDoNotBecomeUnknownPositiveSamples() {
        val m=LightMeasurer().measure(scene(),listOf(ObjectBox(.09f,.24f,.29f,.41f,"car",.9f)),false)
        val f=m.lightFeatures!!
        assertEquals(0,f.summary.pixelCount);assertEquals(List(8){0},f.summary.categoryCounts)
        assertEquals(List(4){0},f.summary.upperChannelCounts);assertTrue(f.groups.isEmpty())
        assertEquals(0f,f.summary.saturationSum,0f)
    }
    @Test fun returnedStatisticsAreDetachedFromAccumulatorAndInputFrames() {
        val a=LightPixelStatistics();a.add(0xffff0000.toInt(),0f,1f)
        val first=a.snapshot();a.add(0xff0000ff.toInt(),240f,1f)
        assertEquals(1,first.pixelCount);assertEquals(0,first.categoryCounts[4])
        val input=scene();val m=LightMeasurer().measure(input,emptyList(),false)
        val recorded=m.lightFeatures!!
        input.argb.fill(0)
        LightMeasurer().measure(input,emptyList(),false)
        assertEquals(560,recorded.summary.pixelCount);assertEquals(560,recorded.summary.categoryCounts.sum())
    }
}
