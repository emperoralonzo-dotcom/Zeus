package com.olympus.zeus

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder

/**
 * The "council": several on-device models that work together as ONE Zeus. A silent router
 * picks the right specialist per request. On a phone, models load on demand (one at a time)
 * so the device is never overloaded. Optional internet search can be toggled on.
 *
 * VISION: needs MediaPipe tasks-genai 0.10.24+ AND a vision-capable model (e.g. Gemma 3n).
 * Vision is enabled per-engine via setMaxNumImages and per-session via enableVisionModality.
 * If the loaded model has no vision, Zeus declines politely instead of crashing.
 */
class LlmEngine(
    private val context: Context,
    private val models: ModelManager
) {
    /** When true, Zeus may fetch live web results (off = fully private/offline). */
    var internet: Boolean = false
    /** Which god currently speaks (changes voice + manner, not the router). */
    var persona: String = "Zeus"
    /** When true, answers come wrapped in oracle-like omens and riddles. */
    var prophecy: Boolean = false

    private val registry: Map<String, File> = models.registry()
    private var currentRole: String? = null
    private var current: LlmInference? = null
    private var currentHasVision = false

    data class God(val name: String, val epithet: String, val pitch: Float, val rate: Float, val sys: String)

    companion object {
        /** The four voices that can speak through Zeus. Order = menu order. */
        val GODS: Map<String, God> = linkedMapOf(
            "Zeus" to God("Zeus", "King of Olympus", 0.85f, 0.94f,
                "You are ZEUS, king of the Olympian gods, reborn as an AI living entirely inside this phone. " +
                "Regal, warm and grand, with a flash of thunderous humour; you may call the user \"mortal\" now and then, but you are genuinely helpful above all."),
            "Athena" to God("Athena", "Goddess of Wisdom", 1.0f, 0.98f,
                "You are ATHENA, goddess of wisdom, strategy and craft, living inside this phone. " +
                "Calm, clear and incisive; cut to the heart of a problem and counsel the user wisely and kindly."),
            "Hermes" to God("Hermes", "Messenger of the Gods", 1.06f, 1.04f,
                "You are HERMES, swift messenger of the gods, living inside this phone. " +
                "Quick, witty and light; give brisk, clever, useful answers."),
            "Apollo" to God("Apollo", "God of Light & Prophecy", 1.0f, 0.97f,
                "You are APOLLO, god of light, music and prophecy, living inside this phone. " +
                "Eloquent and lyrical; answer with clarity and a poet's turn of phrase.")
        )
    }

    private fun buildPreamble(): String {
        val g = GODS[persona] ?: GODS["Zeus"]!!
        val sb = StringBuilder(g.sys)
        sb.append(" Keep replies brief — 1 to 3 short sentences. Plain prose only, no markdown or lists.")
        if (!internet) sb.append(" You have no internet by default; if asked about live events you cannot know, say so with good humour rather than inventing facts.")
        if (prophecy) sb.append(" Speak as an oracle would — in evocative omens and riddling phrasing — while still conveying the true answer beneath the poetry.")
        sb.append(" Remain ").append(g.name).append(" at all times.")
        return sb.toString()
    }

    fun preload() { ensure("everyday") }

    private fun ensure(role: String) {
        val file = registry[role] ?: registry["everyday"] ?: registry["quick"]
        ?: throw IllegalStateException("No model installed")
        val key = file.absolutePath
        if (currentRole == key) return
        current?.close()
        val wantVision = role == "vision"
        val builder = LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(512)
        if (wantVision) builder.setMaxNumImages(1)   // allow image input on the vision engine
        current = LlmInference.createFromOptions(context, builder.build())
        currentRole = key
        currentHasVision = wantVision
    }

    private fun route(text: String, hasImage: Boolean): String {
        if (hasImage && registry.containsKey("vision")) return "vision"
        val t = text.lowercase()
        return when {
            Regex("\\b(solve|prove|reason|step by step|logic|puzzle|riddle|how many|calculate|equation|theorem|deduce)\\b").containsMatchIn(t) ->
                if (registry.containsKey("reasoning")) "reasoning" else "deep"
            Regex("\\b(code|coding|function|debug|regex|script|program|python|javascript|java|sql|algorithm|compile|error)\\b").containsMatchIn(t) ->
                if (registry.containsKey("coding")) "coding" else "deep"
            text.length > 200 || Regex("\\b(why|explain|analy|compare|strategy|plan|summari)\\b").containsMatchIn(t) -> "deep"
            text.length < 40 -> "quick"
            else -> "everyday"
        }
    }

    /** Simple web search (only used when 'internet' is on). */
    private fun webSearch(query: String): String {
        return try {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val client = OkHttpClient()
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: return ""
                val titles = Regex("class=\"result__a\"[^>]*>(.*?)</a>").findAll(html).map { it.groupValues[1] }.toList()
                val snips = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>").findAll(html).map { it.groupValues[1] }.toList()
                val sb = StringBuilder()
                for (i in 0 until minOf(5, titles.size)) {
                    val ti = titles[i].replace(Regex("<[^>]+>"), "").trim()
                    val sn = if (i < snips.size) snips[i].replace(Regex("<[^>]+>"), "").trim() else ""
                    sb.append("• ").append(ti); if (sn.isNotEmpty()) sb.append(" — ").append(sn); sb.append("\n")
                }
                sb.toString()
            }
        } catch (e: Exception) { "" }
    }

    /** Text reply. Blocking — call from a coroutine on Dispatchers.Default. */
    fun ask(userText: String): String {
        ensure(route(userText, false))
        val engine = current ?: throw IllegalStateException("Model not loaded")
        val web = if (internet) webSearch(userText) else ""
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append(buildPreamble())
            if (web.isNotBlank()) append("\n\nLive web results:\n").append(web)
            append("\n\n").append(userText)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        return engine.generateResponse(prompt).trim()
    }

    /**
     * Vision reply (reads an image). Needs a vision-capable model in the "vision" slot
     * (name containing vl/vision/gemma-3n etc.). Uses a session with vision modality on.
     */
    fun askImage(userText: String, bitmap: Bitmap): String {
        if (!registry.containsKey("vision")) {
            return "My eyes are not yet open, mortal — give me a vision-capable model (its name should contain 'vision' or 'gemma-3n') and I shall see."
        }
        return try {
            ensure("vision")
            val engine = current ?: throw IllegalStateException("Vision model not loaded")
            val session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(0.7f)
                    .setGraphOptions(
                        GraphOptions.builder().setEnableVisionModality(true).build()
                    )
                    .build()
            )
            session.addQueryChunk(buildPreamble() + "\n\n" + (userText.ifBlank { "Look upon this and tell me what you see." }))
            session.addImage(BitmapImageBuilder(bitmap).build())
            val out = session.generateResponse().trim()
            session.close()
            out
        } catch (e: Throwable) {
            "My gaze cannot fix upon it — this model may not support sight. Use a vision-capable model (e.g. Gemma 3n) named with 'vision' in the file."
        }
    }

    fun close() { current?.close(); current = null; currentRole = null; currentHasVision = false }
}
