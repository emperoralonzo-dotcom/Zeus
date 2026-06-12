package com.olympus.zeus

import android.app.ActivityManager
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Catalogs the on-device model files and assigns each a role automatically, so several
 * models can work together as one. Models arrive AFTER install: downloaded in-app
 * (ungated models) or imported from the phone's Downloads (gated models like Gemma).
 * Each model is saved under its role name: quick.task, everyday.task, deep.task,
 * reasoning.task, coding.task, vision.task — up to six minds, one loaded at a time.
 */
class ModelManager(private val context: Context) {

    data class Entry(val name: String, val file: File, val size: Long)

    private val prefs = context.getSharedPreferences("zeus", Context.MODE_PRIVATE)

    /** A place models can live: internal storage, shared phone storage, or an SD card. */
    data class Store(val id: String, val label: String, val dir: File)

    /** All storage locations available on this device (internal always; SD card if present). */
    fun stores(): List<Store> {
        val list = mutableListOf<Store>()
        list.add(Store("internal", "Internal storage", File(context.filesDir, "models")))
        val ext = context.getExternalFilesDirs(null) ?: emptyArray()
        ext.forEachIndexed { i, dir ->
            if (dir != null) {
                val removable = try { android.os.Environment.isExternalStorageRemovable(dir) } catch (e: Exception) { i > 0 }
                val label = if (i == 0 && !removable) "Phone storage (shared)" else "SD card"
                list.add(Store("ext$i", label, File(dir, "models")))
            }
        }
        return list
    }

    fun currentStoreId(): String = prefs.getString("store", "internal") ?: "internal"
    fun setStore(id: String) { prefs.edit().putString("store", id).apply() }

    /** Where models are read/written right now (the chosen store). */
    private val modelsDir: File
        get() {
            val id = currentStoreId()
            val store = stores().firstOrNull { it.id == id } ?: stores().first()
            return store.dir.apply { mkdirs() }
        }

    /** Every model file present (MediaPipe .task or older .bin). */
    fun catalog(): List<Entry> =
        (modelsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".bin")) && it.length() > 1_000_000L }
            .map { Entry(it.name, it, it.length()) }

    fun isAnyModelPresent(): Boolean = catalog().isNotEmpty()

    /**
     * Roles by FILENAME first (quick/everyday/deep/reasoning/coding/vision .task),
     * falling back to name-hints and size so any model files still work.
     */
    fun registry(): Map<String, File> {
        val all = catalog()
        if (all.isEmpty()) return emptyMap()
        val map = linkedMapOf<String, File>()
        val roles = listOf("quick", "everyday", "deep", "reasoning", "coding", "vision")

        // First: exact names like quick.task, vision.task (what the in-app installer writes)
        for (r in roles) {
            all.firstOrNull { it.name.substringBeforeLast('.').equals(r, ignoreCase = true) }
                ?.let { map[r] = it.file }
        }
        // Second: name hints for anything not yet assigned
        val left = all.filter { e -> map.values.none { it == e.file } }
        val isVision = { n: String -> Regex("(vl|vision|llava|moondream|paligemma|3n)", RegexOption.IGNORE_CASE).containsMatchIn(n) }
        val isCoder = { n: String -> Regex("(cod(e|er|ing)|coder)", RegexOption.IGNORE_CASE).containsMatchIn(n) }
        val isReason = { n: String -> Regex("(r1|qwq|reason|distill|think)", RegexOption.IGNORE_CASE).containsMatchIn(n) }
        if (!map.containsKey("vision")) left.firstOrNull { isVision(it.name) }?.let { map["vision"] = it.file }
        if (!map.containsKey("coding")) left.firstOrNull { isCoder(it.name) }?.let { map["coding"] = it.file }
        if (!map.containsKey("reasoning")) left.firstOrNull { isReason(it.name) }?.let { map["reasoning"] = it.file }
        // Third: remaining text models by size into quick/everyday/deep
        val text = left.filter { e -> map.values.none { it == e.file } }.sortedBy { it.size }
        val sizeTiers = listOf("quick", "everyday", "deep").filter { !map.containsKey(it) }
        if (text.isNotEmpty() && sizeTiers.isNotEmpty()) {
            sizeTiers.forEachIndexed { i, tier ->
                val idx = if (sizeTiers.size == 1) text.size - 1
                          else Math.round(i.toDouble() / (sizeTiers.size - 1) * (text.size - 1)).toInt()
                map[tier] = text[minOf(text.size - 1, idx)].file
            }
        }
        return map
    }

    fun deviceRamGb(): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun recommendedTier(): String {
        val gb = deviceRamGb()
        return when {
            gb < 4.0 -> "a 0.5–1B model (smallest, for modest phones)"
            gb < 6.0 -> "a ~2B model (the sweet spot)"
            else     -> "a 2–3B model (full strength on flagships)"
        }
    }

    /**
     * Downloads a model from a DIRECT file URL and installs it under the chosen role
     * (e.g. quick.task). Gated models (Gemma, Llama) return HTTP 401/403 here — those
     * must be downloaded in a logged-in browser and brought in via importStream instead.
     */
    fun downloadModel(url: String, targetName: String, onProgress: (Float) -> Unit) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            val body = response.body ?: throw RuntimeException("Empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            saveStream(body.source().inputStream(), targetName, total, onProgress)
        }
    }

    /** Installs a model from a stream (used by the file-import picker). */
    fun importStream(input: InputStream, targetName: String, total: Long, onProgress: (Float) -> Unit) =
        saveStream(input, targetName, total, onProgress)

    private fun saveStream(input: InputStream, targetName: String, total: Long, onProgress: (Float) -> Unit) {
        val dest = File(modelsDir, targetName)
        val tmp = File(modelsDir, "$targetName.part")
        var done = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buf); if (read == -1) break
                out.write(buf, 0, read); done += read
                if (total > 0) onProgress(done.toFloat() / total)
            }
            out.flush()
        }
        val size = tmp.length()
        if (size < 1_000_000L) {
            tmp.delete()
            throw RuntimeException("that was not a model file (got ${size / 1024} KB — use the direct .task file link, not the page)")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw RuntimeException("could not finalize $targetName")
        onProgress(1f)
    }
}
