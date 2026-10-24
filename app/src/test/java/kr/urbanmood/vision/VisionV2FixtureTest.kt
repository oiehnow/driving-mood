package kr.urbanmood.vision

import android.graphics.Bitmap
import kr.urbanmood.contracts.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the actual B analyzer over authored pixels with explicit fake detector output.
 * Optional TSV export is a real analyzer result fixture, not real-camera/native evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class VisionV2FixtureTest {
    private fun result(name:String):VisionResult {
        val bitmap=Bitmap.createBitmap(200,120,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if(name=="dark") 0xff000000.toInt() else 0xff202020.toInt())
        if(name!="empty") for(y in 30 until 46) for(x in 20 until 55)
            bitmap.setPixel(x,y,if(name=="clipped") 0xffff9030.toInt() else 0xffe09030.toInt())
        val analyzer=UrbanLightAnalyzer({
            if(name=="missing_model") throw IllegalStateException("fixture")
            object:FrameObjectDetector {
                override fun detect(borrowedBitmap:Bitmap):DetectionBatch {
                    if(name=="inference_failed") throw IllegalStateException("fixture")
                    return DetectionBatch(if(name=="masked") listOf(ObjectBox(.09f,.23f,.29f,.40f,"car",.9f))
                        else emptyList(),name=="capacity")
                }
                override fun close()=Unit
            }
        },{1_000_000_000L})
        return try { analyzer.analyze(CameraFrame(bitmap,1_000L,250_000_000L,1)) }
        finally { assertFalse(bitmap.isRecycled);analyzer.close();bitmap.recycle() }
    }
    @Test fun realAnalyzerOutputsForConsumerGates() {
        val names=listOf("usable_patch","empty","masked","dark","clipped","capacity","missing_model","inference_failed")
        val rows=mutableListOf("fixture\tstatus\tcolorQuality\tlightQuality\tsemanticConfidence\tvehicleMaskApplied\tlegacyConfidence\tlightScore\thue\tsaturation\tbrightness\tluminousArea\tvehicleExcludedRatio\tusableColor\tusableLight")
        for(name in names) {
            val r=result(name)
            assertNull(r.semanticConfidence)
            assertEquals(name=="usable_patch",VisionMeasurementPolicy.usableColor(r,1_000L))
            assertEquals(name=="usable_patch",VisionMeasurementPolicy.usableLight(r,1_000L))
            assertFalse(VisionMeasurementPolicy.usableColor(r,11_001L))
            assertFalse(VisionMeasurementPolicy.usableLight(r,11_001L))
            if(name in setOf("missing_model","inference_failed")) {
                assertFalse(r.vehicleMaskApplied)
                assertNull(r.colorMeasurementConfidence);assertNull(r.lightMeasurementConfidence)
                assertNull(r.colors);assertNull(r.urbanLightScore)
            } else {
                assertEquals(AnalysisStatus.LOW_CONFIDENCE,r.status)
                assertTrue(r.vehicleMaskApplied)
                assertTrue(r.confidence!!<=.55f)
                if(name=="usable_patch") {
                    assertTrue(r.colorMeasurementConfidence!!>=.6f);assertTrue(r.lightMeasurementConfidence!!>=.6f)
                    assertTrue(r.colors!!.hueDegrees!! in 30f..40f)
                } else {
                    assertTrue(r.colorMeasurementConfidence!!<.6f);assertTrue(r.lightMeasurementConfidence!!<.6f)
                }
            }
            rows.add(listOf(name,r.status,r.colorMeasurementConfidence,r.lightMeasurementConfidence,r.semanticConfidence,
                r.vehicleMaskApplied,r.confidence,r.urbanLightScore,r.colors?.hueDegrees,r.colors?.saturation,
                r.colors?.brightness,r.colors?.luminousAreaRatio,r.vehicleExcludedRatio,
                VisionMeasurementPolicy.usableColor(r,1_000L),VisionMeasurementPolicy.usableLight(r,1_000L)).joinToString("\t"))
        }
        System.getProperty("visionV2FixtureReport")?.let { path ->
            File(path).apply { parentFile?.mkdirs();writeText(rows.joinToString("\n")+"\n") }
        }
    }
}
