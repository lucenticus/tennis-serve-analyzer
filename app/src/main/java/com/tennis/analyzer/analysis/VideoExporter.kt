package com.tennis.analyzer.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.tennis.analyzer.pose.DetectedObject
import com.tennis.analyzer.pose.LandmarkIndex
import com.tennis.analyzer.pose.PoseFrame
import java.io.File

object VideoExporter {

    private const val TAG = "VideoExporter"
    private const val BIT_RATE = 8_000_000
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL = 1

    /**
     * Рендерит видео с наложением скелета и детекции объектов.
     * Запускать на фоновом потоке.
     */
    fun export(
        videoFile: File,
        frames: List<PoseFrame>,
        videoWidth: Int,
        videoHeight: Int,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (frames.isEmpty()) return false

        val retriever = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        return try {
            retriever.setDataSource(videoFile.absolutePath)

            val outW = videoWidth.let { if (it % 2 == 0) it else it - 1 }
            val outH = videoHeight.let { if (it % 2 == 0) it else it - 1 }

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxTrack = -1
            var muxerStarted = false

            val sorted = frames.sortedBy { it.timestampMs }
            val bufInfo = MediaCodec.BufferInfo()
            val argbBuf = IntArray(outW * outH)

            sorted.forEachIndexed { i, poseFrame ->
                onProgress(i.toFloat() / sorted.size * 0.95f)

                // Декодируем кадр видео по временной метке
                val raw = retriever.getFrameAtTime(
                    poseFrame.timestampMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: return@forEachIndexed

                // Масштаб к размеру выходного видео
                val src = if (raw.width != outW || raw.height != outH)
                    Bitmap.createScaledBitmap(raw, outW, outH, true)
                else raw

                // Рисуем оверлей позы и объектов
                val mutable = src.copy(Bitmap.Config.ARGB_8888, true)
                drawOverlay(Canvas(mutable), poseFrame, outW, outH)

                // Передаём в энкодер через Image API
                val inputIdx = encoder.dequeueInputBuffer(100_000L)
                if (inputIdx >= 0) {
                    val img = encoder.getInputImage(inputIdx)
                    if (img != null) {
                        copyBitmapToImage(mutable, img, argbBuf, outW, outH)
                    }
                    encoder.queueInputBuffer(inputIdx, 0, 0,
                        poseFrame.timestampMs * 1000L, 0)
                }

                // Сливаем выходные буферы в мухер
                var drained = false
                while (!drained) {
                    val outIdx = encoder.dequeueOutputBuffer(bufInfo, 0L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            if (outBuf != null && muxerStarted && bufInfo.size > 0 &&
                                bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                muxer.writeSampleData(muxTrack, outBuf, bufInfo)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) drained = true
                        }
                        else -> drained = true
                    }
                }

                if (src !== raw) src.recycle()
                mutable.recycle()
                raw.recycle()
            }

            // Сигнал конца потока
            val eosIdx = encoder.dequeueInputBuffer(100_000L)
            if (eosIdx >= 0) {
                encoder.queueInputBuffer(eosIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // Дочитываем оставшиеся буферы
            var eosSeen = false
            while (!eosSeen) {
                val outIdx = encoder.dequeueOutputBuffer(bufInfo, 10_000L)
                when {
                    outIdx >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(outIdx)
                        if (muxerStarted && outBuf != null && bufInfo.size > 0 &&
                            bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(muxTrack, outBuf, bufInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosSeen = true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> eosSeen = true
                    else -> {}
                }
            }

            onProgress(1f)
            Log.i(TAG, "Export done: ${outputFile.length() / 1024}KB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            outputFile.delete()
            false
        } finally {
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { if (muxer != null) muxer.stop(); muxer?.release() } catch (_: Exception) {}
            retriever.release()
        }
    }

    // ── Рисование оверлея ─────────────────────────────────────────────────────

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.WHITE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.CYAN
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.YELLOW
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW
    }
    private val racketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(0, 230, 255)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; isFakeBoldText = true
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0); style = Paint.Style.FILL
    }

    private val highlightJoints = setOf(
        LandmarkIndex.RIGHT_SHOULDER, LandmarkIndex.RIGHT_ELBOW, LandmarkIndex.RIGHT_WRIST,
        LandmarkIndex.LEFT_SHOULDER,  LandmarkIndex.LEFT_ELBOW,  LandmarkIndex.LEFT_WRIST
    )

    private fun drawOverlay(canvas: Canvas, frame: PoseFrame, w: Int, h: Int) {
        val lms = frame.landmarks
        if (lms.isEmpty()) return

        fun lmX(i: Int) = lms.getOrNull(i)?.x?.times(w) ?: 0f
        fun lmY(i: Int) = lms.getOrNull(i)?.y?.times(h) ?: 0f

        // Кости
        for ((a, b) in LandmarkIndex.CONNECTIONS) {
            if (a >= lms.size || b >= lms.size) continue
            bonePaint.color = if (a in highlightJoints && b in highlightJoints)
                Color.rgb(255, 180, 0) else Color.WHITE
            canvas.drawLine(lmX(a), lmY(a), lmX(b), lmY(b), bonePaint)
        }

        // Суставы
        for ((idx, _) in lms.withIndex()) {
            if (idx >= lms.size) continue
            val isHighlight = idx in highlightJoints
            canvas.drawCircle(lmX(idx), lmY(idx),
                if (isHighlight) 14f else 8f,
                if (isHighlight) highlightPaint else jointPaint)
        }

        // YOLO bbox: мяч и ракетка
        for (obj in frame.objects) {
            val cx = obj.cx * w; val cy = obj.cy * h
            val hw = obj.w * w / 2f; val hh = obj.h * h / 2f
            val paint = if (obj.classId == DetectedObject.CLASS_BALL) ballPaint else racketPaint
            val label = if (obj.classId == DetectedObject.CLASS_BALL) "мяч" else "ракетка"
            canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, paint)
            val lw = labelPaint.measureText(label)
            canvas.drawRect(cx - hw, cy - hh - 36f, cx - hw + lw + 12f, cy - hh, labelBgPaint)
            canvas.drawText(label, cx - hw + 6f, cy - hh - 6f, labelPaint)
        }
    }

    // ── ARGB Bitmap → YUV_420_888 Image ───────────────────────────────────────

    private fun copyBitmapToImage(
        bitmap: Bitmap,
        image: android.media.Image,
        argbBuf: IntArray,
        w: Int, h: Int
    ) {
        bitmap.getPixels(argbBuf, 0, w, 0, 0, w, h)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yStride = yPlane.rowStride
        val uvPixStride = uPlane.pixelStride
        val uvRowStride = uPlane.rowStride

        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = argbBuf[j * w + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(j * yStride + i, y.coerceIn(16, 235).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val base = (j / 2) * uvRowStride + (i / 2) * uvPixStride
                    uBuf.put(base, u.coerceIn(16, 240).toByte())
                    vBuf.put(base, v.coerceIn(16, 240).toByte())
                }
            }
        }
    }
}
