package com.biosense.data

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.util.UUID

/**
 * Persists the user's scheduled medications and each time they log a dose
 * as taken / skipped / snoozed. Two JSONL files under `<filesDir>/history/`:
 *
 *   medications.jsonl — one line per configured medication.
 *     {"id":"<uuid>","name":"<drug>","dose":"<amount>","times":"08:00,13:00,18:00","enabled":1}
 *
 *   med_events.jsonl — append-only adherence log.
 *     {"t":<ms>,"id":"<uuid>","name":"<drug>","dose":"<amount>","action":"taken|skipped|snoozed","scheduled":"08:00"}
 *
 * We keep the schema flat/plain-text for the same reason as HistoryStore —
 * survives ZIP backup, easy to inspect, no Room migration pain.
 */
class MedicationStore(filesDir: File) {

    private val dir = File(filesDir, "history").also { if (!it.exists()) it.mkdirs() }
    private val medsFile = File(dir, "medications.jsonl")
    private val eventsFile = File(dir, "med_events.jsonl")

    data class Medication(
        val id: String,
        val name: String,
        val dose: String,
        /** Comma-separated 24h HH:MM strings, e.g. "08:00,13:00,18:00". */
        val times: String,
        val enabled: Boolean,
    ) {
        fun timeList(): List<String> = times.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    enum class Action { TAKEN, SKIPPED, SNOOZED }

    data class Event(
        val tMs: Long,
        val id: String,
        val name: String,
        val dose: String,
        val action: Action,
        val scheduledHhmm: String,
    )

    // ---- Medications --------------------------------------------------------

    fun list(): List<Medication> {
        if (!medsFile.exists()) return emptyList()
        val out = ArrayList<Medication>()
        medsFile.forEachLine { line ->
            val id = str(line, "\"id\":") ?: return@forEachLine
            val name = str(line, "\"name\":") ?: ""
            val dose = str(line, "\"dose\":") ?: ""
            val times = str(line, "\"times\":") ?: ""
            val enabled = intField(line, "\"enabled\":") == 1
            out.add(Medication(id, name, dose, times, enabled))
        }
        return out
    }

    fun upsert(m: Medication): Medication {
        val id = m.id.ifEmpty { UUID.randomUUID().toString() }
        val current = list().filter { it.id != id }
        val next = current + m.copy(id = id)
        rewriteMeds(next)
        return m.copy(id = id)
    }

    fun delete(id: String) {
        val next = list().filter { it.id != id }
        rewriteMeds(next)
    }

    private fun rewriteMeds(items: List<Medication>) {
        FileWriter(medsFile, false).use { w ->
            for (m in items) {
                w.append('{')
                    .append("\"id\":\"").append(m.id).append("\"")
                    .append(",\"name\":\"").append(escape(m.name)).append("\"")
                    .append(",\"dose\":\"").append(escape(m.dose)).append("\"")
                    .append(",\"times\":\"").append(m.times).append("\"")
                    .append(",\"enabled\":").append(if (m.enabled) "1" else "0")
                    .append("}\n")
            }
        }
    }

    // ---- Events -------------------------------------------------------------

    fun logEvent(e: Event) {
        FileWriter(eventsFile, true).use { w ->
            w.append('{')
                .append("\"t\":").append(e.tMs.toString())
                .append(",\"id\":\"").append(e.id).append("\"")
                .append(",\"name\":\"").append(escape(e.name)).append("\"")
                .append(",\"dose\":\"").append(escape(e.dose)).append("\"")
                .append(",\"action\":\"").append(e.action.name.lowercase()).append("\"")
                .append(",\"scheduled\":\"").append(e.scheduledHhmm).append("\"")
                .append("}\n")
        }
    }

    fun recentEvents(sinceMs: Long): List<Event> {
        if (!eventsFile.exists()) return emptyList()
        val out = ArrayList<Event>()
        eventsFile.forEachLine { line ->
            val t = longField(line, "\"t\":") ?: return@forEachLine
            if (t < sinceMs) return@forEachLine
            val id = str(line, "\"id\":") ?: ""
            val name = str(line, "\"name\":") ?: ""
            val dose = str(line, "\"dose\":") ?: ""
            val action = when (str(line, "\"action\":")) {
                "taken" -> Action.TAKEN
                "skipped" -> Action.SKIPPED
                "snoozed" -> Action.SNOOZED
                else -> return@forEachLine
            }
            val scheduled = str(line, "\"scheduled\":") ?: ""
            out.add(Event(t, id, name, dose, action, scheduled))
        }
        return out
    }

    // ---- Parsing helpers ----------------------------------------------------

    private fun str(line: String, key: String): String? {
        val i = line.indexOf(key)
        if (i < 0) return null
        var j = i + key.length
        if (j >= line.length || line[j] != '"') return null
        j++
        val start = j
        while (j < line.length && line[j] != '"') j++
        return line.substring(start, j)
    }

    private fun longField(line: String, key: String): Long? =
        rawNumber(line, key)?.toLongOrNull()
    private fun intField(line: String, key: String): Int? =
        rawNumber(line, key)?.toIntOrNull()
    private fun rawNumber(line: String, key: String): String? {
        val i = line.indexOf(key); if (i < 0) return null
        var j = i + key.length; val start = j
        while (j < line.length && line[j] != ',' && line[j] != '}') j++
        return line.substring(start, j)
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        fun of(ctx: Context): MedicationStore = MedicationStore(ctx.filesDir)
    }
}
