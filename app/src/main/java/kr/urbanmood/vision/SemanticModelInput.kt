package kr.urbanmood.vision

import kotlin.math.*

/** Transient inference frame data. Never attach these arrays to accumulated scene DTOs. */
internal data class SemanticPreparedFrame(
    val pixels: PixelFrame,
    val tensor: FloatArray,
    val layout: SemanticLetterbox,
)

internal data class SemanticLetterbox(val size: Int, val width: Int, val height: Int, val left: Int, val top: Int) {
    fun cropClassMap(square: ByteArray): ByteArray {
        require(square.size == size * size)
        require(square.all { (it.toInt() and 255) < 150 })
        return ByteArray(width * height) { i -> square[(i / width + top) * size + i % width + left] }
    }
}

/** Explicit RGB bilinear sampling and centered padding; independent of Android Canvas.
 * Model and light measurements share these exact sampled pixels. Pixel-center coordinates,
 * clamped borders, rounded RGB bytes, and ties-to-even resized dimensions are intentional.
 * Small fixed-point differences from OpenCV INTER_LINEAR must be measured, not hidden.
 */
internal object SemanticModelInput {
    fun prepare(frame: PixelFrame, size: Int = 320): SemanticPreparedFrame {
        require(size == 320 || size == 640)
        val scale = min(size.toDouble() / frame.width, size.toDouble() / frame.height)
        val w = Math.rint(frame.width * scale).toInt().coerceIn(1, size)
        val h = Math.rint(frame.height * scale).toInt().coerceIn(1, size)
        val left = (size - w) / 2; val top = (size - h) / 2
        val pixels = IntArray(w * h)
        val tensor = FloatArray(3 * size * size) { 114f / 255f }
        for (y in 0 until h) for (x in 0 until w) {
            val sx = ((x + .5) * frame.width / w - .5).coerceIn(0.0, frame.width - 1.0)
            val sy = ((y + .5) * frame.height / h - .5).coerceIn(0.0, frame.height - 1.0)
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val x1 = min(frame.width - 1, x0 + 1); val y1 = min(frame.height - 1, y0 + 1)
            val fx = sx - x0; val fy = sy - y0
            var color = 255 shl 24
            for (channel in 0..2) {
                val shift = 16 - channel * 8
                fun value(xx: Int, yy: Int) = (frame.argb[yy * frame.width + xx] ushr shift) and 255
                val upper = value(x0,y0)*(1-fx)+value(x1,y0)*fx
                val lower = value(x0,y1)*(1-fx)+value(x1,y1)*fx
                val byte = floor(upper*(1-fy)+lower*fy+.5).toInt().coerceIn(0,255)
                color = color or (byte shl shift)
                tensor[channel*size*size+(y+top)*size+x+left] = byte/255f
            }
            pixels[y*w+x] = color
        }
        return SemanticPreparedFrame(PixelFrame(w,h,pixels),tensor,SemanticLetterbox(size,w,h,left,top))
    }
}

internal interface SemanticFrameModel : AutoCloseable {
    val modelSha256: String
    val inputSize: Int
    /** Returns one ADE20K class per square input pixel, not class probabilities. */
    fun infer(prepared: SemanticPreparedFrame): ByteArray
}
