package com.tennis.analyzer.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Принимает команды с часов (START/STOP/MODE) и передаёт их в MainActivity через [WearLink.onCommand].
 * Запись/реал-тайм идут в MainActivity, поэтому приложение должно быть открыто.
 */
class ServeWearListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearLink.PATH_START, WearLink.PATH_STOP, WearLink.PATH_MODE -> {
                val payload = String(event.data)
                Log.i(TAG, "Wear command: ${event.path} '$payload'")
                WearLink.onCommand?.invoke(event.path, payload)
            }
        }
    }

    private companion object {
        const val TAG = "ServeWearListener"
    }
}
