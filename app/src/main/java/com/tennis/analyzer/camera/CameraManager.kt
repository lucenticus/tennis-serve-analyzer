package com.tennis.analyzer.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK
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

        val videoCapture = serveRecorder.buildUseCase(isFrontCamera)

        prov.unbindAll()
        prov.bindToLifecycle(lifecycleOwner, selector, preview, analysis, videoCapture)
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
