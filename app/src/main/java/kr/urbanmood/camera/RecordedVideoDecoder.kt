package kr.urbanmood.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** Sequential decoded presentation timestamps. No key-frame seeking or fabricated times. */
class RecordedVideoDecoder {
    suspend fun decode(clip: RecordedClip, frame: suspend (Bitmap, Long, Long) -> Unit): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        var count = 0
        try {
            extractor.setDataSource(clip.file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("저장된 영상 트랙을 읽을 수 없습니다.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val storedDurationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            check(validRecordingDuration(clip.timing.requestedMs, storedDurationMs)) { "저장된 영상 길이를 확인하지 못했습니다." }
            val rotation = format.intOr(MediaFormat.KEY_ROTATION, 0)
            require(rotation in setOf(0, 90, 180, 270)) { "영상 방향 정보를 읽을 수 없습니다." }
            // Raw YUV is rotated explicitly exactly once, independent of display/surface rendering.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start(); codecStarted = true
            val sampler = PresentationSampler(clip.timing.durationMs)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputFormat = format
            var lastOutputAt = SystemClock.elapsedRealtime()
            var firstDecodedPts: Long? = null
            var lastDecodedPts = -1L
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() - lastOutputAt < 15_000) { "영상 디코딩 응답이 지연되었습니다." }
                if (!inputEnded) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val bytes = requireNotNull(decoder.getInputBuffer(input)).apply { clear() }
                        val size = extractor.readSampleData(bytes, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = decoder.outputFormat
                if (output >= 0) {
                    lastOutputAt = SystemClock.elapsedRealtime()
                    try {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !isConfig) {
                            if (info.presentationTimeUs >= 0) {
                                if (firstDecodedPts == null) firstDecodedPts = info.presentationTimeUs
                                lastDecodedPts = maxOf(lastDecodedPts, info.presentationTimeUs)
                            }
                            val elapsed = sampler.accept(info.presentationTimeUs)
                            if (elapsed != null) {
                                val image = decoder.getOutputImage(output) ?: error("이 기기에서 영상 프레임을 읽을 수 없습니다.")
                                val bitmap = try { uprightBitmap(image, outputFormat, rotation) } finally { image.close() }
                                try {
                                    currentCoroutineContext().ensureActive()
                                    frame(bitmap, info.presentationTimeUs, elapsed)
                                    count++
                                } finally { bitmap.recycle() }
                                lastOutputAt = SystemClock.elapsedRealtime()
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(output, false) }
                }
            }
            check(count > 0) { "분석할 영상 프레임을 찾지 못했습니다." }
            check(firstDecodedPts != null && (lastDecodedPts - firstDecodedPts!!) / 1000 >= clip.timing.durationMs - 1_500) {
                "영상의 끝까지 읽지 못했습니다. 다시 촬영해 주세요."
            }
            return count
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun uprightBitmap(image: Image, format: MediaFormat, rotation: Int): Bitmap {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "지원하지 않는 영상 색 형식입니다." }
        val crop = image.cropRect
        require(crop.width() > 0 && crop.height() > 0)
        val scale = minOf(1f, 640f / maxOf(crop.width(), crop.height()))
        val w = (crop.width() * scale).roundToInt().coerceAtLeast(1)
        val h = (crop.height() * scale).roundToInt().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        val planes = image.planes
        val bases = planes.map { it.buffer.position() }
        val standard = format.intOr(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
        val full = format.intOr(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) == MediaFormat.COLOR_RANGE_FULL
        fun channel(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]
            return p.buffer.get(bases[plane] + y * p.rowStride + x * p.pixelStride).toInt() and 255
        }
        for (y in 0 until h) for (x in 0 until w) {
            val sx = crop.left + ((x + .5f) * crop.width() / w).toInt().coerceAtMost(crop.width() - 1)
            val sy = crop.top + ((y + .5f) * crop.height() / h).toInt().coerceAtMost(crop.height() - 1)
            pixels[y * w + x] = yuvToArgb(channel(0, sx, sy), channel(1, sx / 2, sy / 2),
                channel(2, sx / 2, sy / 2), full, standard)
        }
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return raw
        return try {
            Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, false)
        } finally { raw.recycle() }
    }
}

private fun MediaFormat.intOr(key: String, fallback: Int) = if (containsKey(key)) getInteger(key) else fallback

/** SDR Y'CbCr matrix selected from decoder metadata; not a physical light calibration. */
internal fun yuvToArgb(y: Int, u: Int, v: Int, full: Boolean, standard: Int): Int {
    val (kr, kb) = when (standard) {
        MediaFormat.COLOR_STANDARD_BT709 -> .2126 to .0722
        MediaFormat.COLOR_STANDARD_BT2020 -> .2627 to .0593
        else -> .299 to .114
    }
    val yy = if (full) y.toDouble() else (y - 16) * 255.0 / 219
    val cb = (u - 128) * if (full) 1.0 else 255.0 / 224
    val cr = (v - 128) * if (full) 1.0 else 255.0 / 224
    val kg = 1 - kr - kb
    val r = (yy + 2 * (1 - kr) * cr).roundToInt().coerceIn(0, 255)
    val b = (yy + 2 * (1 - kb) * cb).roundToInt().coerceIn(0, 255)
    val g = (yy - 2 * kb * (1 - kb) / kg * cb - 2 * kr * (1 - kr) / kg * cr).roundToInt().coerceIn(0, 255)
    return (255 shl 24) or (r shl 16) or (g shl 8) or b
}
