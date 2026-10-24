package kr.urbanmood.vision

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Fixed YOLO26n COCO end-to-end export, CPU only. No additional NMS.
 * Uses the existing RGB letterbox sampler; never retains or recycles the borrowed Bitmap.
 * Official exporter/weights: https://github.com/ultralytics/ultralytics (AGPL-3.0).
 * ORT Java API: https://onnxruntime.ai/docs/get-started/with-java.html (MIT runtime).
 * The caller owns frame identity/PTS; this synchronous adapter creates no queue or frame IDs.
 */
internal class OrtObjectDetector(context: Context) : FrameObjectDetector {
    private val environment: OrtEnvironment
    private val session: OrtSession
    private val input = ByteBuffer.allocateDirect(3 * INPUT_SIZE * INPUT_SIZE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var closed = false

    init {
        check(System.getenv("ORT_DISABLE_TELEMETRY") == "1") {
            "ORT telemetry must be disabled before native initialization"
        }
        val bytes = context.applicationContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        require(bytes.size == MODEL_BYTES) { "Unexpected object model size" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == MODEL_SHA256) { "Object model checksum mismatch" }
        environment = OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            // Match semantic sessions: release temporary CPU allocations between runs.
            // Accuracy and input geometry are unchanged; allocation cost is benchmarked.
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            environment.createSession(bytes, options)
        }
        try {
            require(session.inputNames == setOf("images") && session.outputNames == setOf("output0"))
            val image = session.inputInfo["images"]?.info as? TensorInfo ?: error("Missing object input")
            require(image.type == OnnxJavaType.FLOAT && image.shape.contentEquals(longArrayOf(1, 3, 640, 640)))
            val output = session.outputInfo["output0"]?.info as? TensorInfo ?: error("Missing object output")
            require(output.type == OnnxJavaType.FLOAT && output.shape.contentEquals(longArrayOf(1, 300, 6)))
            val metadata = session.metadata.customMetadata
            require(metadata["task"] == "detect" && metadata["end2end"] == "True")
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    @Synchronized
    override fun detect(borrowedBitmap: Bitmap): DetectionBatch {
        check(!closed) { "Object detector is closed" }
        require(!borrowedBitmap.isRecycled && borrowedBitmap.config == Bitmap.Config.ARGB_8888)
        val width = borrowedBitmap.width
        val height = borrowedBitmap.height
        require(width in 1..640 && height in 1..640)
        val pixels = IntArray(width * height)
        borrowedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val prepared = SemanticModelInput.prepare(PixelFrame(width, height, pixels), INPUT_SIZE)
        input.clear()
        input.put(prepared.tensor)
        input.flip()
        return OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, 640, 640)).use { tensor ->
            session.run(mapOf("images" to tensor)).use { result ->
                val output = result[0] as? OnnxTensor ?: error("Object output is not a tensor")
                val buffer = output.floatBuffer
                require(buffer.remaining() == GRAPH_CAPACITY * 6)
                val rows = FloatArray(GRAPH_CAPACITY * 6)
                buffer.get(rows)
                decode(rows, width, height, prepared.layout)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            session.close()
        }
    }

    companion object {
        const val INPUT_SIZE = 640
        const val GRAPH_CAPACITY = 300
        // Preserve the existing downstream PersonObservation maximum of 25 until that contract changes.
        const val RESULT_CAPACITY = 25
        const val SCORE_THRESHOLD = .25f
        const val MODEL_ASSET = "vision/yolo26n-detect-640.onnx"
        const val MODEL_BYTES = 9893909
        const val MODEL_SHA256 = "6236c6b2a47706c21edb508ca8a66d9b2f3772fe2b1410fee3cd70896ac0277a"
        // Export reference before non-computational description metadata sanitization.
        const val SOURCE_MODEL_SHA256 = "b7bdfacd8bedbf2f679fc1959a2315dcc0e6e1c3b01ea26012be3f0b5c0eab06"
        internal val LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
            "scissors", "teddy bear", "hair drier", "toothbrush",
        )

        /** [x1,y1,x2,y2,score,class] in the letterboxed 640 raster -> original normalized coordinates. */
        internal fun decode(rows: FloatArray, width: Int, height: Int, layout: SemanticLetterbox): DetectionBatch {
            require(width in 1..640 && height in 1..640)
            require(rows.size == GRAPH_CAPACITY * 6 && rows.all { it.isFinite() })
            val scale = minOf(INPUT_SIZE.toDouble() / width, INPUT_SIZE.toDouble() / height)
            val scaledWidth = Math.rint(width * scale).toInt().coerceIn(1, INPUT_SIZE)
            val scaledHeight = Math.rint(height * scale).toInt().coerceIn(1, INPUT_SIZE)
            require(layout == SemanticLetterbox(INPUT_SIZE, scaledWidth, scaledHeight,
                (INPUT_SIZE - scaledWidth) / 2, (INPUT_SIZE - scaledHeight) / 2))
            val accepted = ArrayList<ObjectBox>()
            var aboveThreshold = 0
            for (i in 0 until GRAPH_CAPACITY) {
                val offset = i * 6
                val score = rows[offset + 4]
                val classValue = rows[offset + 5]
                require(score in 0f..1f && classValue in 0f..79f && classValue == classValue.toInt().toFloat())
                if (score < SCORE_THRESHOLD) continue
                aboveThreshold++
                val left = ((rows[offset] - layout.left) / scale / width).toFloat().coerceIn(0f, 1f)
                val top = ((rows[offset + 1] - layout.top) / scale / height).toFloat().coerceIn(0f, 1f)
                val right = ((rows[offset + 2] - layout.left) / scale / width).toFloat().coerceIn(0f, 1f)
                val bottom = ((rows[offset + 3] - layout.top) / scale / height).toFloat().coerceIn(0f, 1f)
                if (right <= left || bottom <= top) continue
                accepted += ObjectBox(left, top, right, bottom, LABELS[classValue.toInt()], score)
            }
            val sorted = accepted.sortedByDescending { it.confidence }
            // Exactly at the retained ceiling is conservatively capacity-limited, as in the legacy detector.
            return DetectionBatch(sorted.take(RESULT_CAPACITY),
                sorted.size >= RESULT_CAPACITY || aboveThreshold == GRAPH_CAPACITY)
        }
    }
}
