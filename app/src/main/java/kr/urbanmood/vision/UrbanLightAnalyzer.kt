package kr.urbanmood.vision

import android.content.Context
import android.graphics.Bitmap
import kr.urbanmood.contracts.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Foreground prototype: real on-device object inference + measured light candidates.
 * Construction does no model work. A calls analyze/close on the same serial worker.
 * LOW_CONFIDENCE is intentional: this model cannot identify storefronts/buildings.
 */
class UrbanLightAnalyzer internal constructor(
    private val factory: () -> FrameObjectDetector,
    private val nanoTime: () -> Long = System::nanoTime,
) : VisionAnalyzer {
    constructor(context: Context) : this({ MediaPipeVehicleDetector(context.applicationContext) })

    private var detector: FrameObjectDetector? = null
    private val measurer=LightMeasurer()
    private val temporal=TemporalLightFilter()
    private var closed=false
    private var lastSequence=-1L
    private var lastTimestamp=-1L
    private var lastInputSource: FrameInputSource? = null
    private var nextRetryNs=0L
    // Used only to choose the next spatial sampling size, not to queue or retain frames.
    private var pixelLongEdge=320
    private var slowRuns=0

    override fun analyze(frame: CameraFrame): VisionResult {
        val started=nanoTime()
        fun failed(status:AnalysisStatus, reason:String):VisionResult {
            temporal.reset()
            return VisionResult(frame.capturedAtEpochMs,status,reasons=listOf(reason),
                processingTimeMs=max(0L,(nanoTime()-started)/1_000_000))
        }
        if(closed) return failed(AnalysisStatus.FAILED,"분석이 종료되었습니다. 다시 시작해 주세요.")
        val bitmap=frame.bitmap
        if(bitmap.isRecycled || bitmap.width<1 || bitmap.height<1 || bitmap.width>640 || bitmap.height>640 ||
            bitmap.config!=Bitmap.Config.ARGB_8888) {
            return failed(AnalysisStatus.FAILED,"카메라 영상을 읽을 수 없습니다. 분석을 다시 시작해 주세요.")
        }
        if (frame.inputSource == FrameInputSource.ANDROID_RECORDED &&
            (frame.mediaPresentationTimeUs == null || frame.cameraTimestampNs < 0)) {
            return failed(AnalysisStatus.FAILED,"녹화 영상의 프레임 시각을 확인하지 못했습니다.")
        }
        val newSession=frame.inputSource!=lastInputSource || frame.sequence<=lastSequence || frame.cameraTimestampNs<=lastTimestamp ||
            (lastTimestamp>=0 && frame.cameraTimestampNs-lastTimestamp>1_500_000_000L)
        if(newSession) { temporal.reset(); nextRetryNs=0L }
        lastSequence=frame.sequence; lastTimestamp=frame.cameraTimestampNs
        lastInputSource=frame.inputSource
        if(detector==null) {
            if(started<nextRetryNs) return failed(AnalysisStatus.MODEL_NOT_READY,
                "영상 분석을 준비하지 못했습니다. 잠시 후 다시 시도합니다.")
            try {
                detector=factory()
            } catch(e:Exception) {
                nextRetryNs=started+5_000_000_000L
                return failed(AnalysisStatus.MODEL_NOT_READY,"영상 분석을 준비하지 못했습니다. 잠시 후 다시 시도합니다.")
            } catch(e:LinkageError) {
                nextRetryNs=started+5_000_000_000L
                return failed(AnalysisStatus.MODEL_NOT_READY,"이 기기에서 영상 분석을 시작하지 못했습니다.")
            }
        }
        try {
            val detections=checkNotNull(detector).detect(bitmap)
            val scale=minOf(1f,pixelLongEdge.toFloat()/max(bitmap.width,bitmap.height))
            val width=max(1,(bitmap.width*scale).roundToInt())
            val height=max(1,(bitmap.height*scale).roundToInt())
            val sampled=if(width==bitmap.width && height==bitmap.height) bitmap
                else Bitmap.createScaledBitmap(bitmap,width,height,true)
            val pixels=IntArray(width*height)
            try { sampled.getPixels(pixels,0,width,0,0,width,height) }
            finally { if(sampled !== bitmap) sampled.recycle() }
            val measurement=temporal.apply(measurer.measure(PixelFrame(width,height,pixels),detections.boxes,detections.atCapacity),
                frame.cameraTimestampNs,frame.sequence)
            val elapsed=max(0L,(nanoTime()-started)/1_000_000)
            slowRuns=if(elapsed>220) slowRuns+1 else 0
            if(slowRuns>=3 && pixelLongEdge>240) { pixelLongEdge=240; temporal.reset(); slowRuns=0 }
            val c=measurement.colors
            val observations=SceneObservationBuilder.build(detections.boxes,detections.atCapacity,measurement,
                frame.capturedAtEpochMs,"${frame.cameraTimestampNs}:${frame.sequence}",frame.inputSource,frame.mediaPresentationTimeUs)
            return VisionResult(frame.capturedAtEpochMs,AnalysisStatus.LOW_CONFIDENCE,
                urbanLightScore=measurement.score,
                colors=ColorFeatures(c.argb,c.hue,c.saturation,c.brightness,c.luminousArea),
                vehicleExcludedRatio=measurement.vehicleRatio,confidence=measurement.confidence,
                method=AnalysisMethod.HYBRID,
                reasons=measurement.reasons+measurement.pixelQuality.reasons,
                debugRegions=measurement.regions.map { DebugRegion(it.left,it.top,it.right,it.bottom,it.label,it.excluded) },
                processingTimeMs=elapsed,
                colorMeasurementConfidence=measurement.pixelQuality.colorConfidence,
                lightMeasurementConfidence=measurement.pixelQuality.lightConfidence,
                // COCO vehicle detections do not evaluate storefront/building semantics.
                semanticConfidence=null,
                vehicleMaskApplied=true,
                sceneObservations=observations)
        } catch(e:Exception) {
            invalidateDetector()
            nextRetryNs=started+5_000_000_000L
            return failed(AnalysisStatus.FAILED,"현재 영상을 분석하지 못했습니다. 이전 결과는 사용하지 않습니다.")
        } catch(e:LinkageError) {
            invalidateDetector()
            nextRetryNs=started+5_000_000_000L
            return failed(AnalysisStatus.FAILED,"이 기기에서 영상 분석을 실행하지 못했습니다. 이전 결과는 사용하지 않습니다.")
        }
    }
    private fun invalidateDetector() {
        val old=detector; detector=null
        try { old?.close() } catch(_:Exception) { /* Preserve original analysis failure. */ }
        catch(_:LinkageError) { /* A missing native library must not escape failure handling. */ }
    }
    override fun close() {
        if(closed) return
        closed=true; temporal.reset(); invalidateDetector()
    }
}
