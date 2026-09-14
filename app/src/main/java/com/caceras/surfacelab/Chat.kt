package com.caceras.surfacelab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One exchange: what you said, and what came back. */
data class Turn(val you: String, val reply: String)

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

    /** Portable backup chosen through Android's file picker; no storage permission. */
    fun backup(context: Context): String = JSONObject()
        .put("format", "surface-chat-v1")
        .put("turns", JSONArray().apply { load(context).forEach { put(JSONObject().put("q", it.you).put("a", it.reply)) } })
        .put("draft", draft(context)).toString(2)

    fun readBackup(raw: String): Pair<List<Turn>, String> {
        require(raw.length <= 512_000) { "This backup is too large." }
        val objectValue = JSONObject(raw)
        require(objectValue.getString("format") == "surface-chat-v1") { "Choose a Surface conversation backup." }
        val array = objectValue.getJSONArray("turns")
        require(array.length() <= KEEP) { "This backup contains too many turns." }
        val turns = (0 until array.length()).map { i ->
            val turn = array.getJSONObject(i)
            Turn(turn.getString("q"), turn.getString("a"))
        }
        val draft = objectValue.optString("draft", "")
        return turns to draft
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
