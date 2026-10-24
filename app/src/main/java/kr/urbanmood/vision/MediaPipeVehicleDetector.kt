package kr.urbanmood.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class DetectionBatch(val boxes: List<ObjectBox>, val atCapacity: Boolean)
internal interface FrameObjectDetector : AutoCloseable {
    fun detect(borrowedBitmap: Bitmap): DetectionBatch
}

/** API usage informed by Google's Apache-2.0 ObjectDetectorHelper example (see docs/vision).
 * This adapter owns its input copy; A's borrowed Bitmap must never be recycled here.
 * IMAGE mode is synchronous on A's serial worker; no nested streaming queue.
 */
internal class MediaPipeVehicleDetector(context: Context) : FrameObjectDetector {
    // Direct buffer avoids depending on A's asset compression settings; kept alive with detector.
    private val modelBuffer: ByteBuffer
    private val detector: ObjectDetector
    init {
        val bytes=context.assets.open(MODEL_PATH).use { it.readBytes() }
        require(bytes.size==MODEL_BYTES) { "Model asset size mismatch" }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash==MODEL_SHA256) { "Model asset checksum mismatch" }
        modelBuffer=ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
        detector=ObjectDetector.createFromOptions(context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(modelBuffer).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(.25f)
                .setMaxResults(-1) // Underlying model still has a 25-detection ceiling.
                .build())
    }
    override fun detect(borrowedBitmap: Bitmap): DetectionBatch {
        val owned=checkNotNull(borrowedBitmap.copy(Bitmap.Config.ARGB_8888,false))
        var image: MPImage?=null
        try {
            val input=BitmapImageBuilder(owned).build()
            image=input
            val results=detector.detect(input).detections()
            val boxes=results.mapNotNull { detection ->
                val category=detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val bounds=detection.boundingBox()
                ObjectBox(bounds.left/owned.width,bounds.top/owned.height,bounds.right/owned.width,
                    bounds.bottom/owned.height,category.categoryName(),category.score())
            }
            return DetectionBatch(boxes,results.size>=25)
        } finally {
            try { image?.close() } // Current MediaPipe BitmapImageContainer recycles owned.
            finally { if (!owned.isRecycled) owned.recycle() }
        }
    }
    override fun close() { detector.close() }
    companion object {
        const val MODEL_PATH="vision/efficientdet-lite0-metadata-v1.tflite"
        const val MODEL_BYTES=4563519
        const val MODEL_SHA256="2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b"
    }
}
