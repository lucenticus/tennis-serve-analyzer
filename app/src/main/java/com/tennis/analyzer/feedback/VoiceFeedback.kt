package com.tennis.analyzer.feedback

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.tennis.analyzer.analysis.ServeAdvice
import java.util.Locale
import java.util.UUID

class VoiceFeedback(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var isSpeaking = false

    /** Глобальный выключатель голоса (из настроек). */
    var enabled = true

    private val recentAdvice = mutableMapOf<String, Int>()
    private var serveCount = 0

    // Движки TTS в порядке приоритета
    private val preferredEngines = listOf(
        "com.google.android.tts",           // Google TTS
        "com.samsung.SMT",                  // Samsung TTS (резерв)
        "com.svox.pico",                    // Pico TTS (резерв)
    )

    fun init(onReady: () -> Unit) {
        tryNextEngine(preferredEngines, onReady)
    }

    private fun tryNextEngine(engines: List<String>, onReady: () -> Unit) {
        if (engines.isEmpty()) {
            // Все движки не подошли — попробуем системный по умолчанию без указания движка
            Log.w(TAG, "All preferred engines failed, trying system default")
            initWithEngine(null, onReady) {}
            return
        }

        val engine = engines.first()
        val rest = engines.drop(1)

        initWithEngine(engine, onReady) {
            Log.w(TAG, "$engine unavailable, trying next")
            tryNextEngine(rest, onReady)
        }
    }

    private fun initWithEngine(engine: String?, onReady: () -> Unit, onFail: () -> Unit) {
        tts?.shutdown()

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Язык озвучки = язык интерфейса (советы уже локализованы: ru или en по умолчанию)
                val ttsLocale = if (Locale.getDefault().language == "ru") Locale("ru", "RU") else Locale.ENGLISH
                val langResult = tts?.setLanguage(ttsLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "${engine ?: "default"}: $ttsLocale not supported")
                    onFail()
                    return@OnInitListener
                }

                Log.i(TAG, "TTS ready: ${engine ?: "system default"}")
                tts?.setSpeechRate(0.88f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isSpeaking = true }
                    override fun onDone(utteranceId: String?) { isSpeaking = false }
                    override fun onError(utteranceId: String?) { isSpeaking = false }
                })
                isReady = true
                onReady()
            } else {
                Log.w(TAG, "${engine ?: "default"} init failed with status $status")
                onFail()
            }
        }

        tts = if (engine != null) {
            TextToSpeech(context, listener, engine)
        } else {
            TextToSpeech(context, listener)
        }
    }

    fun speak(advice: List<ServeAdvice>) {
        if (!enabled || !isReady || isSpeaking) return
        serveCount++

        val toSpeak = advice.firstOrNull { a ->
            (recentAdvice[a.textRu] ?: 0) < serveCount - REPEAT_COOLDOWN
        } ?: return

        recentAdvice[toSpeak.textRu] = serveCount
        sayText(toSpeak.textRu)
    }

    fun speakScore(score: Float) {
        if (!enabled || !isReady || isSpeaking) return
        sayText(context.getString(com.tennis.analyzer.R.string.voice_score, score.toInt()))
    }

    /** Произносит текст немедленно, прерывая текущую речь (для системных уведомлений) */
    fun speakImmediate(text: String) {
        if (!enabled || !isReady) return
        val id = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun sayText(text: String) {
        val id = UUID.randomUUID().toString()
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR for: $text")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val TAG = "VoiceFeedback"
        private const val REPEAT_COOLDOWN = 3
    }
}
