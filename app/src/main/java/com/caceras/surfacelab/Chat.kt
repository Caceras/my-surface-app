package com.caceras.surfacelab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One exchange: what you said, and what came back. */
data class Turn(val you: String, val reply: String)
data class SavedConversation(val id: String, val title: String, val savedAt: Long, val turns: List<Turn>, val draft: String)

/**
 * The conversation itself.
 *
 * The chat screen used to send each message on its own, with nothing before
 * it. Asked to "list 10 more" the model had no idea what "more" referred to
 * and listed fruit, which is exactly what a model does when the question
 * arrives with no conversation attached. So the turns are kept here, sent
 * back as context, and written to disk -- a chat app that forgets everything
 * the moment you rotate the phone or close the app is not a chat app.
 *
 * org.json ships in the framework, so this costs no dependency.
 */
object Chat {

    private const val PREFS = "surfacelab"
    private const val KEY = "chat_turns"

    /** Kept on disk. Older turns are dropped rather than growing forever. */
    private const val KEEP = 40

    fun load(context: Context): MutableList<Turn> {
        val raw = prefs(context).getString(KEY, null) ?: return mutableListOf()
        val turns = mutableListOf<Turn>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val turn = array.getJSONObject(i)
                turns.add(Turn(turn.optString("q"), turn.optString("a")))
            }
        } catch (e: Exception) {
            // A store we cannot read is a store we start again, rather than a
            // crash on launch that leaves the app permanently unopenable.
            return mutableListOf()
        }
        return turns
    }

    fun save(context: Context, turns: List<Turn>) {
        val array = JSONArray()
        turns.takeLast(KEEP).forEach {
            array.put(JSONObject().put("q", it.you).put("a", it.reply))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    fun append(context: Context, turn: Turn) {
        save(context, load(context) + turn)
    }

    fun draft(context: Context): String = prefs(context).getString("draft", "").orEmpty()
    fun saveDraft(context: Context, text: String) {
        prefs(context).edit().putString("draft", text).apply()
    }

    fun speakReplies(context: Context): Boolean = prefs(context).getBoolean("speak_replies", false)
    fun setSpeakReplies(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("speak_replies", value).apply()
    }

    /** New starts fresh without destroying the conversation the user just left. */
    fun archiveCurrent(context: Context, draft: String = draft(context)): Boolean {
        val turns = load(context)
        if (turns.isEmpty() && draft.isBlank()) return false
        val saved = SavedConversation(java.util.UUID.randomUUID().toString(),
            (turns.firstOrNull()?.you ?: draft).replace('\n', ' ').take(80),
            System.currentTimeMillis(), turns, draft)
        val previous = archives(context).filterNot { it.turns == turns && it.draft == draft }
        writeArchives(context, listOf(saved) + previous)
        return true
    }

    fun archives(context: Context): List<SavedConversation> = runCatching {
        parseArchives(JSONArray(prefs(context).getString("conversations", "[]")))
    }.getOrDefault(emptyList())

    private fun parseArchives(rows: JSONArray): List<SavedConversation> {
        require(rows.length() <= 12) { "Too many saved conversations." }
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val turns = row.getJSONArray("turns")
            require(turns.length() <= KEEP) { "Too many saved exchanges." }
            SavedConversation(row.getString("id"), row.getString("title"), row.getLong("savedAt"),
                (0 until turns.length()).map { n -> turns.getJSONObject(n).let { Turn(it.getString("q"), it.getString("a")) } },
                row.optString("draft", ""))
        }
    }

    fun openArchive(context: Context, id: String): Boolean {
        val selected = archives(context).firstOrNull { it.id == id } ?: return false
        archiveCurrent(context)
        writeArchives(context, archives(context).filterNot { it.id == id || (it.turns == selected.turns && it.draft == selected.draft) })
        save(context, selected.turns)
        saveDraft(context, selected.draft)
        return true
    }

    private fun writeArchives(context: Context, saved: List<SavedConversation>) {
        val rows = JSONArray()
        var used = 0
        // Bound JSON work and disk space; retain whole conversations, newest first.
        saved.take(12).forEach { chat ->
            val row = JSONObject().put("id", chat.id).put("title", chat.title).put("savedAt", chat.savedAt)
                .put("draft", chat.draft).put("turns", JSONArray().apply {
                    chat.turns.forEach { put(JSONObject().put("q", it.you).put("a", it.reply)) }
                })
            val size = row.toString().length
            if (rows.length() == 0 || used + size <= 512_000) { rows.put(row); used += size }
        }
        prefs(context).edit().putString("conversations", rows.toString()).apply()
    }

    /** Portable backup chosen through Android's file picker; no storage permission. */
    fun backup(context: Context): String = JSONObject()
        .put("format", "surface-chat-v1")
        .put("turns", JSONArray().apply { load(context).forEach { put(JSONObject().put("q", it.you).put("a", it.reply)) } })
        .put("draft", draft(context))
        .put("conversations", JSONArray(prefs(context).getString("conversations", "[]"))).toString(2)

    fun readBackup(raw: String): Pair<List<Turn>, String> {
        require(raw.length <= 4_000_000) { "This backup is too large." }
        val objectValue = JSONObject(raw)
        require(objectValue.getString("format") == "surface-chat-v1") { "Choose a Surface conversation backup." }
        val array = objectValue.getJSONArray("turns")
        require(array.length() <= KEEP) { "This backup contains too many turns." }
        val turns = (0 until array.length()).map { i ->
            val turn = array.getJSONObject(i)
            Turn(turn.getString("q"), turn.getString("a"))
        }
        parseArchives(objectValue.optJSONArray("conversations") ?: JSONArray())
        val draft = objectValue.optString("draft", "")
        return turns to draft
    }

    fun restoreArchives(context: Context, raw: String) {
        val imported = parseArchives(JSONObject(raw).optJSONArray("conversations") ?: JSONArray())
        writeArchives(context, (imported + archives(context)).distinctBy { it.turns to it.draft })
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
