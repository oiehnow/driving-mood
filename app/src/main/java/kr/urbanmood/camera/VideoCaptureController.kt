package kr.urbanmood.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resumeWithException

/** CameraX Recorder lifecycle adapted to View UI; silent video, no microphone permission.
 * Reference: android/camera-samples CameraXTakeAVideoController (Apache-2.0),
 * Copyright 2026 The Android Open Source Project. No sample UI or storage code copied.
 */
class VideoCaptureController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
) : AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var pending: CancellableContinuation<RecordedClip>? = null
    private var generation = 0L
    private var closed = false

    suspend fun record(durationMs: Long, progress: (Long) -> Unit, finalizing: () -> Unit): RecordedClip {
        require(durationMs in setOf(30_000L, 60_000L))
        check(!closed && pending == null) { "녹화가 이미 진행 중입니다." }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "카메라 권한을 확인해 주세요."
        }
        return withTimeout(durationMs + 30_000) {
            suspendCancellableCoroutine { continuation ->
                pending = continuation
                val token = ++generation
                var file: File? = null
                var startedAt = 0L
                var finalized = false
                fun current() = token == generation && continuation.isActive && !closed
                fun fail(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                    if (pending === continuation) pending = null
                }
                continuation.invokeOnCancellation {
                    main.execute {
                        if (token == generation) {
                            generation++
                            recording?.stop(); recording = null
                            unbind()
                            if (pending === continuation) pending = null
                        }
                        // The writer owns the file until Finalize; do not delete a live output.
                        if (finalized) file?.delete()
                    }
                }
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    if (!current()) return@addListener
                    try {
                        val p = future.get()
                        provider = p
                        val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                        val usePreview = Preview.Builder().setTargetRotation(rotation).build()
                        usePreview.setSurfaceProvider(previewView.surfaceProvider)
                        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(
                            listOf(Quality.HD, Quality.SD), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                        val capture = VideoCapture.withOutput(recorder).apply { targetRotation = rotation }
                        preview = usePreview; video = capture
                        p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, usePreview, capture)
                        val directory = File(context.cacheDir, "mood-recordings").apply { mkdirs() }
                        // Only abandoned files in our private directory, never user media or reports.
                        directory.listFiles()?.filter { it.name.startsWith("capture-") && it.extension == "mp4" &&
                            System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                        val output = File.createTempFile("capture-", ".mp4", directory)
                        file = output
                        val options = FileOutputOptions.Builder(output).setDurationLimitMillis(durationMs).build()
                        recording = capture.output.prepareRecording(context, options).start(main) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> if (current()) {
                                    startedAt = System.currentTimeMillis()
                                    progress(0)
                                }
                                is VideoRecordEvent.Status -> if (current()) {
                                    val elapsed = event.recordingStats.recordedDurationNanos / 1_000_000
                                    progress(elapsed.coerceAtMost(durationMs))
                                    if (elapsed >= durationMs) finalizing()
                                }
                                is VideoRecordEvent.Finalize -> {
                                    finalized = true
                                    val actual = event.recordingStats.recordedDurationNanos / 1_000_000
                                    if (!current()) { output.delete(); return@start }
                                    recording = null
                                    finalizing()
                                    unbind()
                                    pending = null
                                    val expected = event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
                                        event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                                    if (!expected || startedAt <= 0 || !validRecordingDuration(durationMs, actual) || output.length() == 0L) {
                                        output.delete()
                                        fail("영상을 완전히 저장하지 못했습니다. 저장 공간과 카메라 상태를 확인하고 다시 촬영해 주세요.")
                                    } else {
                                        val clip = RecordedClip(output, RecordedTiming(durationMs, actual.coerceAtMost(durationMs), startedAt))
                                        continuation.resume(clip) { _, abandoned, _ -> abandoned.close() }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        recording?.close(); recording = null
                        unbind()
                        file?.delete()
                        fail("카메라 녹화를 시작하지 못했습니다. 권한과 카메라 사용 상태를 확인해 주세요.")
                    }
                }, main)
            }
        }
    }

    fun cancel() { pending?.cancel(); recording?.stop(); recording = null; unbind() }
    private fun unbind() {
        val bound = listOfNotNull(preview, video).toTypedArray()
        if (bound.isNotEmpty()) provider?.unbind(*bound)
        preview = null; video = null
    }
    override fun close() { closed = true; cancel() }
}
