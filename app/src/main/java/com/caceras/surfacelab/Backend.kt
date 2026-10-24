package com.caceras.surfacelab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ægentica's back office on aegentica.se: one MCP endpoint with memory, the Inbox, tasks, server
 * jobs and chat history (aegentica-app docs/BACKEND_API.md), reached with an access token the
 * owner makes for this phone. The token comes over the app's private channel from aegentica.se's
 * Settings → Security (`?connect=Pixel`) and is kept encrypted with Android's Keystore.
 */
object Backend {
    const val ENDPOINT = "https://aegentica.se/api/backend/mcp"
    const val CONNECT = "https://aegentica.se/settings/security?connect=Pixel"
    private const val PREFS = "backend"
    /** aegentica.se's personal access tokens. */
    private val TOKEN = Regex("aeg_pat_[A-Za-z0-9_-]{16,256}")

    /** The back office's side, so tests can stand in a fake. */
    fun interface Api { fun call(tool: String, args: JSONObject): JSONObject }

    /** How the token is kept: Keystore encryption, or a stand-in in tests (Robolectric has no Keystore). */
    interface Vault { fun seal(text: String): String; fun unseal(raw: String): String }
    private object Keystore : Vault {
        override fun seal(text: String) = ConnectedAI.seal(text)
        override fun unseal(raw: String) = ConnectedAI.unseal(raw)
    }

    /** Test-only stand-ins. */
    @Volatile var apiForTest: Api? = null
    @Volatile var vaultForTest: Vault? = null
    private val vault get() = vaultForTest ?: Keystore

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun validToken(token: String) = TOKEN.matches(token)

    fun token(context: Context): String? = prefs(context).getString("token", null)?.let { runCatching { vault.unseal(it) }.getOrNull() }?.takeIf(::validToken)
    fun connected(context: Context) = apiForTest != null || token(context) != null
    fun name(context: Context): String? = prefs(context).getString("name", null)

    /** Phone conversations are copied into Ægentica's chat history (on by default once connected). */
    fun saving(context: Context) = prefs(context).getBoolean("save_chats", true)
    fun setSaving(context: Context, on: Boolean) = prefs(context).edit().putBoolean("save_chats", on).apply()

    /** Keeps a token handed over by aegentica.se; false when it is not one or cannot be kept. */
    fun pair(context: Context, token: String, name: String): Boolean {
        if (!validToken(token)) return false
        val sealed = runCatching { vault.seal(token) }.getOrNull() ?: return false
        prefs(context).edit().putString("token", sealed).putString("name", name.oneLine().take(60).ifBlank { "Pixel" }).apply()
        return true
    }

    /** Forgets the token on the phone. aegentica.se's Settings → Security disconnects it there too. */
    fun disconnect(context: Context) = prefs(context).edit().remove("token").remove("name").apply()

    fun api(context: Context): Api? = apiForTest ?: token(context)?.let { Mcp(it) }

    /** MCP over Streamable HTTP, stateless with JSON answers: one POST per tool call. */
    class Mcp(private val token: String, private val endpoint: String = ENDPOINT) : Api {
        private val id = AtomicInteger()
        override fun call(tool: String, args: JSONObject): JSONObject {
            val request = JSONObject().put("jsonrpc", "2.0").put("id", id.incrementAndGet()).put("method", "tools/call")
                .put("params", JSONObject().put("name", tool).put("arguments", args))
            val response = ConnectedAI.http.post(endpoint, mapOf(
                "Content-Type" to "application/json", "Accept" to "application/json, text/event-stream",
                "Authorization" to "Bearer $token"), request.toString().toByteArray()) {}
            try {
                val text = response.body.readBounded(4_000_000).toString(Charsets.UTF_8)
                when (response.code) {
                    in 200..299 -> {}
                    401 -> error("Ægentica did not accept this phone's access. Connect again in Settings → Connect to Ægentica.")
                    429 -> error("Ægentica is busy with this phone's requests. Wait a minute.")
                    else -> error("Ægentica answered HTTP ${response.code}.")
                }
                // A JSON answer, or one SSE message when the server streams.
                val json = if (response.contentType.contains("event-stream")) text.lines().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: "{}" else text
                return result(JSONObject(json))
            } finally { response.close() }
        }

        companion object {
            /** A tool result: its structured content, or the JSON in its text; a refusal throws its sentence. */
            fun result(envelope: JSONObject): JSONObject {
                envelope.optJSONObject("error")?.let { error(it.optString("message").ifBlank { "Ægentica refused the request." }.take(300)) }
                val result = envelope.optJSONObject("result") ?: error("Ægentica gave no result.")
                val text = result.optJSONArray("content").objects().firstOrNull { it.optString("type") == "text" }?.optString("text").orEmpty()
                if (result.optBoolean("isError")) error(text.ifBlank { "Ægentica refused the request." }.take(500))
                return result.optJSONObject("structuredContent") ?: runCatching { JSONObject(text) }.getOrElse { JSONObject().put("text", text) }
            }
        }
    }

    /** How many turns of each conversation are already in Ægentica's chat history. */
    private val saved = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Copies a finished phone conversation into Ægentica's chat history, so it continues on every
     * device. Questions and answers only, as text; the screen and screenshots stay on the phone.
     * Saved again only when it grew; [done] gets the chat's link on aegentica.se.
     */
    fun saveConversation(context: Context, session: AgentSession, done: (String?) -> Unit = {}) {
        if (session.running || !saving(context)) return done(null)
        val api = api(context) ?: return done(null)
        val turns = session.thread.toList()
        val messages = JSONArray()
        turns.forEach { turn ->
            when (turn) {
                is AgentTurn.User -> if (turn.text.isNotBlank()) messages.put(JSONObject().put("role", "user").put("text", turn.text.take(16_000)))
                is AgentTurn.Assistant -> synchronized(turn.reply) { turn.reply.text.toString() }.takeIf { it.isNotBlank() }
                    ?.let { messages.put(JSONObject().put("role", "assistant").put("text", Prompts.reply(it).take(16_000))) }
            }
        }
        if (messages.length() < 2 || messages.length() > 400) return done(null)
        val before = saved[session.id]
        if (before != null && before >= turns.size) return done(null)
        saved[session.id] = turns.size
        val first = (turns.firstOrNull() as? AgentTurn.User)?.text?.oneLine()?.take(80)?.ifBlank { null } ?: "A question"
        val title = "Phone · $first" + if (before != null) " (continued)" else ""
        Thread({
            val open = runCatching { api.call("conversation_save", JSONObject().put("title", title).put("messages", messages)).optString("open").takeIf { it.startsWith("https://") } }
                .onFailure { saved.remove(session.id) }.getOrNull()
            done(open)
        }, "save-chat").start()
    }

    internal fun resetForTest() { apiForTest = null; vaultForTest = null; saved.clear() }
}
