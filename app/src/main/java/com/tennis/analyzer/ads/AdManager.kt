package com.tennis.analyzer.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.InitializationListener
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader

/**
 * Реклама Yandex Ads для бесплатной версии: инициализация → межстраничная
 * реклама после анализа (с ограничением по частоте).
 *
 * Выбран вместо Google AdMob: Google не создаёт новые AdMob-аккаунты из России
 * (санкции OFAC), а основной рынок приложения — RuStore. Yandex Ads работает
 * без VPN и без ограничений для российских разработчиков.
 *
 * Класс/методы сверены напрямую по .aar SDK 8.4.0 (javap), т.к. официальная
 * документация Yandex местами описывает устаревший API (напр. несуществующий
 * MobileAds.initialize / AdRequestConfiguration).
 *
 * ID ниже — реальные ad unit из кабинета рекламной сети Яндекса (ads.yandex.ru).
 */
object AdManager {

    // Реклама временно отключена целиком (по просьбе во время UX-правок — интерстишиал
    // после каждого возврата с экрана анализа постоянно перебивал ручное тестирование
    // интерфейса). Чтобы вернуть рекламу — просто верни true.
    const val ADS_ENABLED = false

    private const val INTERSTITIAL_UNIT_ID = "R-M-19853025-2"
    const val BANNER_UNIT_ID = "R-M-19853025-1"

    private const val MIN_GAP_MS = 90_000L   // не чаще раза в 1.5 минуты

    private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var lastShownMs = 0L
    private var loading = false

    /** Вызвать один раз при старте: инициализировать SDK и подгрузить первую рекламу. */
    fun start(activity: Activity) {
        if (!ADS_ENABLED) return
        if (initialized) { loadInterstitial(activity); return }
        initialized = true
        YandexAds.initialize(activity, object : InitializationListener {
            override fun onInitializationCompleted() {
                loadInterstitial(activity)
            }
        })
    }

    private fun loadInterstitial(context: Context) {
        if (loading || interstitial != null) return
        loading = true
        val request = AdRequest.Builder(INTERSTITIAL_UNIT_ID).build()
        InterstitialAdLoader(context).loadAd(request, object : InterstitialAdLoadListener {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitial = ad
                loading = false
            }
            override fun onAdFailedToLoad(error: AdRequestError) {
                Log.w(TAG, "Interstitial load failed: ${error.description}")
                interstitial = null
                loading = false
            }
        })
    }

    /** Показать межстраничную, если реклама готова и прошёл интервал. Затем подгрузить следующую. */
    fun maybeShowInterstitial(activity: Activity) {
        if (!ADS_ENABLED) return
        val ad = interstitial ?: run { loadInterstitial(activity); return }
        if (System.currentTimeMillis() - lastShownMs < MIN_GAP_MS) return
        ad.setAdEventListener(object : InterstitialAdEventListener {
            override fun onAdShown() {}
            override fun onAdFailedToShow(error: AdError) { interstitial = null; loadInterstitial(activity) }
            override fun onAdDismissed() { interstitial = null; loadInterstitial(activity) }
            override fun onAdClicked() {}
            override fun onAdImpression(impressionData: ImpressionData?) {}
        })
        lastShownMs = System.currentTimeMillis()
        ad.show(activity)
    }

    private const val TAG = "AdManager"
}
