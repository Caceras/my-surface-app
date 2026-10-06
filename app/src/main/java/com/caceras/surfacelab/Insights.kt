package com.caceras.surfacelab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What AI proposes about one note. Nothing is stored until the user accepts items. */
data class Insight(val title: String = "", val summary: List<String> = emptyList(), val tasks: List<String> = emptyList(),
                   val people: List<String> = emptyList(), val projects: List<String> = emptyList())

/** One reviewable change. [existing] is a matching person/project already in the Library. */
data class Proposal(val kind: String, val value: String, val existing: Record? = null) {
    val label: String get() = when (kind) {
        "title" -> "Title: $value"
        "summary" -> "Summary note: ${value.lineSequence().first().removePrefix("• ").take(80)}…"
        "task" -> "Task: $value"
        else -> "${kind.replaceFirstChar(Char::uppercase)}: $value" + if (existing != null) " (link existing)" else " (new)"
    }
}

/** Records created or changed by one accepted review, so it can be undone exactly. */
data class Applied(val record: String, val previousTitle: String?, val created: List<String>, val linked: List<String>)

/**
 * Entities and actions extracted from the user's own words, applied only
 * after review as ordinary linked records whose source names the model and
 * the note they came from. The note's text and original are never edited.
 */
object Insights {
    const val INSTRUCTION = "Read the note below and reply with only a JSON object with these keys: " +
        "\"title\" (at most 8 words), \"summary\" (up to 3 short strings), " +
        "\"tasks\" (concrete actions the speaker committed to or asked for, at most 5), " +
        "\"people\" (names of people mentioned), \"projects\" (named projects, clients or products). " +
        "Write in the note's language. Use empty lists when nothing fits. Never invent names or tasks that are not in the note."
    const val NANO_CHUNK = 2500
    const val CONNECTED_CHUNK = 12000
    private const val MAX_PARTS = 6

    private fun strings(json: JSONObject, key: String, limit: Int): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.opt(it)?.toString()?.trim()?.removePrefix("-")?.trim()?.takeIf { s -> s.isNotEmpty() && s.length <= 200 } }
            .distinctBy { it.lowercase() }.take(limit)
    }

    /** Tolerates prose or code fences around the JSON object. */
    fun parse(text: String): Insight? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        return Insight(json.optString("title").trim().take(120), strings(json, "summary", 3), strings(json, "tasks", 5), strings(json, "people", 8), strings(json, "projects", 6))
    }

    fun merge(parts: List<Insight>): Insight = Insight(
        title = parts.firstOrNull { it.title.isNotBlank() }?.title.orEmpty(),
        summary = parts.flatMap { it.summary }.distinctBy { it.lowercase() }.take(5),
        tasks = parts.flatMap { it.tasks }.distinctBy { it.lowercase() }.take(8),
        people = parts.flatMap { it.people }.distinctBy { it.lowercase() }.take(10),
        projects = parts.flatMap { it.projects }.distinctBy { it.lowercase() }.take(8))

    /** Generated titles may replace blank or automatic transcript titles only. */
    private fun replaceableTitle(record: Record) = record.title.isBlank() || record.title.startsWith("Transcript · ") || record.title.startsWith("Live conversation · ")

    fun proposals(store: WorkspaceStore, record: Record, insight: Insight): List<Proposal> = buildList {
        if (insight.title.isNotBlank() && replaceableTitle(record)) add(Proposal("title", insight.title))
        if (insight.summary.isNotEmpty()) add(Proposal("summary", insight.summary.joinToString("\n") { "• $it" }))
        insight.tasks.forEach { add(Proposal("task", it)) }
        val linked = store.linked(record.id).map { it.id }.toSet()
        for ((kind, names) in listOf("person" to insight.people, "project" to insight.projects)) {
            val existing = store.list(kind = kind, limit = 2000)
            names.forEach { name ->
                val match = existing.firstOrNull { it.title.equals(name, true) }
                if (match == null || match.id !in linked) add(Proposal(kind, match?.title ?: name, match))
            }
        }
    }

    /** Applies accepted proposals in one transaction. */
    fun apply(store: WorkspaceStore, record: Record, accepted: List<Proposal>, provider: String): Applied {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            var previousTitle: String? = null
            val created = mutableListOf<String>(); val linked = mutableListOf<String>()
            val provenance = "AI insight · $provider · from ${record.id}"
            for (p in accepted) when (p.kind) {
                "title" -> { val current = store.get(record.id)!!; previousTitle = current.title; store.save(current.copy(title = p.value.take(200)), current.revision) }
                "summary" -> { val note = store.save(Record(title = "Summary · " + (accepted.firstOrNull { it.kind == "title" }?.value ?: record.title).ifBlank { "note" }.take(150), body = p.value, source = provenance)); store.link(note.id, record.id, "source"); created += note.id }
                "task" -> { val task = store.save(Record(kind = "task", title = p.value.take(200), body = p.value, source = provenance)); store.link(task.id, record.id, "source"); created += task.id }
                "person", "project" -> {
                    val target = p.existing ?: store.save(Record(kind = p.kind, title = p.value.take(200), source = provenance)).also { created += it.id }
                    if (target.id != record.id) { store.link(record.id, target.id, "related"); if (p.existing != null) linked += target.id }
                }
            }
            db.setTransactionSuccessful()
            return Applied(record.id, previousTitle, created, linked)
        } finally { db.endTransaction() }
    }

    /** Reverses one review: created records are removed, links to existing records dropped, the title restored. */
    fun undo(store: WorkspaceStore, applied: Applied) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            applied.created.forEach { id -> store.get(id)?.let { r -> store.save(r.copy(deleted = true)); store.purge(id) } }
            applied.linked.forEach { store.unlink(applied.record, it) }
            applied.previousTitle?.let { title -> store.get(applied.record)?.let { store.save(it.copy(title = title)) } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun toJson(insight: Insight) = JSONObject().put("title", insight.title).put("summary", JSONArray(insight.summary)).put("tasks", JSONArray(insight.tasks))
        .put("people", JSONArray(insight.people)).put("projects", JSONArray(insight.projects))
}

/** Foreground, chunked extraction with the same cancellation contract as polish. */
class InsightRun(private val context: Context, private val brain: SurfaceBrain, text: String) {
    val parts = Polish.chunks(Transcripts.words(text).trim(), if (brain === ConnectedAI.brain) Insights.CONNECTED_CHUNK else Insights.NANO_CHUNK).take(6)
    private val found = mutableListOf<Insight>()
    private var index = 0
    private var done = false
    private var progress: (Int, Int) -> Unit = { _, _ -> }
    private var finished: (Insight?, String?) -> Unit = { _, _ -> }

    fun start(onProgress: (Int, Int) -> Unit, onFinished: (Insight?, String?) -> Unit) { progress = onProgress; finished = onFinished; next() }

    private fun next() {
        if (done) return
        if (index == parts.size) {
            done = true
            if (found.isEmpty()) finished(null, "AI did not return usable suggestions. Your note is unchanged.") else finished(Insights.merge(found), null)
            return
        }
        progress(index, parts.size)
        brain.run(context, Task.ASK, parts[index], Insights.INSTRUCTION) { result ->
            if (done) return@run
            if (!result.ok) { done = true; finished(null, result.note?.let { "Suggestions stopped: $it" } ?: "Suggestions stopped. Your note is unchanged."); return@run }
            Insights.parse(Prompts.reply(result.text))?.let { found += it }
            index++
            next()
        }
    }

    fun cancel(reason: String = "Suggestions cancelled. Your note is unchanged.") {
        if (done) return
        done = true; brain.cancel(); finished(null, reason)
    }
}
