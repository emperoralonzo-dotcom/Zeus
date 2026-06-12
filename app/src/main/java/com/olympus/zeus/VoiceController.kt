package com.olympus.zeus

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Gives Zeus a voice using the phone's built-in text-to-speech (works offline). */
class VoiceController(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled = true

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setPitch(0.8f)
                tts?.setSpeechRate(0.97f)
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zeus")
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
