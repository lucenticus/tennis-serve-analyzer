package com.tennis.analyzer.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

object VideoFrameExtractor {

    private const val TAG = "VideoFrameExtractor"

    data class VideoMeta(val durationMs: Long, val width: Int, val height: Int)

    /**
     * Последовательно декодирует видео через MediaCodec.
     * Берёт кадр каждые [stepMs] мс в диапазоне [startMs, endMs].
     * [startMs] = 0 и [endMs] = Long.MAX_VALUE → весь файл.
     */
    fun extract(
        videoFile: File,
        stepMs: Long = 100L,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onFrame: (bitmap: Bitmap, timestampMs: Long) -> Unit
    ): VideoMeta {
        val extractor = MediaExtractor()
        extractor.setDataSource(videoFile.absolutePath)

        var videoTrackIdx = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoTrackIdx = i
                videoFormat = fmt
                break
            }
        }
        if (videoTrackIdx < 0 || videoFormat == null) {
            extractor.release()
            return VideoMeta(0L, 1080, 1920)
        }

        extractor.selectTrack(videoTrackIdx)

        val durationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)
        val durationMs = durationUs / 1000L
        val rawW = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val rawH = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val rotation = if (videoFormat.containsKey("rotation-degrees"))
            videoFormat.getInteger("rotation-degrees") else 0
        val (displayW, displayH) = if (rotation == 90 || rotation == 270) rawH to rawW else rawW to rawH

        val rotMatrix = if (rotation != 0)
            Matrix().apply { postRotate(rotation.toFloat()) } else null

        val clampedStart = startMs.coerceIn(0L, durationMs)
        val clampedEnd   = endMs.coerceIn(clampedStart, durationMs)
        val totalFrames  = (((clampedEnd - clampedStart) + stepMs - 1) / stepMs).toInt().coerceAtLeast(1)
        val stepUs = stepMs * 1000L
        val endUs  = clampedEnd * 1000L

        Log.i(TAG, "decode: ${durationMs}ms ${rawW}x${rawH} rot=$rotation → ${displayW}x${displayH}, " +
            "range=[${clampedStart}..${clampedEnd}]ms step=${stepMs}ms ~$totalFrames samples")

        // Сик к ближайшему keyframe перед startMs
        if (clampedStart > 0L) {
            extractor.seekTo(clampedStart * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val codec = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(videoFormat, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var nextTargetUs = clampedStart * 1000L
        var sampledCount = 0

        while (!outputEos) {
            // Подаём данные в декодер
            if (!inputEos) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Читаем декодированные кадры
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIdx >= 0) {
                val presentUs = bufferInfo.presentationTimeUs

                if (presentUs > endUs) { codec.releaseOutputBuffer(outIdx, false); outputEos = true; break }

                if (presentUs >= nextTargetUs) {
                    val image = codec.getOutputImage(outIdx)
                    if (image != null) {
                        try {
                            val bitmap = imageToBitmap(image, rawW, rawH, rotMatrix)
                            onProgress(sampledCount, totalFrames)
                            onFrame(bitmap, presentUs / 1000L)
                            sampledCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "frame convert failed at ${presentUs / 1000}ms: ${e.message}")
                        }
                        image.close()
                    }
                    nextTargetUs = presentUs + stepUs
                }

                codec.releaseOutputBuffer(outIdx, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputEos = true
                }
            }
        }

        Log.i(TAG, "decode done: $sampledCount frames sampled")

        codec.stop()
        codec.release()
        extractor.release()

        return VideoMeta(durationMs, displayW, displayH)
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int, rotMatrix: Matrix?): Bitmap {
        val nv21 = toNv21(image, width, height)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val raw = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        return if (rotMatrix != null)
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotMatrix, false)
        else raw
    }

    private fun toNv21(image: Image, width: Int, height: Int): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf  = yPlane.buffer
        val uBuf  = uPlane.buffer
        val vBuf  = vPlane.buffer
        val yRowStride  = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        // Y plane (копируем построчно, учитываем padding)
        for (row in 0 until height) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, row * width, width)
        }

        // VU interleaved → NV21
        var dst = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val src = row * uvRowStride + col * uvPixStride
                vBuf.position(src); nv21[dst++] = vBuf.get()
                uBuf.position(src); nv21[dst++] = uBuf.get()
            }
        }

        return nv21
    }
}
