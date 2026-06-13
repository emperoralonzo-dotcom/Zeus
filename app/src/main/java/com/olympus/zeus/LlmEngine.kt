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
    private val models: ModelManager,
    private val memory: Memory? = null
) {
    /** When true, Zeus may fetch live web results (off = fully private/offline). */
    var internet: Boolean = false

    private val registry: Map<String, File> = models.registry()
    private var currentRole: String? = null
    private var current: LlmInference? = null
    private var currentHasVision = false

    private fun buildPreamble(): String {
        val sb = StringBuilder(
            "You are Zeus, a helpful AI assistant running entirely on this device. " +
            "Answer clearly, accurately and concisely in plain prose — no markdown, no lists, " +
            "1 to 4 short sentences.")
        val prof = memory?.profile() ?: ""
        if (prof.isNotBlank()) sb.append(" Known facts about the user:\n").append(prof)
        if (!internet) sb.append(
            " You have no internet by default; if asked about live events you cannot know, " +
            "say so plainly rather than inventing facts.")
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
            .setMaxTokens(384)
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

    /** Recent conversation so Zeus remembers what was said. (role, text) per turn. */
    private val history = ArrayList<Pair<String, String>>()
    /** The model chosen for THIS conversation. Pinned so it doesn't swap mid-chat. */
    private var sessionRole: String? = null
    /** Tail of the saved transcript, loaded once so he recalls past sessions. */
    private var priorLoaded = false
    private var priorContext = ""
    private fun trimHistory() { while (history.size > 8) history.removeAt(0) }
    fun resetHistory() { history.clear(); sessionRole = null; priorLoaded = false; priorContext = "" }

    /** Text reply. Blocking — call from a coroutine on Dispatchers.Default. */
    fun ask(userText: String): String {
        // Pick the model on the FIRST message, then keep it for the whole chat.
        // Default to the balanced "everyday" model rather than routing per word,
        // so the conversation stays coherent and in one voice.
        val role = sessionRole ?: "everyday".also { sessionRole = it }
        ensure(role)
        val engine = current ?: throw IllegalStateException("Model not loaded")
        if (!priorLoaded) { priorContext = memory?.recentTail() ?: ""; priorLoaded = true }
        val web = if (internet) webSearch(userText) else ""
        history.add("user" to userText)
        trimHistory()
        val prompt = buildString {
            var prefaced = false
            history.forEachIndexed { i, (role2, text) ->
                if (role2 == "user") {
                    append("<start_of_turn>user\n")
                    if (!prefaced) {
                        append(buildPreamble())
                        if (priorContext.isNotBlank())
                            append("\n\nEarlier conversation (for context):\n").append(priorContext)
                        append("\n\n"); prefaced = true
                    }
                    append(text)
                    if (i == history.lastIndex && web.isNotBlank())
                        append("\n\nLive web results:\n").append(web)
                    append("<end_of_turn>\n")
                } else {
                    append("<start_of_turn>model\n").append(text).append("<end_of_turn>\n")
                }
            }
            append("<start_of_turn>model\n")
        }
        val reply = engine.generateResponse(prompt).trim()
        history.add("model" to reply)
        trimHistory()
        memory?.record("You", userText)
        memory?.record("Zeus", reply)
        return reply
    }

    /**
     * Vision reply (reads an image). Needs a vision-capable model in the "vision" slot
     * (name containing vl/vision/gemma-3n etc.). Uses a session with vision modality on.
     */
    fun askImage(userText: String, bitmap: Bitmap): String {
        if (!registry.containsKey("vision")) {
            return "No vision model is loaded. Add a vision-capable model (its filename should contain 'vision' or 'gemma-3n') and I can read images."
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
            history.add("user" to ("(shows you an image) " + userText).trim())
            history.add("model" to out)
            trimHistory()
            memory?.record("You", "(sent an image) " + userText)
            memory?.record("Zeus", out)
            out
        } catch (e: Throwable) {
            "I could not read that image — this model may not support vision. Use a vision-capable model (e.g. Gemma 3n) with 'vision' in the filename."
        }
    }

    fun close() { current?.close(); current = null; currentRole = null; currentHasVision = false }
}
