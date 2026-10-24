package kr.urbanmood.vision

import android.content.Context
import android.graphics.Bitmap
import kr.urbanmood.contracts.*
import kotlin.math.max

/** Semantic RGB observer with explicit unevaluated measurement quality.
 * One synchronous worker, no frame queue or stored Bitmap. Unknown quality stays null.
 */
internal class SemanticLightAnalyzer(
    private val detectorFactory: () -> FrameObjectDetector,
    private val semanticFactory: () -> SemanticFrameModel,
    private val nanoTime: () -> Long = System::nanoTime,
    private val skyColorCalculator: SkyColorCalculator? = null,
    private val objectModelDescription:String="EfficientDet-Lite0 ${MediaPipeVehicleDetector.MODEL_SHA256}",
) : VisionAnalyzer {
    constructor(context: Context, semanticFactory: () -> SemanticFrameModel) :
        this({ MediaPipeVehicleDetector(context.applicationContext) }, semanticFactory, skyColorCalculator = DefaultSkyColorCalculator)

    private var detector: FrameObjectDetector? = null
    private var model: SemanticFrameModel? = null
    private val observer=SemanticContrastObserver()
    private val skyObserver=SkyColorObserver(skyColorCalculator)
    private var closed=false
    private var nextRetry=0L

    override fun analyze(frame: CameraFrame): VisionResult {
        val start=nanoTime()
        fun fail(status:AnalysisStatus, reason:String) = VisionResult(frame.capturedAtEpochMs,status,
            reasons=listOf(reason),processingTimeMs=max(0L,(nanoTime()-start)/1_000_000))
        if(closed)return fail(AnalysisStatus.FAILED,"분석이 종료되었습니다.")
        val bitmap=frame.bitmap
        if(bitmap.isRecycled || bitmap.width !in 1..640 || bitmap.height !in 1..640 || bitmap.config!=Bitmap.Config.ARGB_8888)
            return fail(AnalysisStatus.FAILED,"영상 프레임을 읽을 수 없습니다.")
        if(frame.inputSource==FrameInputSource.ANDROID_RECORDED && (frame.mediaPresentationTimeUs==null || frame.cameraTimestampNs<0))
            return fail(AnalysisStatus.FAILED,"녹화 프레임 시각이 없습니다.")
        if(start<nextRetry)return fail(AnalysisStatus.MODEL_NOT_READY,"의미분할 모델을 준비하지 못했습니다.")
        if(model==null || detector==null) {
            try { model=semanticFactory();detector=detectorFactory() }
            catch(error:Exception) { android.util.Log.e("SemanticLight", "Model initialization failed", error);release();nextRetry=start+5_000_000_000;return fail(AnalysisStatus.MODEL_NOT_READY,"의미분할 모델 또는 설정이 없습니다.") }
            catch(error:LinkageError) { android.util.Log.e("SemanticLight", "Runtime initialization failed", error);release();nextRetry=start+5_000_000_000;return fail(AnalysisStatus.MODEL_NOT_READY,"의미분할 런타임을 사용할 수 없습니다.") }
        }
        return try {
            val sourcePixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(sourcePixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val activeModel=checkNotNull(model)
            val source=PixelFrame(bitmap.width,bitmap.height,sourcePixels)
            val raster=if(activeModel is SourceSemanticFrameModel)activeModel.inferSource(source) else {
                val prepared=SemanticModelInput.prepare(source,activeModel.inputSize)
                SemanticObservationRaster(prepared.pixels,prepared.layout.cropClassMap(activeModel.infer(prepared)),
                    activeModel.modelSha256,"YOLO26n-sem-ADE20K ${activeModel.modelSha256}; input ${activeModel.inputSize}")
            }
            val pixels=raster.pixels;val classes=raster.classes
            val detections=checkNotNull(detector).detect(bitmap)
            val key="${frame.cameraTimestampNs}:${frame.sequence}"
            val observeStart=nanoTime()
            val measured=observer.observe(pixels,classes,detections.boxes,detections.atCapacity,key)
            val observeMs=(nanoTime()-observeStart).coerceAtLeast(0)/1_000_000
            val skyFrame=SkyFrameIdentity(frame.sequence,frame.capturedAtEpochMs,frame.cameraTimestampNs,
                frame.inputSource,frame.mediaPresentationTimeUs)
            val sky=skyObserver.observe(source,skyFrame,
                SemanticSkyMask(pixels.width,pixels.height,classes,skyFrame,raster.sourceSha256))
            // Reuse only the established person/vehicle observation builder. Its legacy light
            // features/score/regions do not enter the semantic light contribution a second time.
            val legacyStart=nanoTime()
            val baseMeasurement=LightMeasurer().measure(pixels,detections.boxes,detections.atCapacity)
            val legacyMs=(nanoTime()-legacyStart).coerceAtLeast(0)/1_000_000
            val base=SceneObservationBuilder.build(detections.boxes,detections.atCapacity,baseMeasurement,
                frame.capturedAtEpochMs,key,frame.inputSource,frame.mediaPresentationTimeUs)
            val area=measured.features.summary.pixelCount.toFloat()/(pixels.width*pixels.height)
            val scene=base.copy(source=base.source.copy(name="Android 의미분할·밝은 부분 관측",
                detail="${raster.description}; ${SemanticContrastObserver.VERSION}; role preparation+inference ms=${raster.roleElapsedMs}; lightObserverMs=$observeMs; legacyBaseMeasurementMs=$legacyMs; " +
                    "$objectModelDescription; actual decoder PTS us=${frame.mediaPresentationTimeUs}"),
                ambientLights=AmbientLightObservation(measured.regions.size,area,true,null),semantic=measured.semantic.copy(skyColor=sky),
                regions=base.regions.filter { it.kind!=RegionEvidenceKind.AMBIENT_LIGHT }+measured.regions,
                lightFeatures=measured.features,
                notes=listOfNotNull("건물·창문·표지 영역의 밝은 픽셀을 관측합니다. 상점이나 발광 종류는 미확인입니다.",
                    raster.fallbackReason?.let { "보조 장면 분석을 사용할 수 없어 기본 모델로 분석했습니다. ($it)" }))
            VisionResult(frame.capturedAtEpochMs,AnalysisStatus.LOW_CONFIDENCE,
                colors=ColorFeatures(luminousAreaRatio=area),vehicleExcludedRatio=measured.vehicleAreaRatio,
                method=AnalysisMethod.HYBRID,reasons=measured.features.quality.reasons,
                debugRegions=scene.regions.take(64).map { DebugRegion(it.left,it.top,it.right,it.bottom,it.label,it.kind==RegionEvidenceKind.VEHICLE) },
                processingTimeMs=max(0L,(nanoTime()-start)/1_000_000),vehicleMaskApplied=true,sceneObservations=scene)
        } catch(error:Exception) {
            android.util.Log.e("SemanticLight", "Frame analysis failed", error)
            release();nextRetry=start+5_000_000_000
            fail(AnalysisStatus.FAILED,"영상 분석에 실패했습니다. 이전 결과를 사용하지 않습니다.")
        } catch(error:LinkageError) {
            android.util.Log.e("SemanticLight", "Runtime inference failed", error)
            release();nextRetry=start+5_000_000_000
            fail(AnalysisStatus.FAILED,"의미분할 런타임 실행에 실패했습니다.")
        }
    }
    private fun release() {
        val oldModel=model;val oldDetector=detector;model=null;detector=null
        try { oldModel?.close() } catch(_:Exception) {} catch(_:LinkageError) {}
        try { oldDetector?.close() } catch(_:Exception) {} catch(_:LinkageError) {}
    }
    override fun close() { if(!closed){closed=true;release()} }
}

