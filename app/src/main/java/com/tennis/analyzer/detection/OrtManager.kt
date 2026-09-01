package com.tennis.analyzer.detection

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

/** Общий OrtEnvironment + фабрика сессий с fallback QNN→CPU. */
object OrtManager {

    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * Пробует создать сессию: сначала QNN HTP, иначе CPU.
     * После создания QNN-сессии делает warm-up прогон чтобы убедиться что NPU реально
     * работает: если инференс > [CPU_THRESHOLD_MS] — считаем что QNN упал на CPU и
     * пересоздаём сессию на чистом CPU EP.
     *
     * XNNPACK сюда сознательно не включён: используемая сборка
     * com.microsoft.onnxruntime:onnxruntime-android-qnn не компилирует в себя
     * XnnpackExecutionProvider (это QNN-специфичная сборка ORT — XNNPACK есть только
     * в обычной onnxruntime-android). Проверено через `strings`/`nm` на реальном .so:
     * "XnnpackExecutionProvider" встречается лишь в таблице сообщений об ошибках
     * ("... execution provider is not supported in this build"), а не как
     * зарегистрированный provider. Попытка addXnnpack(...) в этой сборке гарантированно
     * и всегда проваливается — раньше это выполнялось на КАЖДОМ откате на CPU (то есть
     * почти на каждом устройстве без реально работающего QNN NPU), впустую тратя время
     * и засоряя логи. Если когда-нибудь перейдём на обычную onnxruntime-android (без
     * QNN) или на сборку с обоими EP — можно будет вернуть XNNPACK как отдельную ступень.
     */
    fun createSession(bytes: ByteArray, tag: String): OrtSession {
        return tryQnn(bytes, tag)
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

    // Порог откалиброван на устройстве с реально работающим HTP (Snapdragon 8 Gen на
    // флагмане): установившийся HTP-инференс там ~20-30мс, CPU-инференс >100мс — порог
    // 60мс уверенно разделяет эти два случая.
    //
    // На реальном Snapdragon 865 (Hexagon 698, HTP v66) второй createSession для того же
    // тега конкурирует за уже занятый QNN-бэкенд и падает на createSession/GetCapability с
    // QNN_BACKEND_ERROR_CANNOT_INITIALIZE ("Failed to initialize logging in the QNN
    // backend") — это ловит catch(Exception) в tryQnn() и корректно откатывается на CPU,
    // никакого отдельного обращения не требуется. Там же первая (неконкурентная) QNN-сессия
    // всё равно не уложилась в порог (103–214мс) — то есть на этом чипе QNN HTP просто не
    // достигает NPU-скорости для наших моделей, независимо от конкуренции за бэкенд.
    private const val CPU_THRESHOLD_MS = 60L   // установившийся HTP-инференс ~20-30мс; CPU > 100мс
    private const val WARMUP_RUNS      = 3     // первый прогон = компиляция графа, меряем минимум
}
