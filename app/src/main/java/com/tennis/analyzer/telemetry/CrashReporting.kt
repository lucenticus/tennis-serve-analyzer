package com.tennis.analyzer.telemetry

import android.content.Context
import android.util.Log
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig

/**
 * Сбор крашей и базовой телеметрии через AppMetrica (Yandex).
 *
 * Выбран вместо Firebase Crashlytics: работает без VPN у российской аудитории
 * RuStore (Firebase/Google-сервисы у части пользователей нестабильны без VPN).
 *
 * ⚠️ [API_KEY] — плейсхолдер. Получить свой ключ:
 *   https://appmetrica.yandex.ru/ → создать приложение → скопировать API-ключ.
 * Без реального ключа AppMetrica просто не отправляет данные (не крашит приложение),
 * так что сборка безопасна и без ключа — но крашей ты не увидишь.
 */
object CrashReporting {

    // TODO: заменить на реальный API-ключ из личного кабинета AppMetrica
    private const val API_KEY = "00000000-0000-0000-0000-000000000000"

    private const val TAG = "CrashReporting"

    fun init(context: Context) {
        if (API_KEY.startsWith("00000000")) {
            Log.w(TAG, "AppMetrica API_KEY не задан — телеметрия отключена (см. CrashReporting.kt)")
            return
        }
        val config = AppMetricaConfig.newConfigBuilder(API_KEY)
            .withCrashReporting(true)     // автоматический сбор крашей и ANR
            .build()
        AppMetrica.activate(context.applicationContext, config)
        AppMetrica.enableActivityAutoTracking(context.applicationContext as android.app.Application)
    }

    /** Нефатальная ошибка — для мест, где мы ловим Exception и хотим знать, что это случалось. */
    fun reportError(message: String, throwable: Throwable? = null) {
        runCatching { AppMetrica.reportError(message, throwable) }
    }
}
