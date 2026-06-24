package com.tennis.analyzer.wear

/** Пути сообщений Data Layer между часами и телефоном (должны совпадать с телефоном). */
object WearComm {
    const val PATH_START  = "/serve/start"    // часы → телефон: начать запись
    const val PATH_STOP   = "/serve/stop"     // часы → телефон: остановить запись
    const val PATH_RESULT = "/serve/result"   // телефон → часы: "score|tip"
}
