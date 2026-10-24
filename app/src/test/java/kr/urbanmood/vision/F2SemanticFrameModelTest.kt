package kr.urbanmood.vision

import org.junit.Assert.*
import org.junit.Test

class F2SemanticFrameModelTest {
    private class Model(override val inputSize:Int,val label:Int):SemanticFrameModel {
        override val modelSha256=label.toString(16).padStart(64,'0')
        var calls=0;var closes=0;var fail=false;var last:SemanticPreparedFrame?=null
        override fun infer(prepared:SemanticPreparedFrame):ByteArray {
            calls++;last=prepared;if(fail)error("test failure")
            return ByteArray(inputSize*inputSize){label.toByte()}
        }
        override fun close(){closes++}
    }
    private fun frame()=PixelFrame(640,360,IntArray(640*360){ i ->
        0xff000000.toInt() or ((i%256) shl 16) or ((i/640%256) shl 8)
    })

    @Test fun sourceIsPreparedSeparatelyAndSharedFusedRasterUsesFullResolution() {
        val base=Model(320,1);val sky=Model(640,2);val detail=Model(320,43)
        val fusion=F2SemanticFrameModel(base,{sky},{detail})
        val source=frame();val result=fusion.inferSource(source)
        assertSame(source,result.pixels)
        assertArrayEquals(source.argb,sky.last!!.pixels.argb)
        assertEquals(320,base.last!!.pixels.width)
        assertEquals(640,sky.last!!.pixels.width)
        assertEquals(320,detail.last!!.pixels.width)
        assertTrue(result.classes.all { it.toInt()==2 })
        assertTrue(result.sourceSha256.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(base.modelSha256,result.sourceSha256)
        assertNull(result.fallbackReason)
        fusion.close();fusion.close()
        assertEquals(1,base.closes);assertEquals(1,sky.closes);assertEquals(1,detail.closes)
    }
    @Test fun protectionAndPositiveOverrideOrderMatchesFrozenDevelopmentRules() {
        val protected=listOf(6,11,12,20,80,83,87,93,102,116,127,136)
        val base=(protected+listOf(0,0,0,2)).map{it.toByte()}.toByteArray()
        val sky=ByteArray(base.size){2};sky[base.lastIndex]=0;sky[base.lastIndex-1]=0
        val detail=ByteArray(base.size){43}
        val result=F2SemanticFusion.merge(base,sky,detail)
        protected.indices.forEach { assertEquals(base[it],result[it]) }
        assertEquals(2,result[12].toInt());assertEquals(2,result[13].toInt())
        assertEquals(43,result[14].toInt());assertEquals(43,result[15].toInt())
        assertArrayEquals(byteArrayOf(2),F2SemanticFusion.merge(byteArrayOf(2),byteArrayOf(0),byteArrayOf(0)))
    }
    @Test fun optionalFailureUsesFreshBaselineAndDoesNotRetryOrReusePreviousMask() {
        val base=Model(320,1);val sky=Model(640,2);val detail=Model(320,43)
        val fusion=F2SemanticFrameModel(base,{sky},{detail})
        assertNull(fusion.inferSource(frame()).fallbackReason)
        detail.fail=true
        val fallback=fusion.inferSource(frame())
        assertNotNull(fallback.fallbackReason);assertEquals(320,fallback.pixels.width)
        assertTrue(fallback.classes.all { it.toInt()==1 })
        assertEquals(base.modelSha256,fallback.sourceSha256)
        fusion.inferSource(frame())
        assertEquals(3,base.calls);assertEquals(2,detail.calls);assertEquals(2,sky.calls)
        assertEquals(1,sky.closes);assertEquals(1,detail.closes)
        fusion.close();assertEquals(1,base.closes)
    }
    @Test fun failedOptionalInitializationClosesCreatedSessionAndPreservesBaseline() {
        val base=Model(320,1);val sky=Model(640,2)
        val fusion=F2SemanticFrameModel(base,{sky},{error("missing asset")})
        assertNotNull(fusion.inferSource(frame()).fallbackReason)
        assertEquals(1,sky.closes);assertEquals(0,base.closes)
        fusion.close()
    }
    @Test fun cancellationDoesNotStartAnyInference() {
        val base=Model(320,1);val fusion=F2SemanticFrameModel(base,{Model(640,2)},{Model(320,43)})
        Thread.currentThread().interrupt()
        try { fusion.inferSource(frame());fail("cancel must propagate") }
        catch(_:InterruptedException) { assertEquals(0,base.calls) }
        finally { Thread.interrupted();fusion.close() }
    }
    @Test fun baselineFailureDoesNotProduceFakeFallback() {
        val base=Model(320,1).apply{fail=true};val fusion=F2SemanticFrameModel(base,{Model(640,2)},{Model(320,43)})
        try { fusion.inferSource(frame());fail("baseline must fail") } catch(_:IllegalStateException) {} finally { fusion.close() }
    }
    @Test(expected=IllegalArgumentException::class)
    fun invalidAdeIdsCannotEnterFusion(){F2SemanticFusion.merge(byteArrayOf(-1),byteArrayOf(2),byteArrayOf(1))}
    @Test(expected=IllegalArgumentException::class)
    fun differentGridsCannotEnterFusion(){F2SemanticFusion.merge(byteArrayOf(1,2),byteArrayOf(2),byteArrayOf(1))}
}
