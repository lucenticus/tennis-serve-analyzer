package com.tennis.analyzer.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Принимает команды START/STOP с часов и передаёт их в MainActivity через [WearLink.onCommand].
 * Запись идёт камерой в MainActivity, поэтому приложение должно быть открыто (камера активна).
 */
class ServeWearListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearLink.PATH_START, WearLink.PATH_STOP -> {
                Log.i(TAG, "Wear command: ${event.path}")
                WearLink.onCommand?.invoke(event.path)
            }
        }
    }

    private companion object {
        const val TAG = "ServeWearListener"
    }
}
