package com.tennis.analyzer.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable

/**
 * Мост между телефоном и часами (Wear OS компаньон, режим «пульт + дисплей»).
 * - Часы шлют START/STOP → сюда приходит команда → MainActivity её исполняет ([onCommand]).
 * - После анализа MainActivity вызывает [sendResult] → оценка+совет уходят на часы.
 */
object WearLink {

    const val PATH_START  = "/serve/start"
    const val PATH_STOP   = "/serve/stop"
    const val PATH_RESULT = "/serve/result"

    /** Установить из MainActivity. Получает путь команды (PATH_START/PATH_STOP). */
    @Volatile
    var onCommand: ((String) -> Unit)? = null

    /** Отправить результат подачи на все подключённые часы. Без часов — просто no-op. */
    fun sendResult(context: Context, score: Int, tip: String?) {
        val payload = "$score|${tip ?: ""}".toByteArray()
        val appContext = context.applicationContext
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                val msg = Wearable.getMessageClient(appContext)
                for (n in nodes) {
                    msg.sendMessage(n.id, PATH_RESULT, payload)
                        .addOnFailureListener { e -> Log.w(TAG, "sendResult to ${n.id} failed: ${e.message}") }
                }
                Log.i(TAG, "sendResult score=$score to ${nodes.size} node(s)")
            }
            .addOnFailureListener { e -> Log.w(TAG, "connectedNodes failed: ${e.message}") }
    }

    private const val TAG = "WearLink"
}
