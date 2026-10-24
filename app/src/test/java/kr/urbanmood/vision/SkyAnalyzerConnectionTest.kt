package kr.urbanmood.vision

import android.graphics.Bitmap
import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic Android bitmap + fake semantic model. No native inference or color accuracy claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class SkyAnalyzerConnectionTest {
    private class Model:SemanticFrameModel {
        override val inputSize=320;override val modelSha256="a".repeat(64)
        var label=2;var fail=false;var closes=0
        override fun infer(prepared:SemanticPreparedFrame):ByteArray { if(fail)error("failure");return ByteArray(320*320){label.toByte()} }
        override fun close(){closes++}
    }
    private class Detector:FrameObjectDetector {
        var closes=0
        override fun detect(borrowedBitmap:Bitmap)=DetectionBatch(emptyList(),false)
        override fun close(){closes++}
    }
    private val redOracle=SkyColorCalculator { _,mask ->
        val n=mask.count { it }
        SkyColorStatistics(n,listOf(n)+List(11){0},listOf(n.toDouble())+List(11){0.0},0,n.toDouble(),n*.2126,listOf(n,0,0,0))
    }
    @Test fun currentFrameToZeroToFailureNeverReusesPreviousSkyAndCloseReleasesModels() {
        val model=Model();val detector=Detector()
        val analyzer=SemanticLightAnalyzer({detector},{model},skyColorCalculator=redOracle)
        val bitmap=Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888).apply { eraseColor(0xffff0000.toInt()) }
        fun frame(seq:Long)=CameraFrame(bitmap,1000+seq,seq*1_000_000,seq,FrameInputSource.ANDROID_RECORDED,seq*1000)
        try {
            val first=analyzer.analyze(frame(1)).sceneObservations!!.semantic!!.skyColor!!
            assertEquals(96,first.statistics.skyPixCount);assertEquals(12,first.rasterWidth);assertEquals(320,first.semanticRasterWidth)
            assertEquals(1000L,first.frame.mediaPresentationTimeUs);assertEquals(1001L,first.frame.capturedAtEpochMs)
            model.label=1
            val zero=analyzer.analyze(frame(2)).sceneObservations!!.semantic!!.skyColor!!
            assertEquals(0,zero.statistics.skyPixCount);assertNotEquals(first.skyMaskSha256,zero.skyMaskSha256)
            model.fail=true
            assertNull(analyzer.analyze(frame(3)).sceneObservations)
            analyzer.close();analyzer.close()
            assertNull(analyzer.analyze(frame(4)).sceneObservations)
            assertEquals(1,model.closes);assertEquals(1,detector.closes)
            assertFalse(bitmap.isRecycled);assertEquals(0xffff0000.toInt(),bitmap.getPixel(0,0))
        } finally { analyzer.close();bitmap.recycle() }
    }
    @Test fun unconnectedKernelLeavesOnlySkyColorNullAndExistingObservationsAvailable() {
        val model=Model();val detector=Detector();val analyzer=SemanticLightAnalyzer({detector},{model})
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        try {
            val value=analyzer.analyze(CameraFrame(bitmap,1000,50,1))
            assertNotNull(value.sceneObservations?.lightFeatures)
            assertEquals(1f,value.sceneObservations!!.semantic!!.skyAreaRatio!!,0f)
            assertNull(value.sceneObservations!!.semantic!!.skyColor)
        } finally { analyzer.close();bitmap.recycle() }
    }
}
