package com.tennis.analyzer.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Реклама AdMob для бесплатной версии: согласие (UMP) → инициализация →
 * межстраничная реклама после анализа (с ограничением по частоте).
 *
 * ⚠️ ID ниже — ТЕСТОВЫЕ (Google). Замени на свои из AdMob перед публикацией.
 */
object AdManager {

    // TODO: заменить на реальные ad-unit из своего AdMob-аккаунта
    private const val INTERSTITIAL_TEST = "ca-app-pub-3940256099942544/1033173712"
    const val BANNER_TEST = "ca-app-pub-3940256099942544/6300978111"

    private const val MIN_GAP_MS = 90_000L   // не чаще раза в 1.5 минуты

    private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var lastShownMs = 0L
    private var loading = false

    /** Вызвать один раз при старте: запросить согласие, затем инициализировать рекламу. */
    fun start(activity: Activity) {
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        consent.requestConsentInfoUpdate(
            activity, params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    if (consent.canRequestAds()) initAds(activity)
                }
            },
            { err ->
                Log.w(TAG, "Consent update failed: ${err.message}")
                // Всё равно инициализируем (неперсонализированная реклама)
                initAds(activity)
            }
        )
        // Если согласие уже получено ранее — можно грузить сразу
        if (consent.canRequestAds()) initAds(activity)
    }

    private fun initAds(context: Context) {
        if (!initialized) {
            initialized = true
            MobileAds.initialize(context) {}
        }
        loadInterstitial(context)
    }

    private fun loadInterstitial(context: Context) {
        if (loading || interstitial != null) return
        loading = true
        InterstitialAd.load(
            context, INTERSTITIAL_TEST, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; loading = false }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Interstitial load failed: ${error.message}"); interstitial = null; loading = false
                }
            }
        )
    }

    /** Показать межстраничную, если реклама готова и прошёл интервал. Затем подгрузить следующую. */
    fun maybeShowInterstitial(activity: Activity) {
        val ad = interstitial ?: run { loadInterstitial(activity); return }
        if (System.currentTimeMillis() - lastShownMs < MIN_GAP_MS) return
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; loadInterstitial(activity) }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { interstitial = null; loadInterstitial(activity) }
        }
        lastShownMs = System.currentTimeMillis()
        ad.show(activity)
    }

    private const val TAG = "AdManager"
}
