package kr.urbanmood.vision

import android.graphics.Bitmap
import kr.urbanmood.contracts.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric + fake object detections only. Does not claim native MediaPipe inference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], manifest=Config.NONE)
class UrbanLightAnalyzerTest {
    private class FakeDetector:FrameObjectDetector {
        var calls=0; var closes=0; var fail=false
        override fun detect(borrowedBitmap:Bitmap):DetectionBatch {
            check(!borrowedBitmap.isRecycled); calls++
            if(fail) throw IllegalStateException("test inference failure")
            return DetectionBatch(emptyList(),false)
        }
        override fun close() { closes++ }
    }
    private fun frame(sequence:Long=1, width:Int=640, height:Int=480):CameraFrame {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff202020.toInt())
        return CameraFrame(bitmap,1000+sequence,sequence*250_000_000L,sequence)
    }
    @Test fun constructorIsLazyAndOriginalSurvivesAnalysisAndClose() {
        val detector=FakeDetector(); var constructions=0
        val analyzer=UrbanLightAnalyzer({ constructions++; detector })
        assertEquals(0,constructions)
        val input=frame()
        try {
            val result=analyzer.analyze(input)
            assertEquals(1,constructions); assertEquals(AnalysisStatus.LOW_CONFIDENCE,result.status)
            assertEquals(AnalysisMethod.HYBRID,result.method); assertEquals(input.capturedAtEpochMs,result.frameTimeEpochMs)
            assertFalse(input.bitmap.isRecycled); assertEquals(0xff202020.toInt(),input.bitmap.getPixel(0,0))
            analyzer.close(); analyzer.close()
            assertFalse(input.bitmap.isRecycled); assertEquals(1,detector.closes)
        } finally { input.bitmap.recycle(); analyzer.close() }
    }
    @Test fun missingModelHasNullMeasurementsAndRetriesWithBackoff() {
        var now=1_000_000_000L; var attempts=0
        val analyzer=UrbanLightAnalyzer({ attempts++; throw IllegalStateException("missing") }, { now })
        val first=frame(); val second=frame(2)
        try {
            val result=analyzer.analyze(first)
            assertEquals(AnalysisStatus.MODEL_NOT_READY,result.status)
            assertNull(result.urbanLightScore); assertNull(result.colors); assertNull(result.vehicleExcludedRatio)
            analyzer.analyze(second); assertEquals(1,attempts)
            now+=6_000_000_000L
            analyzer.analyze(second.copy(sequence=3,cameraTimestampNs=750_000_000L)); assertEquals(2,attempts)
            assertFalse(first.bitmap.isRecycled); assertFalse(second.bitmap.isRecycled)
        } finally { first.bitmap.recycle(); second.bitmap.recycle(); analyzer.close() }
    }
    @Test fun inferenceFailureDiscardsValuesAndClosesDetector() {
        val detector=FakeDetector(); val analyzer=UrbanLightAnalyzer({ detector })
        val first=frame(); val second=frame(2)
        try {
            assertNotNull(analyzer.analyze(first).urbanLightScore)
            detector.fail=true
            val failed=analyzer.analyze(second)
            assertEquals(AnalysisStatus.FAILED,failed.status); assertNull(failed.urbanLightScore)
            assertNull(failed.colors); assertTrue(failed.debugRegions.isEmpty()); assertEquals(1,detector.closes)
            assertNull(failed.colorMeasurementConfidence); assertNull(failed.lightMeasurementConfidence)
            assertNull(failed.semanticConfidence); assertFalse(failed.vehicleMaskApplied)
            assertFalse(second.bitmap.isRecycled)
        } finally { first.bitmap.recycle(); second.bitmap.recycle(); analyzer.close() }
    }
    @Test fun missingNativeLibraryIsReportedAndDoesNotEscape() {
        val analyzer=UrbanLightAnalyzer({ throw UnsatisfiedLinkError("fake ABI") })
        val input=frame()
        try { assertEquals(AnalysisStatus.MODEL_NOT_READY,analyzer.analyze(input).status) }
        finally { input.bitmap.recycle(); analyzer.close() }
    }
    @Test fun closedAndRecycledFramesDoNotReachInference() {
        val detector=FakeDetector(); val analyzer=UrbanLightAnalyzer({ detector })
        val input=frame(); input.bitmap.recycle()
        assertEquals(AnalysisStatus.FAILED,analyzer.analyze(input).status)
        assertEquals(0,detector.calls)
        analyzer.close()
        val valid=frame()
        try { assertEquals(AnalysisStatus.FAILED,analyzer.analyze(valid).status); assertEquals(0,detector.calls) }
        finally { valid.bitmap.recycle() }
    }
    @Test fun tinyAndPortraitFramesRespectNormalizedContract() {
        val analyzer=UrbanLightAnalyzer({ FakeDetector() })
        for((w,h) in listOf(1 to 1,240 to 640)) {
            val input=frame(width=w,height=h)
            try {
                val result=analyzer.analyze(input)
                assertEquals(AnalysisStatus.LOW_CONFIDENCE,result.status)
                assertEquals(0f,result.colors!!.luminousAreaRatio!!,0f)
                assertTrue(result.debugRegions.all { it.left in 0f..1f && it.right in 0f..1f && it.bottom in 0f..1f })
            } finally { input.bitmap.recycle() }
        }
        analyzer.close()
    }
}
