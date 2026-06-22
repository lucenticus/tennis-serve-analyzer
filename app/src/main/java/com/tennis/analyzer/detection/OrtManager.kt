package com.tennis.analyzer.detection

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.util.Log
import java.util.EnumSet

/** Общий OrtEnvironment + фабрика сессий с fallback QNN→XNNPACK. */
object OrtManager {

    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * Пробует создать сессию: сначала QNN HTP, затем XNNPACK.
     * После создания делает warm-up прогон чтобы убедиться что NPU реально работает:
     * если инференс > [cpuFallbackMs] — считаем что QNN упал на CPU и пробуем XNNPACK.
     */
    fun createSession(bytes: ByteArray, tag: String): OrtSession {
        return tryQnn(bytes, tag)
            ?: tryXnnpack(bytes, tag)
            ?: tryCpu(bytes, tag)
            ?: error("[$tag] All EPs failed")
    }

    private fun tryQnn(bytes: ByteArray, tag: String): OrtSession? {
        return try {
            val opts = OrtSession.SessionOptions().apply {
                addQnn(mapOf(
                    "backend_path"             to "libQnnHtp.so",
                    "htp_performance_mode"     to "burst",
                    "enable_htp_fp16_precision" to "1"
                ))
            }
            val session = env.createSession(bytes, opts)

            // Warm-up: проверяем что реально идёт на NPU, а не CPU-fallback.
            // Первый прогон включает финализацию/компиляцию графа HTP (~200мс),
            // поэтому делаем несколько прогонов и оцениваем скорость по МИНИМУМУ —
            // он отражает реальный установившийся инференс, а не время компиляции.
            val inputName  = session.inputNames.first()
            val inputShape = session.inputInfo[inputName]!!.info as? ai.onnxruntime.TensorInfo
            if (inputShape != null) {
                val dims = inputShape.shape
                val n    = dims.fold(1L, Long::times).toInt()
                val warmup = ai.onnxruntime.OnnxTensor.createTensor(
                    env,
                    java.nio.FloatBuffer.allocate(n),
                    dims
                )
                var best = Long.MAX_VALUE
                repeat(WARMUP_RUNS) {
                    val t0 = System.currentTimeMillis()
                    session.run(mapOf(inputName to warmup)).close()
                    best = minOf(best, System.currentTimeMillis() - t0)
                }
                warmup.close()
                Log.i("OrtManager", "[$tag] QNN warmup best of $WARMUP_RUNS: ${best}ms")
                if (best > CPU_THRESHOLD_MS) {
                    Log.w("OrtManager", "[$tag] QNN too slow (${best}ms > ${CPU_THRESHOLD_MS}ms) — not on NPU, falling back")
                    session.close()
                    return null
                }
            }
            Log.i("OrtManager", "[$tag] QNN HTP OK")
            session
        } catch (e: Exception) {
            Log.w("OrtManager", "[$tag] QNN failed: ${e.message}")
            null
        }
    }

    private fun tryXnnpack(bytes: ByteArray, tag: String): OrtSession? {
        return try {
            val opts = OrtSession.SessionOptions().apply {
                addXnnpack(emptyMap())
                setIntraOpNumThreads(4)
            }
            env.createSession(bytes, opts).also {
                Log.i("OrtManager", "[$tag] XNNPACK OK")
            }
        } catch (e: Exception) {
            Log.w("OrtManager", "[$tag] XNNPACK failed: ${e.message}")
            null
        }
    }

    private fun tryCpu(bytes: ByteArray, tag: String): OrtSession? {
        return try {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
            }
            env.createSession(bytes, opts).also {
                Log.i("OrtManager", "[$tag] CPU fallback OK")
            }
        } catch (e: Exception) {
            Log.w("OrtManager", "[$tag] CPU fallback failed: ${e.message}")
            null
        }
    }

    // Первая сессия реально идёт на HTP (~79ms). Вторая под конкуренцией HTP получает
    // QNN_DEVICE_ERROR_INVALID_CONFIG и тихо исполняется на CPU внутри QNN-обёртки (~114ms) —
    // её мы отбраковываем и отдаём чистому CPU EP (tryCpu).
    private const val CPU_THRESHOLD_MS = 60L   // установившийся HTP-инференс ~20-30мс; CPU > 100мс
    private const val WARMUP_RUNS      = 3     // первый прогон = компиляция графа, меряем минимум
}
