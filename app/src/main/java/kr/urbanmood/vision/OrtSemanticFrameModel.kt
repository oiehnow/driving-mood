package kr.urbanmood.vision

import android.content.Context
import ai.onnxruntime.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Pinned CPU runtime adapter with verified model bytes and reusable input storage.
 * Application initialization must disable telemetry before ORT/provider initialization.
 * Model AGPL/Enterprise terms are separate from the runtime's MIT license.
 */
internal data class SemanticModelAsset(val path:String,val bytes:Int,val sha256:String,val inputSize:Int)

internal class OrtSemanticFrameModel(context: Context,
    private val asset:SemanticModelAsset=BASELINE) : SemanticFrameModel {
    override val inputSize=asset.inputSize
    override val modelSha256=asset.sha256
    private val environment: OrtEnvironment
    private val session: OrtSession
    private val input=ByteBuffer.allocateDirect(3*inputSize*inputSize*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var closed=false

    init {
        check(System.getenv("ORT_DISABLE_TELEMETRY")=="1") { "ORT telemetry must be disabled before native initialization" }
        require(inputSize==320 || inputSize==640)
        val bytes=context.applicationContext.assets.open(asset.path).use { it.readBytes() }
        require(bytes.size==asset.bytes) { "Unexpected semantic model size" }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash==asset.sha256) { "Semantic model checksum mismatch" }
        environment=OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        session=OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            // Serial role sessions must not each retain a large CPU activation arena.
            // Keep tensor precision, graph optimizations and model operators unchanged.
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            environment.createSession(bytes,options)
        }
        try {
            val info=session.inputInfo["images"]?.info as? TensorInfo ?: error("Missing semantic input")
            require(info.type==OnnxJavaType.FLOAT && info.shape.contentEquals(longArrayOf(1,3,inputSize.toLong(),inputSize.toLong())))
            val output=session.outputInfo["output0"]?.info as? TensorInfo ?: error("Missing semantic output")
            require(output.onnxType==TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8 && output.shape.contentEquals(longArrayOf(1,inputSize.toLong(),inputSize.toLong())))
        } catch(error:Exception) { session.close();throw error }
    }
    override fun infer(prepared: SemanticPreparedFrame):ByteArray {
        check(!closed)
        require(prepared.layout.size==inputSize && prepared.tensor.size==3*inputSize*inputSize)
        input.clear();input.put(prepared.tensor);input.flip()
        return OnnxTensor.createTensor(environment,input,longArrayOf(1,3,inputSize.toLong(),inputSize.toLong())).use { tensor ->
            session.run(mapOf("images" to tensor)).use { result ->
                val output=result[0] as? OnnxTensor ?: error("Semantic output is not a tensor")
                val buffer=output.byteBuffer
                require(buffer.remaining()==inputSize*inputSize)
                ByteArray(inputSize*inputSize).also { buffer.get(it) }
            }
        }
    }
    override fun close() { if(!closed){closed=true;session.close()} }
    companion object {
        const val MODEL_ASSET="vision/yolo26n-sem-ade20k-320.ort"
        const val MODEL_BYTES=6386728
        const val MODEL_SHA256="6ce74261203e17eeefda81e41dd8e7e764b0dc98368dc9662501a8de12054dfc"
        val BASELINE=SemanticModelAsset(MODEL_ASSET,MODEL_BYTES,MODEL_SHA256,320)
        val SKY_640=SemanticModelAsset("vision/yolo26n-sem-ade20k-640.onnx",6292427,
            "bda25cea98386bde18ccc1faa1eb4cb3ce5574d3cc17005d876823b45f780e46",640)
        val STRUCTURE_C=SemanticModelAsset("vision/yolo26n-sem-pilot03c-320.onnx",6292459,
            "1d4a53af570b2f61293c857de9dc455b91e1720a894b4f6bdb15d04360f2fd23",320)
    }
}

