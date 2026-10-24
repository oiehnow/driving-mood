package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

/** Procedural selection/ownership regression; not storefront accuracy evaluation. */
class LightPixelEvidenceTest {
    private val core=LightMeasurer()
    private fun frame(draw:(IntArray)->Unit={}):PixelFrame =
        IntArray(200*120) { 0xff202020.toInt() }.also(draw).let { PixelFrame(200,120,it) }
    private fun paint(p:IntArray,l:Int,t:Int,r:Int,b:Int,c:Int) {
        for(y in t until b) for(x in l until r) p[y*200+x]=c
    }
    @Test fun defaultDoesNotAllocateEvidenceAndCaptureDoesNotChangeMeasurements() {
        val f=frame { paint(it,20,30,55,46,0xffffa030.toInt()) }
        val base=core.measure(f,emptyList(),false)
        val captured=core.measure(f,emptyList(),false,true)
        assertNull(base.pixelEvidence)
        assertEquals(base.colors,captured.colors);assertEquals(base.score,captured.score)
        assertEquals(base.pixelQuality,captured.pixelQuality)
        val e=captured.pixelEvidence!!
        assertEquals(200,e.width);assertEquals(120,e.height)
        assertEquals(35*16,e.acceptedAmbientMask.count { it })
        assertEquals(captured.pixelQuality.acceptedPixelCount,e.acceptedAmbientMask.count { it })
        assertTrue(e.acceptedAmbientMask.indices.all { !e.acceptedAmbientMask[it] || e.semanticEligibleBrightMask[it] })
    }
    @Test fun smallValidLuminousPixelsRemainEligibleWithoutBeingAcceptedAmbient() {
        val f=frame { paint(it,20,30,23,33,0xffffa030.toInt()) }
        val e=core.measure(f,emptyList(),false,true).pixelEvidence!!
        assertEquals(0,e.acceptedAmbientMask.count { it });assertEquals(9,e.semanticEligibleBrightMask.count { it })
    }
    @Test fun vehicleTrafficAndReflectionExclusionCannotReturnThroughSemanticEvidence() {
        val f=frame { paint(it,20,30,45,46,0xffffa030.toInt());paint(it,20,50,45,70,0xffffa030.toInt()) }
        for(label in listOf("car","traffic light")) {
            val box=ObjectBox(.09f,.24f,.24f,.61f,label,.9f)
            val e=core.measure(f,listOf(box),false,true).pixelEvidence!!
            assertEquals(0,e.semanticEligibleBrightMask.count { it });assertEquals(0,e.acceptedAmbientMask.count { it })
        }
        // The lower patch extends well beyond the expanded vehicle rectangle; it is
        // excluded specifically by the reflection column below the original box.
        val reflection=core.measure(f,listOf(ObjectBox(.09f,.24f,.24f,.40f,"car",.9f)),false,true).pixelEvidence!!
        assertEquals(0,reflection.semanticEligibleBrightMask.count { it })
        val geometric=frame {
            paint(it,20,0,45,10,0xffffa030.toInt())
            paint(it,20,105,45,119,0xffffa030.toInt())
            paint(it,96,76,104,95,0xffffa030.toInt())
        }
        assertEquals(0,core.measure(geometric,emptyList(),false,true).pixelEvidence!!.semanticEligibleBrightMask.count { it })
    }
    @Test fun pairedLightsGlareAndTooLargeAreHardExcluded() {
        val fixtures=listOf(
            frame { paint(it,70,48,78,54,0xffffffff.toInt());paint(it,100,48,108,54,0xffffffff.toInt()) },
            frame { paint(it,20,30,45,46,0xffffffff.toInt()) },
            frame { paint(it,20,30,160,50,0xffffa030.toInt()) })
        for(f in fixtures) {
            val e=core.measure(f,emptyList(),false,true).pixelEvidence!!
            assertEquals(0,e.semanticEligibleBrightMask.count { it });assertEquals(0,e.acceptedAmbientMask.count { it })
        }
    }
    @Test fun arraysHaveFrameOwnershipAndAreaPartitionIsExact() {
        val f=frame { paint(it,20,30,55,46,0xffffa030.toInt());paint(it,60,30,63,33,0xffffa030.toInt()) }
        val first=core.measure(f,emptyList(),false,true).pixelEvidence!!
        val acceptedBefore=first.acceptedAmbientMask.copyOf();val eligibleBefore=first.semanticEligibleBrightMask.copyOf()
        val next=core.measure(frame(),emptyList(),false,true).pixelEvidence!!
        assertNotSame(first.acceptedAmbientMask,next.acceptedAmbientMask)
        assertArrayEquals(acceptedBefore,first.acceptedAmbientMask);assertArrayEquals(eligibleBefore,first.semanticEligibleBrightMask)
        val bright=first.semanticEligibleBrightMask.count { it }
        val overlap=first.semanticEligibleBrightMask.indices.count { first.semanticEligibleBrightMask[it] && first.acceptedAmbientMask[it] }
        val unique=first.semanticEligibleBrightMask.indices.count { first.semanticEligibleBrightMask[it] && !first.acceptedAmbientMask[it] }
        assertEquals(bright,overlap+unique);assertEquals(9,unique)
        assertEquals(0,next.semanticEligibleBrightMask.count { it })
    }
}
