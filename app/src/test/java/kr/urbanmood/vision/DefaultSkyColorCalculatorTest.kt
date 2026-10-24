package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

class DefaultSkyColorCalculatorTest {
    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
    private fun measure(vararg colors: Int) = DefaultSkyColorCalculator.calculate(
        PixelFrame(colors.size, 1, colors), BooleanArray(colors.size) { true })!!

    @Test fun exactGraysAreAchromaticIncludingBlackAndWhite() {
        val s = measure(0, rgb(64,64,64), rgb(128,128,128), rgb(192,192,192), 0xffffff)
        assertEquals(5, s.skyPixCount)
        assertEquals(5, s.achromaticPixCount)
        assertEquals(List(12) { 0 }, s.hueSectorCounts)
        assertEquals(List(12) { 0.0 }, s.hueSectorSaturationSums)
        assertEquals(0.0, s.saturationSum, 0.0)
        assertEquals(listOf(1,1,1,2), s.encodedLumaBinCounts)
        assertEquals((64+128+192+255)/255.0, s.encodedLumaSum, 1e-12)
    }

    @Test fun faintAndDarkChromaticPixelsStayInHueHistogram() {
        val s = measure(rgb(255,254,254), rgb(1,0,0), rgb(0,1,0), rgb(0,0,1))
        assertEquals(0, s.achromaticPixCount)
        assertEquals(2, s.hueSectorCounts[0])
        assertEquals(1, s.hueSectorCounts[4])
        assertEquals(1, s.hueSectorCounts[8])
        assertEquals(3.0 + 1.0/255, s.saturationSum, 1e-12)
        assertEquals(listOf(3,0,0,1), s.encodedLumaBinCounts)
    }

    // Walk the six RGB cube edges at value 240: each step advances H by .25 degrees.
    private fun wheel(step: Int): Int {
        val p = Math.floorMod(step, 1440)
        val t = p % 240
        return when (p / 240) {
            0 -> rgb(240,t,0)
            1 -> rgb(240-t,240,0)
            2 -> rgb(0,240,t)
            3 -> rgb(0,240-t,240)
            4 -> rgb(t,0,240)
            else -> rgb(240,0,240-t)
        }
    }

    @Test fun allTwelveHueBoundariesAndBothNeighborsIncludeRedWrap() {
        for (bin in 0..11) for (offset in -1..1) {
            val s = measure(wheel(bin*120 + offset))
            val expected = if (offset < 0) (bin+11)%12 else bin
            assertEquals("bin=$bin offset=$offset", List(12) { if (it==expected) 1 else 0 },
                s.hueSectorCounts)
            assertEquals(1.0, s.hueSectorSaturationSums[expected], 0.0)
        }
    }

    @Test fun lumaIsEncodedWeightedRgbNotHsvValue() {
        val s = measure(rgb(255,0,0), rgb(0,255,0), rgb(0,0,255))
        assertEquals(listOf(2,0,1,0), s.encodedLumaBinCounts)
        assertEquals(1.0, s.encodedLumaSum, 1e-12)
    }

    @Test fun lumaBinsKeepSamplesOnEachSideOfQuarterBoundaries() {
        for (boundary in listOf(64,128,192)) {
            val below = measure(rgb(boundary-1,boundary-1,boundary-1))
            val above = measure(rgb(boundary,boundary,boundary))
            assertEquals(1, below.encodedLumaBinCounts[boundary/64-1])
            assertEquals(1, above.encodedLumaBinCounts[boundary/64])
        }
    }

    @Test fun exactQuarterLumaBoundariesBelongToUpperBin() {
        // Integer RGB solutions of 2126R+7152G+722B = 2550000 * quarter.
        val boundaries = listOf(Triple(8,70,166),Triple(13,163,113),Triple(4,243,230))
        for ((index, c) in boundaries.withIndex()) {
            val (r,g,b) = c
            for (offset in -1..1) {
                val s = measure(rgb(r,g,b+offset))
                assertEquals(1, s.encodedLumaBinCounts[if (offset<0) index else index+1])
                if (offset==0) assertEquals((index+1)*.25,s.encodedLumaSum,0.0)
            }
        }
    }

    @Test fun validNoSkyReturnsZeroObservation() {
        val s = DefaultSkyColorCalculator.calculate(PixelFrame(2,1,intArrayOf(0xff0000,0xffffff)),
            booleanArrayOf(false,false))!!
        assertEquals(0, s.skyPixCount)
        assertEquals(0, s.achromaticPixCount)
        assertEquals(0, s.hueSectorCounts.sum())
        assertEquals(0, s.encodedLumaBinCounts.sum())
        assertEquals(0.0, s.saturationSum, 0.0)
        assertEquals(0.0, s.encodedLumaSum, 0.0)
    }

    @Test fun mismatchedMasksAreUnavailableNotZero() {
        val frame = PixelFrame(1,1,intArrayOf(0xffffff))
        assertNull(DefaultSkyColorCalculator.calculate(frame, booleanArrayOf()))
        assertNull(DefaultSkyColorCalculator.calculate(frame, booleanArrayOf(true,false)))
    }

    @Test fun onlySelectedPixelsContributeAndAlphaIsIgnored() {
        val frame = PixelFrame(2,2,intArrayOf(0xffff0000.toInt(),0xffffff,0x00ff0000,0x00ff00))
        val s = DefaultSkyColorCalculator.calculate(frame,booleanArrayOf(true,false,true,false))!!
        assertEquals(2, s.skyPixCount)
        assertEquals(2, s.hueSectorCounts[0])
        assertEquals(2.0, s.saturationSum, 0.0)
        assertEquals(2*.2126, s.encodedLumaSum, 1e-12)
    }

    @Test fun borrowedInputsRemainUnchangedAndResultsDoNotRetainThem() {
        val colors = intArrayOf(0xff0000,0xffffff)
        val mask = booleanArrayOf(true,false)
        val s = DefaultSkyColorCalculator.calculate(PixelFrame(2,1,colors),mask)!!
        assertArrayEquals(intArrayOf(0xff0000,0xffffff),colors)
        assertArrayEquals(booleanArrayOf(true,false),mask)
        colors.fill(0); mask.fill(false)
        measure(0x00ff00)
        assertEquals(1, s.skyPixCount)
        assertEquals(1, s.hueSectorCounts[0])
        assertEquals(1.0, s.saturationSum, 0.0)
    }

    @Test fun maximumFramePreservesCountAndSaturationTotals() {
        val colors = IntArray(640*640) { wheel(it % 1440) }
        val s = DefaultSkyColorCalculator.calculate(PixelFrame(640,640,colors),
            BooleanArray(colors.size) { true })!!
        assertEquals(colors.size, s.skyPixCount)
        assertEquals(s.skyPixCount, s.hueSectorCounts.sum()+s.achromaticPixCount)
        assertEquals(s.skyPixCount, s.encodedLumaBinCounts.sum())
        assertEquals(s.saturationSum, s.hueSectorSaturationSums.sum(), 1e-10)
        assertEquals(colors.size.toDouble(), s.saturationSum, 0.0)
        assertTrue(s.encodedLumaSum in 0.0..s.skyPixCount.toDouble())
    }

    @Test fun invalidRasterCannotEnterCalculatorThroughPixelFrameContract() {
        for ((w,h,p) in listOf(Triple(0,1,intArrayOf()), Triple(641,1,IntArray(641)),
            Triple(Int.MAX_VALUE,Int.MAX_VALUE,intArrayOf()), Triple(2,2,IntArray(3)))) {
            assertThrows(IllegalArgumentException::class.java) { PixelFrame(w,h,p) }
        }
    }
}
