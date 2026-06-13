package com.olympus.zeus

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Gives Zeus a voice using the phone's built-in text-to-speech (works offline). */
class VoiceController(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled = true

    private var pendingPitch = 0.62f
    private var pendingRate = 0.88f

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setPitch(pendingPitch)
                tts?.setSpeechRate(pendingRate)
                ready = true
            }
        }
    }

    /** Switch the voice to match the chosen god (deeper Zeus, brighter Hermes, etc.). */
    fun setVoice(pitch: Float, rate: Float) {
        pendingPitch = pitch; pendingRate = rate
        if (ready) { tts?.setPitch(pitch); tts?.setSpeechRate(rate) }
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
