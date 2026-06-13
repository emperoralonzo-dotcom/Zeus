package com.olympus.zeus

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Each god gets its own device voice plus a saved pitch/rate. The menu can nudge a god
 * deeper/higher and slower/faster, cycle to a different installed voice, test it, or reset.
 * Every change is remembered per god. (Android has no gender field, so "male/female" is
 * a matter of which installed voice you cycle to, not something we can auto-pick.)
 */
class VoiceController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("zeus_voices", Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var ready = false
    var enabled = true

    private var current = "Zeus"
    private var pendingPitch = 0.78f
    private var pendingRate = 0.90f

    // Remember each god's BASE tone so "reset" can return to it.
    private val baseP = HashMap<String, Float>()
    private val baseR = HashMap<String, Float>()

    private val seat = mapOf("Zeus" to 0, "Athena" to 1, "Hermes" to 2, "Apollo" to 3)
    private var pool: List<Voice> = emptyList()

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                buildPool()
                ready = true
                setGod("Zeus", 0.78f, 0.90f)
            }
        }
    }

    private fun buildPool() {
        pool = try {
            tts?.voices
                ?.filter { it.locale?.language == "en" && !it.isNetworkConnectionRequired }
                ?.distinctBy { it.name }
                ?.sortedByDescending { it.quality }
                ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun voiceIndex(name: String): Int {
        val saved = prefs.getInt("v_$name", -1)
        return if (saved >= 0) saved else (seat[name] ?: 0)
    }

    /** Activate a god with its saved (or base) voice + tone. */
    fun setGod(name: String, basePitch: Float, baseRate: Float) {
        current = name
        baseP[name] = basePitch; baseR[name] = baseRate
        pendingPitch = prefs.getFloat("p_$name", basePitch)
        pendingRate = prefs.getFloat("r_$name", baseRate)
        apply(name)
    }

    fun deeper(name: String): String = bumpPitch(name, -0.06f)
    fun higher(name: String): String = bumpPitch(name, +0.06f)
    fun slower(name: String): String = bumpRate(name, -0.06f)
    fun faster(name: String): String = bumpRate(name, +0.06f)

    private fun bumpPitch(name: String, d: Float): String {
        pendingPitch = (pendingPitch + d).coerceIn(0.5f, 1.6f)
        prefs.edit().putFloat("p_$name", pendingPitch).apply()
        apply(name)
        return "pitch ${"%.2f".format(pendingPitch)}"
    }

    private fun bumpRate(name: String, d: Float): String {
        pendingRate = (pendingRate + d).coerceIn(0.6f, 1.4f)
        prefs.edit().putFloat("r_$name", pendingRate).apply()
        apply(name)
        return "speed ${"%.2f".format(pendingRate)}"
    }

    /** Step to the next installed voice for this god; remembered. */
    fun cycleVoice(name: String): String {
        if (pool.size < 2) return "no other voices installed"
        val next = (voiceIndex(name) + 1) % pool.size
        prefs.edit().putInt("v_$name", next).apply()
        apply(name)
        return "voice ${next + 1} of ${pool.size}"
    }

    /** Forget tweaks for this god — back to its default voice and tone. */
    fun resetGod(name: String): String {
        prefs.edit().remove("p_$name").remove("r_$name").remove("v_$name").apply()
        pendingPitch = baseP[name] ?: 0.9f
        pendingRate = baseR[name] ?: 0.95f
        apply(name)
        return "reset"
    }

    private fun apply(name: String) {
        if (!ready) return
        if (pool.isNotEmpty()) tts?.voice = pool[voiceIndex(name) % pool.size]
        tts?.setPitch(pendingPitch)
        tts?.setSpeechRate(pendingRate)
    }

    fun speak(text: String) {
        if (!enabled || !ready || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zeus")
    }

    fun stop() { tts?.stop() }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
