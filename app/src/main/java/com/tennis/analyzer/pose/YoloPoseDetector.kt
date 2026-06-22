package com.tennis.analyzer.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.tennis.analyzer.detection.OrtManager
import kotlin.math.max

/**
 * YOLOv8n-pose детектор позы через ONNX Runtime (QNN/XNNPACK).
 *
 * Возвращает PoseLandmark-массив в формате MediaPipe (33 слота),
 * чтобы не менять остальной код. Пустые слоты заполнены visibility=0.
 *
 * COCO 17 keypoints → MediaPipe slot mapping:
 *  COCO  0 nose        → MP  0
 *  COCO  5 l_shoulder  → MP 11    COCO  6 r_shoulder → MP 12
 *  COCO  7 l_elbow     → MP 13    COCO  8 r_elbow    → MP 14
 *  COCO  9 l_wrist     → MP 15    COCO 10 r_wrist    → MP 16
 *  COCO 11 l_hip       → MP 23    COCO 12 r_hip      → MP 24
 *  COCO 13 l_knee      → MP 25    COCO 14 r_knee     → MP 26
 *  COCO 15 l_ankle     → MP 27    COCO 16 r_ankle    → MP 28
 */
class YoloPoseDetector(private val context: Context) {

    private var session: OrtSession? = null
    var available = false
        private set

    private val pixels        = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputFloatBuf = java.nio.FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
    private val inputShape    = longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

    fun setup(): Boolean {
        val bytes = try {
            context.assets.open(MODEL_FILE).readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Model not found: ${e.message}")
            return false
        }
        return try {
            session = OrtManager.createSession(bytes, TAG)
            available = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed: ${e.message}")
            false
        }
    }

    /** Принимает уже масштабированный 640×640 bitmap — без внутреннего createScaledBitmap. */
    fun detectPreScaled(bitmap640: Bitmap): List<PoseLandmark> = detect(bitmap640, preScaled = true)

    fun detect(bitmap: Bitmap): List<PoseLandmark> = detect(bitmap, preScaled = false)

    private fun detect(bitmap: Bitmap, preScaled: Boolean): List<PoseLandmark> {
        if (!available) return emptyList()

        val scaled = if (preScaled || (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE)) bitmap
                     else Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        // RGB в NCHW float [0,1]
        inputFloatBuf.rewind()
        for (p in pixels) inputFloatBuf.put(((p shr 16) and 0xFF) / 255f)
        for (p in pixels) inputFloatBuf.put(((p shr 8)  and 0xFF) / 255f)
        for (p in pixels) inputFloatBuf.put((p           and 0xFF) / 255f)
        inputFloatBuf.rewind()

        val tensor = OnnxTensor.createTensor(OrtManager.env, inputFloatBuf, inputShape)
        return try {
            val t0 = System.currentTimeMillis()
            val lms = session!!.run(mapOf("images" to tensor)).use { results ->
                val out   = results.get(0) as? OnnxTensor ?: return emptyList()
                val shape = out.info.shape          // [1, 56, 8400]
                val nBoxes = shape[2].toInt()
                parse(out.floatBuffer, nBoxes)
            }
            Log.d(TAG, "inference ${System.currentTimeMillis() - t0}ms, detected=${lms.isNotEmpty()}")
            lms
        } catch (e: Exception) {
            Log.w(TAG, "inference error: ${e.message}")
            emptyList()
        } finally {
            tensor.close()
        }
    }

    /**
     * Буфер имеет layout [56, 8400]: строки — признаки, столбцы — якоря.
     * Строки 0..3 = cx,cy,w,h; строка 4 = confidence; строки 5..55 = 17×(x,y,vis).
     */
    private fun parse(buf: java.nio.FloatBuffer, nBoxes: Int): List<PoseLandmark> {
        // Ищем якорь с максимальным confidence
        var bestConf = CONF_THRESHOLD
        var bestBox  = -1
        for (i in 0 until nBoxes) {
            val conf = buf.get(4 * nBoxes + i)
            if (conf > bestConf) { bestConf = conf; bestBox = i }
        }
        if (bestBox < 0) return emptyList()

        // Извлекаем 17 COCO keypoints из строк 5..55
        val coco = Array(17) { k ->
            val base = (5 + k * 3) * nBoxes
            PoseLandmark(
                x          = buf.get(base + bestBox)              / INPUT_SIZE,
                y          = buf.get(base + nBoxes + bestBox)     / INPUT_SIZE,
                z          = 0f,
                visibility = buf.get(base + 2 * nBoxes + bestBox)
            )
        }

        // Раскладываем в 33-слотовый массив MediaPipe (неиспользуемые = пустые)
        val mp = Array(33) { EMPTY_LM }
        for ((cocoIdx, mpIdx) in COCO_TO_MP) {
            mp[mpIdx] = coco[cocoIdx]
        }
        return mp.toList()
    }

    fun close() {
        session?.close()
        session = null
        available = false
    }

    companion object {
        private const val TAG        = "YoloPoseDetector"
        private const val MODEL_FILE = "models/yolov8n-pose.onnx"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.35f

        private val EMPTY_LM = PoseLandmark(0f, 0f, 0f, 0f)

        // COCO index → MediaPipe slot
        private val COCO_TO_MP = mapOf(
            0  to 0,   // nose
            1  to 2,   // left_eye  → MP left_eye_inner (approx)
            2  to 5,   // right_eye → MP right_eye_inner (approx)
            5  to 11,  // left_shoulder
            6  to 12,  // right_shoulder
            7  to 13,  // left_elbow
            8  to 14,  // right_elbow
            9  to 15,  // left_wrist
            10 to 16,  // right_wrist
            11 to 23,  // left_hip
            12 to 24,  // right_hip
            13 to 25,  // left_knee
            14 to 26,  // right_knee
            15 to 27,  // left_ankle
            16 to 28   // right_ankle
        )
    }
}
