package com.tennis.analyzer.detection

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log

/** Общий OrtEnvironment + фабрика сессий с fallback QNN→CPU. */
object OrtManager {

    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * Пробует создать сессию: сначала QNN HTP (Snapdragon 8 Gen1+, Hexagon v68+),
     * затем QNN DSP (старые чипы вроде Snapdragon 855/865, Hexagon v66), иначе CPU.
     * После создания QNN-сессии делает warm-up прогон чтобы убедиться что NPU реально
     * работает: если инференс > [CPU_THRESHOLD_MS] — считаем что EP исполняется на CPU
     * внутри QNN-обёртки и пересоздаём сессию на следующей ступени.
     *
     * Почему два разных QNN-бэкенда: QNN SDK (com.qualcomm.qti:qnn-runtime, транзитивная
     * зависимость ORT-QNN AAR) поставляет для нового "Htp"-бэкенда skeleton-библиотеки
     * только под Hexagon v68+ (Snapdragon 888 / 8 Gen 1 и новее) — libQnnHtpV66*.so не
     * существует в природе (проверено распаковкой полного qnn-runtime-2.42.0.aar). Для
     * v66 и старее у Qualcomm есть только отдельный, более старый "Dsp"-бэкенд
     * (libQnnDsp.so + libQnnDspV66Skel/Stub.so) — другой .so, другой набор опций
     * (никаких htp_performance_mode/enable_htp_fp16_precision, они Htp-специфичны).
     * На Hexagon v66 попытка через "Htp" создаёт сессию без ошибки (сама libQnnHtp.so
     * грузится), но реального исполнения на DSP не происходит — инференс идёт на
     * ~уровне CPU (100–300мс на Snapdragon 865); warm-up-проверка это ловит и мы
     * переходим к попытке через "Dsp".
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
        return tryQnnHtp(bytes, tag)
            ?: tryQnnDsp(bytes, tag)
            ?: tryCpu(bytes, tag)
            ?: error("[$tag] All EPs failed")
    }

    private fun tryQnnHtp(bytes: ByteArray, tag: String): OrtSession? =
        tryQnnBackend(
            bytes, tag,
            backend = "HTP",
            options = mapOf(
                "backend_path"              to "libQnnHtp.so",
                "htp_performance_mode"      to "burst",
                "enable_htp_fp16_precision" to "1"
            )
        )

    private fun tryQnnDsp(bytes: ByteArray, tag: String): OrtSession? =
        tryQnnBackend(
            bytes, tag,
            backend = "DSP",
            // Старый бэкенд для Hexagon v66 (Snapdragon 855/865 и т.п.) — Htp-специфичные
            // опции (htp_performance_mode, enable_htp_fp16_precision) сюда не подходят.
            options = mapOf("backend_path" to "libQnnDsp.so")
        )

    /**
     * Общая логика для обоих QNN-бэкендов: создать сессию, прогнать warm-up и
     * отбраковать по скорости если EP на самом деле молча исполняется на CPU.
     */
    private fun tryQnnBackend(
        bytes: ByteArray,
        tag: String,
        backend: String,
        options: Map<String, String>
    ): OrtSession? {
        return try {
            val opts = OrtSession.SessionOptions().apply { addQnn(options) }
            val session = env.createSession(bytes, opts)

            // Warm-up: проверяем что реально идёт на NPU, а не CPU-fallback.
            // Первый прогон включает финализацию/компиляцию графа (~200мс),
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
                Log.i("OrtManager", "[$tag] QNN $backend warmup best of $WARMUP_RUNS: ${best}ms")
                if (best > CPU_THRESHOLD_MS) {
                    Log.w("OrtManager", "[$tag] QNN $backend too slow (${best}ms > ${CPU_THRESHOLD_MS}ms) — not on NPU, falling back")
                    session.close()
                    return null
                }
            }
            Log.i("OrtManager", "[$tag] QNN $backend OK")
            session
        } catch (e: Exception) {
            Log.w("OrtManager", "[$tag] QNN $backend failed: ${e.message}")
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

    // Порог общий для обоих QNN-бэкендов (Htp и Dsp). Откалиброван на устройстве с
    // реально работающим HTP (Snapdragon 8 Gen на флагмане): установившийся HTP-инференс
    // там ~20-30мс, CPU-инференс >100мс — порог 60мс уверенно разделяет эти два случая.
    // Для DSP-бэкенда (Hexagon v66) абсолютная скорость NPU ниже (старее поколение), но
    // порог осознанно не завышаем отдельно под него: если реальный DSP-инференс на v66
    // окажется медленнее 60мс, откат на честный CPU EP — это безопасный результат, а не
    // потеря производительности (DSP там не был бы намного быстрее CPU в любом случае).
    //
    // На реальном Snapdragon 865 (Hexagon 698, HTP v66) второй createSession для того же
    // тега конкурирует за уже занятый QNN-бэкенд и падает на createSession/GetCapability с
    // QNN_BACKEND_ERROR_CANNOT_INITIALIZE ("Failed to initialize logging in the QNN
    // backend") — это ловит catch(Exception) в tryQnnBackend() и корректно откатывается
    // на следующую ступень, никакого отдельного обращения не требуется. Там же первая
    // (неконкурентная) HTP-сессия всё равно не уложилась в порог (103–214мс) — то есть
    // "Htp"-бэкенд на этом чипе физически не достигает NPU-скорости (см. большой
    // комментарий у createSession), для реального ускорения нужен именно "Dsp"-бэкенд.
    private const val CPU_THRESHOLD_MS = 60L   // установившийся HTP-инференс ~20-30мс; CPU > 100мс
    private const val WARMUP_RUNS      = 3     // первый прогон = компиляция графа, меряем минимум
}
