package com.tennis.analyzer.wear

/** Пути сообщений Data Layer между часами и телефоном (должны совпадать с телефоном). */
object WearComm {
    const val PATH_START    = "/serve/start"    // часы → телефон: начать запись
    const val PATH_STOP     = "/serve/stop"     // часы → телефон: остановить запись
    const val PATH_MODE     = "/serve/mode"     // часы → телефон: "ANALYSIS" | "REALTIME"
    const val PATH_PROGRESS = "/serve/progress" // телефон → часы: "0".."100"
    const val PATH_RESULT   = "/serve/result"   // телефон → часы: "score|tip" (score<0 = не распознана)
}
