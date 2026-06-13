package com.olympus.zeus

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device memory in three plain text files (offline, user-editable):
 *  - profile.txt : durable facts about the user. Always loaded into the prompt.
 *  - recent.txt  : rolling transcript; the recent tail is loaded each session.
 *  - archive-YYYY-MM-DD.txt : one file per day, kept for date-based recall.
 *
 * The model never "learns" — these files just feed context back into each prompt.
 */
class Memory(context: Context) {
    private val dir = File(context.filesDir, "memory").apply { mkdirs() }
    private val profileFile = File(dir, "profile.txt")
    private val recentFile = File(dir, "recent.txt")

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun archiveFile(day: String) = File(dir, "archive-$day.txt")

    /** Durable facts about the user (always injected into the prompt). */
    fun profile(): String = try {
        if (profileFile.exists()) profileFile.readText().trim() else ""
    } catch (e: Exception) { "" }

    /** Append one remembered fact to the profile. */
    fun addProfileFact(fact: String) {
        val f = fact.trim()
        if (f.isEmpty()) return
        try { profileFile.appendText("- $f\n") } catch (e: Exception) { }
    }

    /** Most recent slice of the transcript, to seed a new session. */
    fun recentTail(maxChars: Int = 4000): String = try {
        if (!recentFile.exists()) "" else {
            val all = recentFile.readText()
            if (all.length <= maxChars) all.trim()
            else all.substring(all.length - maxChars).trim()
        }
    } catch (e: Exception) { "" }

    /** Record a turn to the rolling recent file and to today's archive. */
    fun record(who: String, text: String) {
        val line = "$who: ${text.replace("\n", " ").trim()}\n"
        try {
            recentFile.appendText(line)
            val r = recentFile.readText()
            if (r.length > 16000) recentFile.writeText(r.substring(r.length - 16000))
            archiveFile(today()).appendText(line)
        } catch (e: Exception) { }
    }

    /** Days that have an archive, newest first (yyyy-MM-dd). */
    fun archiveDays(): List<String> = try {
        (dir.listFiles() ?: emptyArray())
            .map { it.name }
            .filter { it.startsWith("archive-") && it.endsWith(".txt") }
            .map { it.removePrefix("archive-").removeSuffix(".txt") }
            .sortedDescending()
    } catch (e: Exception) { emptyList() }

    /** The saved transcript for one day. */
    fun readDay(day: String, maxChars: Int = 3000): String = try {
        val f = archiveFile(day)
        if (!f.exists()) "" else {
            val all = f.readText().trim()
            if (all.length <= maxChars) all else all.substring(all.length - maxChars)
        }
    } catch (e: Exception) { "" }

    /** Erase all memory files. */
    fun wipe() {
        try { (dir.listFiles() ?: emptyArray()).forEach { it.delete() } } catch (e: Exception) { }
    }
}
