package kr.urbanmood.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kr.urbanmood.contracts.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class FrameReceipt(val sequence: Long, val width: Int, val height: Int, val processingMs: Long,
    val receivedAtElapsedMs: Long = 0)

/** Main-thread lifecycle commands; only analyze/close run on the single worker. */
class CameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: VisionAnalyzer,
    private val onStatus: (String) -> Unit,
    private val onResult: (VisionResult, FrameReceipt) -> Unit,
    private val onFailure: (String) -> Unit = {},
) : AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "UrbanMood-vision") }
    private val tokens = SessionTokens()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var boundCamera: Camera? = null
    private var running = false
    private var closed = false
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val display = previewView.display ?: return
            if (display.displayId == displayId) {
                preview?.targetRotation = display.rotation
                analysis?.targetRotation = display.rotation
            }
        }
    }

    fun start(singleShot: Boolean = false) {
        if (closed || running) return
        running = true
        val token = tokens.advance()
        onStatus("카메라 연결 중…")
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!tokens.isCurrent(token) || !running || closed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    stop("후면 카메라를 찾을 수 없습니다.")
                    onFailure("후면 카메라를 찾을 수 없어 영상은 반영하지 않았습니다.")
                    return@addListener
                }
                val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val p = Preview.Builder().setResolutionSelector(selector).setTargetRotation(rotation).build()
                val a = ImageAnalysis.Builder().setResolutionSelector(selector).setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
                preview = p
                analysis = a
                p.setSurfaceProvider(previewView.surfaceProvider)
                var lastSampleNs = Long.MIN_VALUE
                var sequence = 0L
                val sampleTaken = AtomicBoolean(false)
                a.setAnalyzer(worker) { proxy ->
                    var bitmap: Bitmap? = null
                    try {
                        if (!tokens.isCurrent(token)) return@setAnalyzer
                        if (singleShot && !sampleTaken.compareAndSet(false, true)) return@setAnalyzer
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (lastSampleNs != Long.MIN_VALUE && now - lastSampleNs < CapturePolicy.SAMPLE_INTERVAL_NS) return@setAnalyzer
                        lastSampleNs = now
                        val receivedAt = System.currentTimeMillis()
                        bitmap = uprightReducedBitmap(proxy)
                        if (!tokens.isCurrent(token)) return@setAnalyzer
                        sequence++
                        val result = analyzer.analyze(CameraFrame(bitmap, receivedAt, proxy.imageInfo.timestamp, sequence))
                        val receipt = FrameReceipt(sequence, bitmap.width, bitmap.height,
                            (SystemClock.elapsedRealtimeNanos() - now) / 1_000_000, now / 1_000_000)
                        main.execute { if (tokens.isCurrent(token)) {
                            if (singleShot) stop("영상 확인 완료 · 카메라를 종료했습니다.")
                            onResult(result, receipt)
                        } }
                    } catch (e: Exception) {
                        main.execute {
                            if (tokens.isCurrent(token)) {
                                if (singleShot) stop("영상 확인을 종료했습니다.")
                                onResult(VisionResult(
                                System.currentTimeMillis(), AnalysisStatus.FAILED,
                                reasons = listOf("프레임 처리 실패 (${e.javaClass.simpleName})")),
                                FrameReceipt(sequence, 0, 0, 0, SystemClock.elapsedRealtime()))
                            }
                        }
                    } finally {
                        bitmap?.recycle()
                        proxy.close()
                    }
                }
                boundCamera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, a)
                boundCamera?.cameraInfo?.cameraState?.observe(owner) { state ->
                    if (tokens.isCurrent(token) && state.error != null) {
                        stop("카메라 오류 ${state.error?.code} · 다른 앱 사용 여부와 권한을 확인하고 다시 시작하세요.")
                        onFailure("카메라를 사용할 수 없어 영상은 반영하지 않았습니다.")
                    }
                }
                displays.registerDisplayListener(rotationListener, Handler(Looper.getMainLooper()))
                onStatus("카메라 연결됨 · 첫 프레임 대기")
            } catch (e: Exception) {
                stop("카메라 시작 실패 (${e.javaClass.simpleName}) · 권한과 카메라 상태를 확인하세요.")
                onFailure("카메라를 시작하지 못해 영상은 반영하지 않았습니다.")
            }
        }, main)
    }

    fun stop(message: String = "정지됨 · 카메라가 해제되었습니다.") {
        tokens.advance()
        running = false
        displays.unregisterDisplayListener(rotationListener)
        boundCamera?.cameraInfo?.cameraState?.removeObservers(owner)
        boundCamera = null
        analysis?.clearAnalyzer()
        val bound = listOfNotNull(preview, analysis).toTypedArray()
        if (bound.isNotEmpty()) provider?.unbind(*bound)
        preview = null
        analysis = null
        onStatus(message)
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        worker.execute { analyzer.close() }
        worker.shutdown() // in-flight analyze returns before close, never block UI
    }

    private fun uprightReducedBitmap(proxy: ImageProxy): Bitmap {
        val raw = proxy.toBitmap()
        try {
            val (width, height) = CapturePolicy.scaledSize(raw.width, raw.height)
            val transform = Matrix().apply {
                postScale(width.toFloat() / raw.width, height.toFloat() / raw.height)
                postRotate(proxy.imageInfo.rotationDegrees.toFloat())
            }
            val transformed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, transform, true)
            // createBitmap may return its input for an identity transform.
            return if (transformed === raw) raw.copy(Bitmap.Config.ARGB_8888, false) else transformed
        } finally {
            raw.recycle()
        }
    }
}
