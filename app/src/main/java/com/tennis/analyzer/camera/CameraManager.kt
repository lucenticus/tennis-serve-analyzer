package com.tennis.analyzer.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val serveRecorder: ServeRecorder,
    private val onFrame: (Bitmap, Long) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // true = фронталка зеркалит изображение, нужно отразить bitmap для корректного анализа
    val isFrontCamera get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    fun start() {
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                provider = future.get()
                bindCamera()
            }, ContextCompat.getMainExecutor(context))
        }
    }

    fun switchCamera() {
        setCamera(!isFrontCamera)
    }

    /** Явно выставить камеру (для настроек и восстановления состояния при запуске). */
    fun setCamera(front: Boolean) {
        val target = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (target == lensFacing) return
        lensFacing = target
        bindCamera()
    }

    private fun bindCamera() {
        val prov = provider ?: return

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(executor) { proxy -> processFrame(proxy) } }

        prov.unbindAll()

        // На части устройств (эмуляторы, отдельные бюджетные чипы со слабым Camera2 HAL)
        // камера не сообщает ни одного поддерживаемого профиля качества видео — тогда
        // CameraX бросает IllegalArgumentException при биндинге VideoCapture и приложение
        // крашится. Предварительная проверка через getVideoCapabilities() недостаточна:
        // список качеств может быть непустым, но реальный merge конфигов внутри
        // StreamSharing (когда камера не тянет 3 одновременных потока — preview+analysis+
        // video — и CameraX объединяет их в один виртуальный поток) всё равно падает с
        // тем же исключением на другом этапе. Поэтому дополнительно оборачиваем сам
        // bindToLifecycle в try/catch и откатываемся на preview+analysis без записи видео.
        val cameraInfo = selector.filter(prov.availableCameraInfos).firstOrNull()
        val supportsVideo = cameraInfo != null && runCatching {
            Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR).isNotEmpty()
        }.getOrDefault(false)

        if (!supportsVideo) {
            Log.w(TAG, "Camera reports no supported video qualities — recording disabled, preview only")
            prov.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            return
        }

        val videoCapture = serveRecorder.buildUseCase(isFrontCamera)
        try {
            prov.bindToLifecycle(lifecycleOwner, selector, preview, analysis, videoCapture)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Video bind failed (${e.message}) — retrying preview+analysis only")
            prov.unbindAll()
            prov.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }
    }

    private companion object {
        const val TAG = "CameraManager"
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toBitmap()
        var rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees.toFloat())
        // Фронталка зеркалит X — отражаем чтобы правая рука оставалась правой для MediaPipe
        if (isFrontCamera) rotated = mirrorBitmap(rotated)
        onFrame(rotated, imageProxy.imageInfo.timestamp / 1_000_000)
        imageProxy.close()
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
