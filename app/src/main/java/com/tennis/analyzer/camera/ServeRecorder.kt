package com.tennis.analyzer.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Управляет записью видео через CameraX VideoCapture.
 * Запись стартует при обнаружении подброса, останавливается после follow-through.
 */
class ServeRecorder(private val context: Context) {

    var videoCapture: VideoCapture<Recorder>? = null
        private set

    private var activeRecording: Recording? = null
    private var recordingStartMs: Long = 0L
    private var onFinished: ((File, Long) -> Unit)? = null
    private var lastSavedFile: File? = null

    /**
     * Задняя камера: 1080p@120fps.
     * Фронтальная камера: 1080p@60fps (максимум для фронталки S25 Ultra).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun buildUseCase(isFrontCamera: Boolean = false): VideoCapture<Recorder> {
        val targetFps = if (isFrontCamera) 60 else 120

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            )
            .build()

        val builder = VideoCapture.Builder(recorder)
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(targetFps, targetFps)
            )

        Log.i(TAG, "Building VideoCapture: ${if (isFrontCamera) "front" else "back"} @ ${targetFps}fps 1080p")
        return builder.build().also { videoCapture = it }
    }

    /**
     * Начать запись. [onFinished] вызывается когда файл готов.
     */
    fun startRecording(onFinished: (videoFile: File, startMs: Long) -> Unit) {
        val vc = videoCapture ?: run {
            Log.e(TAG, "VideoCapture not initialized")
            return
        }
        if (activeRecording != null) return

        this.onFinished = onFinished
        recordingStartMs = System.currentTimeMillis()

        val file = createOutputFile()
        val outputOptions = FileOutputOptions.Builder(file).build()

        activeRecording = vc.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start ->
                        Log.i(TAG, "Recording started: ${file.name}")
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "Recording error: ${event.error} ${event.cause?.message}")
                            file.delete()
                        } else {
                            Log.i(TAG, "Recording saved: ${file.absolutePath} (${file.length()/1024}KB)")
                            lastSavedFile = file
                            // Используем this.onFinished — stopAndWait может его заменить
                            this.onFinished?.invoke(file, recordingStartMs)
                        }
                        activeRecording = null
                        this.onFinished = null
                    }
                    else -> Unit
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
    }

    /** Останавливает запись и вызывает [onFile] когда файл готов */
    fun stopAndWait(onFile: (File) -> Unit) {
        val rec = activeRecording ?: run {
            lastSavedFile?.let { onFile(it) }
            return
        }
        // Переопределяем onFinished чтобы получить файл
        val prevOnFinished = this.onFinished
        this.onFinished = { file, startMs ->
            prevOnFinished?.invoke(file, startMs)
            onFile(file)
        }
        rec.stop()
    }

    fun isRecording() = activeRecording != null

    fun lastFile(): File? = lastSavedFile

    private fun createOutputFile(): File {
        val dir = File(context.filesDir, "serves").also { it.mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "serve_$timestamp.mp4")
    }

    companion object {
        private const val TAG = "ServeRecorder"
    }
}
