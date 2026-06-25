package com.tennis.analyzer.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable

/**
 * Мост между телефоном и часами (Wear OS компаньон, режим «пульт + дисплей»).
 * - Часы шлют команды (START/STOP/MODE) → сюда → MainActivity их исполняет ([onCommand]).
 * - Во время анализа MainActivity шлёт [sendProgress], в конце — [sendResult].
 */
object WearLink {

    const val PATH_START    = "/serve/start"
    const val PATH_STOP     = "/serve/stop"
    const val PATH_MODE     = "/serve/mode"      // payload: "ANALYSIS" | "REALTIME"
    const val PATH_PROGRESS = "/serve/progress"  // payload: "0".."100"
    const val PATH_RESULT   = "/serve/result"    // payload: "score|tip" (score<0 = подача не распознана)

    /** Установить из MainActivity. Получает (path, payload) команды с часов. */
    @Volatile
    var onCommand: ((String, String) -> Unit)? = null

    /** Процент прогресса анализа на часы (0..100). */
    fun sendProgress(context: Context, percent: Int) {
        send(context, PATH_PROGRESS, percent.coerceIn(0, 100).toString())
    }

    /** Итог анализа на часы. score<0 → «подача не распознана». Всегда вызывать в конце анализа. */
    fun sendResult(context: Context, score: Int, tip: String?) {
        send(context, PATH_RESULT, "$score|${tip ?: ""}")
    }

    private fun send(context: Context, path: String, payload: String) {
        val appContext = context.applicationContext
        val bytes = payload.toByteArray()
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                val msg = Wearable.getMessageClient(appContext)
                for (n in nodes) {
                    msg.sendMessage(n.id, path, bytes)
                        .addOnFailureListener { e -> Log.w(TAG, "send $path to ${n.id} failed: ${e.message}") }
                }
                if (path != PATH_PROGRESS) Log.i(TAG, "sent $path='$payload' to ${nodes.size} node(s)")
            }
            .addOnFailureListener { e -> Log.w(TAG, "connectedNodes failed: ${e.message}") }
    }

    private const val TAG = "WearLink"
}
