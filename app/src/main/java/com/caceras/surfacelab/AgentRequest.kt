package com.caceras.surfacelab

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One turn of a phone agent conversation. */
sealed class AgentTurn {
    /**
     * What the person asked, with what they invoked the assistant on: the screen's text (or a
     * selection) and a screenshot as JPEG. [aloud] marks a spoken question, answered for listening.
     */
    class User(
        val text: String,
        val app: String = "",
        val appName: String = "",
        val screen: String = "",
        val selection: Boolean = false,
        val screenshot: ByteArray? = null,
        val aloud: Boolean = false,
        /** Facts from the phone for this turn only (hands on or off, the person took over). */
        val notes: List<String> = emptyList(),
    ) : AgentTurn()

    class Assistant(val reply: AgentReply) : AgentTurn()
}

/**
 * A conversation and the playbook → an OpenRouter Chat Completions body, ported from Glimt's
 * src/lib/request.ts (buildChatBody, toMessages, contextText). Screen content is fenced as
 * untrusted data and cannot close its own fence; screenshots go only to a model that sees images.
 */
object AgentRequest {
    /** Our tag names inside screen text lose their angle bracket, so screen text cannot pose as the person. */
    fun defang(text: String) = text.replace(Regex("<(/?)(screen|selection|tool_result|aegentica_note)\\b", RegexOption.IGNORE_CASE), "‹$1$2")

    /** Attribute values in our fences: a title must not be able to close the tag it sits in. */
    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun systemPrompt(playbook: Playbook, hands: Boolean, now: Date = Date(), zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault(), backend: Boolean = false): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(now)
        val lines = mutableListOf(
            "You are Ægentica, the person's assistant on their Android phone, opened on top of the app they are using. You see the screen they invoked you on (<screen>): its text, and a screenshot when it is shared.",
            // The date only: this prompt starts the cached prefix, so it must not change within a day.
            "Today: $day (${zone.id}). Locale: ${locale.toLanguageTag()}.",
            "Answer first, directly and briefly. Use Markdown: short paragraphs, lists when they help. No preamble, no filler.",
            "Reply in the language the person writes or speaks in (usually Swedish or English).",
        )
        lines += playbook.shared
        lines += if (hands) playbook.hands else playbook.noHands
        if (backend) lines += playbook.backend
        return lines.joinToString("\n")
    }

    /** The screen (or selection) as one text block, fenced as untrusted data. */
    fun contextText(turn: AgentTurn.User): String {
        val out = mutableListOf<String>()
        turn.notes.forEach { out += "<aegentica_note>${defang(it)}</aegentica_note>" }
        if (turn.screen.isNotBlank()) {
            val tag = if (turn.selection) "selection" else "screen"
            out += "<$tag app=\"${esc(turn.appName.ifBlank { turn.app })}\" package=\"${esc(turn.app)}\">"
            out += defang(turn.screen)
            out += "</$tag>"
        } else if (turn.screenshot != null) {
            out += "<screen app=\"${esc(turn.appName.ifBlank { turn.app })}\" package=\"${esc(turn.app)}\">(No text was shared; see the screenshot.)</screen>"
        }
        return out.joinToString("\n")
    }

    private fun jpegUrl(bytes: ByteArray) = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun image(bytes: ByteArray) = JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", jpegUrl(bytes)))
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)

    private fun userContent(turn: AgentTurn.User, vision: Boolean): JSONArray {
        val content = JSONArray()
        val context = contextText(turn)
        if (context.isNotBlank()) content.put(text(context))
        if (turn.screenshot != null) {
            if (vision) content.put(image(turn.screenshot))
            else content.put(text("[A screenshot was shared, but this model cannot see images.]"))
        }
        val voice = if (turn.aloud) "\n\n(Spoken; the answer is read aloud, so keep it short and easy to listen to.)" else ""
        content.put(text(turn.text.ifBlank { if (turn.selection) "Explain this." else "Explain what is on this screen." } + voice))
        return content
    }

    private fun toolResult(call: AgentToolCall): String {
        // A call cut off mid-stream (stopped, or the connection dropped) never ran.
        if (!AgentStream.argsComplete(call.args)) return "Not run: the call was cut off."
        return call.result ?: if (call.status == "denied") "The person declined this action." else "The tool did not return a result."
    }

    /** History as Chat Completions messages, with tool results after the turn that asked for them. */
    fun messages(thread: List<AgentTurn>, playbook: Playbook, vision: Boolean, hands: Boolean, now: Date = Date(), backend: Boolean = false): JSONArray {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt(playbook, hands, now, backend = backend)))
        for (turn in thread) when (turn) {
            is AgentTurn.User -> out.put(JSONObject().put("role", "user").put("content", userContent(turn, vision)))
            is AgentTurn.Assistant -> {
                val reply = turn.reply
                // Stopped before saying or asking anything: nothing to replay.
                if (reply.empty) continue
                out.put(AgentStream.forHistory(reply))
                val shots = mutableListOf<ByteArray>()
                for (call in reply.tools.filter { it.name.isNotEmpty() }) {
                    out.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", toolResult(call)))
                    call.image?.let { shots += it }
                }
                // Screenshots from tools go back as a user turn, which every vision model accepts.
                if (shots.isNotEmpty() && vision) {
                    val content = JSONArray().put(text("Screenshot returned by phone_screenshot:"))
                    shots.forEach { content.put(image(it)) }
                    out.put(JSONObject().put("role", "user").put("content", content))
                }
            }
        }
        return out
    }

    /**
     * The request body. [offered] are the tools the model may call now; tools it called earlier in
     * this conversation stay defined, since providers reject a history with tool calls and no tools.
     * [final] is the last turn of a run at its step limit: same tools, no calls allowed.
     */
    fun body(
        thread: List<AgentTurn>,
        playbook: Playbook,
        model: String,
        sessionId: String,
        offered: List<ToolSpec>,
        vision: Boolean,
        hands: Boolean,
        final: Boolean = false,
        maxTokens: Int = 4096,
        backend: Boolean = false,
    ): JSONObject {
        val body = JSONObject().put("model", model).put("messages", messages(thread, playbook, vision, hands, backend = backend)).put("stream", true)
            .put("session_id", sessionId).put("max_tokens", maxTokens)
            // Cost and tokens come back with the last chunk.
            .put("usage", JSONObject().put("include", true))
            // Providers that store or train on prompts are excluded, as everywhere in the app.
            .put("provider", JSONObject().put("data_collection", "deny"))
        // Claude caches from one top-level field that moves forward as the conversation grows.
        if (model.removePrefix("~").startsWith("anthropic/")) body.put("cache_control", JSONObject().put("type", "ephemeral"))
        val used = thread.filterIsInstance<AgentTurn.Assistant>().flatMap { t -> t.reply.tools.map { it.name } }.filter { it.isNotEmpty() }
        val defined = offered.map { it.name }.toSet() + used
        val tools = (offered + playbook.tools).distinctBy { it.name }.filter { it.name in defined }
        if (tools.isNotEmpty()) {
            body.put("tools", JSONArray(tools.map { it.definition() }))
            if (final || offered.isEmpty()) body.put("tool_choice", "none")
        }
        return body
    }
}
