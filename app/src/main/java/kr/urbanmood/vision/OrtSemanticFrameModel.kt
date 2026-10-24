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
internal class OrtSemanticFrameModel(context: Context) : SemanticFrameModel {
    override val inputSize=320
    override val modelSha256=MODEL_SHA256
    private val environment: OrtEnvironment
    private val session: OrtSession
    private val input=ByteBuffer.allocateDirect(3*320*320*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var closed=false

    init {
        check(System.getenv("ORT_DISABLE_TELEMETRY")=="1") { "ORT telemetry must be disabled before native initialization" }
        val bytes=context.applicationContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        require(bytes.size==MODEL_BYTES) { "Unexpected semantic model size" }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash==MODEL_SHA256) { "Semantic model checksum mismatch" }
        environment=OrtEnvironment.getEnvironment()
        environment.setTelemetry(false)
        session=OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            environment.createSession(bytes,options)
        }
        try {
            val info=session.inputInfo["images"]?.info as? TensorInfo ?: error("Missing semantic input")
            require(info.type==OnnxJavaType.FLOAT && info.shape.contentEquals(longArrayOf(1,3,320,320)))
            val output=session.outputInfo["output0"]?.info as? TensorInfo ?: error("Missing semantic output")
            require(output.onnxType==TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8 && output.shape.contentEquals(longArrayOf(1,320,320)))
        } catch(error:Exception) { session.close();throw error }
    }
    override fun infer(prepared: SemanticPreparedFrame):ByteArray {
        check(!closed)
        require(prepared.layout.size==320 && prepared.tensor.size==3*320*320)
        input.clear();input.put(prepared.tensor);input.flip()
        return OnnxTensor.createTensor(environment,input,longArrayOf(1,3,320,320)).use { tensor ->
            session.run(mapOf("images" to tensor)).use { result ->
                val output=result[0] as? OnnxTensor ?: error("Semantic output is not a tensor")
                val buffer=output.byteBuffer
                require(buffer.remaining()==320*320)
                ByteArray(320*320).also { buffer.get(it) }
            }
        }
    }
    override fun close() { if(!closed){closed=true;session.close()} }
    companion object {
        const val MODEL_ASSET="vision/yolo26n-sem-ade20k-320.ort"
        const val MODEL_BYTES=6386728
        const val MODEL_SHA256="6ce74261203e17eeefda81e41dd8e7e764b0dc98368dc9662501a8de12054dfc"
    }
}

