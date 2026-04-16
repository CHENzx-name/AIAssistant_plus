package com.example.aiassistant.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Lightweight TextToSpeech wrapper with delayed first utterance support.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var initialized = false
    private var pendingText: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: status=$status")
            return
        }

        val engine = tts ?: return
        initialized = true

        val zhResult = engine.setLanguage(Locale.CHINA)
        if (zhResult == TextToSpeech.LANG_MISSING_DATA || zhResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }

        pendingText?.let {
            speakInternal(it)
            pendingText = null
        }
    }

    fun speak(rawText: String) {
        val normalized = normalizeText(rawText)
        if (normalized.isBlank()) {
            return
        }

        if (!initialized) {
            pendingText = normalized
            return
        }

        speakInternal(normalized)
    }

    private fun speakInternal(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_reply")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
        pendingText = null
    }

    private fun normalizeText(text: String): String {
        return text
            .replace("```", "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}