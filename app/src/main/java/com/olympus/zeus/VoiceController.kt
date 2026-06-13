package com.olympus.zeus

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Gives each god its own voice using the phone's built-in text-to-speech (offline).
 * The device usually ships several English voices; we assign a DISTINCT one to each
 * god so they don't all sound alike, then tune pitch/rate on top. More voices
 * installed on the device = more clearly different gods.
 */
class VoiceController(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled = true

    private var pendingPitch = 0.9f
    private var pendingRate = 0.95f
    private var pendingVoice: Voice? = null

    // Stable seat per god, so each gets a different voice from the pool.
    private val seat = mapOf("Zeus" to 0, "Athena" to 1, "Hermes" to 2, "Apollo" to 3)
    private var pool: List<Voice> = emptyList()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                buildPool()
                ready = true
                // Give Zeus his seat immediately.
                setGod("Zeus", 0.85f, 0.94f)
            }
        }
    }

    /** Distinct, offline English voices on this device, best quality first. */
    private fun buildPool() {
        pool = try {
            tts?.voices
                ?.filter { it.locale?.language == "en" && !it.isNetworkConnectionRequired }
                ?.distinctBy { it.name }
                ?.sortedByDescending { it.quality }
                ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    /** Assign a god its own voice + tone. Falls back gracefully if few voices exist. */
    fun setGod(name: String, pitch: Float, rate: Float) {
        pendingPitch = pitch
        pendingRate = rate
        if (pool.isNotEmpty()) {
            val idx = (seat[name] ?: 0) % pool.size
            pendingVoice = pool[idx]
        }
        apply()
    }

    private fun apply() {
        if (!ready) return
        pendingVoice?.let { tts?.voice = it }
        tts?.setPitch(pendingPitch)
        tts?.setSpeechRate(pendingRate)
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
