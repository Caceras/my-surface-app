package com.caceras.surfacelab

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tool call the model asked for, and what became of it. Status: pending, awaiting-approval,
 * running, done, error or denied.
 */
class AgentToolCall(var id: String, var name: String = "", var args: String = "") {
    @Volatile var status = "pending"
    @Volatile var result: String? = null
    /** A JPEG the tool returned for the model to see (phone_screenshot). */
    @Volatile var image: ByteArray? = null
    /** How the step reads to the person. */
    @Volatile var label = ""
}

/** Tokens and cost of one model call, as OpenRouter reports them. */
data class AgentUsage(val prompt: Int, val completion: Int, val reasoning: Int, val cached: Int, val cost: Double?)

/** One assistant turn as it streams in: text, reasoning, tool calls, usage, and how it ended. */
class AgentReply(val model: String) {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    /** Passed back unmodified, as reasoning models need for tool use. */
    val reasoningDetails = mutableListOf<JSONObject>()
    val tools = mutableListOf<AgentToolCall>()
    @Volatile var usage: AgentUsage? = null
    @Volatile var generationId: String? = null
    @Volatile var servedBy: String? = null
    /** waiting, streaming, done, stopped or error. */
    @Volatile var status = "waiting"
    @Volatile var error: String? = null
    @Volatile var finish: String? = null
    internal val slots = HashMap<Int, AgentToolCall>()
    internal val madeUp = HashSet<AgentToolCall>()

    /** Nothing to replay: stopped before it said or asked anything. */
    val empty get() = text.isEmpty() && tools.none { it.name.isNotEmpty() }
}

/**
 * Server-sent events as OpenRouter streams them: `data: {json}` lines separated by a blank line,
 * `: OPENROUTER PROCESSING` comments while a provider thinks, and a final `data: [DONE]`. Feed it
 * text in any pieces (boundaries can fall anywhere, also between `\r` and `\n`). Ported from
 * Glimt's src/lib/sse.ts.
 */
class SseParser {
    sealed class Event {
        data class Data(val data: String, val event: String?) : Event()
        data class Comment(val text: String) : Event()
    }
    private val buf = StringBuilder()
    private val data = mutableListOf<String>()
    private var event: String? = null

    fun push(text: String): List<Event> {
        buf.append(text)
        val out = mutableListOf<Event>()
        while (true) {
            val nl = buf.indexOfFirst { it == '\n' || it == '\r' }
            if (nl < 0) break
            // A lone trailing \r may be the first half of \r\n: wait for the next piece.
            if (buf[nl] == '\r' && nl == buf.length - 1) break
            val line = buf.substring(0, nl)
            val skip = if (buf[nl] == '\r' && nl + 1 < buf.length && buf[nl + 1] == '\n') 2 else 1
            buf.delete(0, nl + skip)
            line(line, out)
        }
        return out
    }

    /** Whatever is left when the stream ends without a trailing blank line. */
    fun end(): List<Event> {
        val out = mutableListOf<Event>()
        val last = buf.toString().removeSuffix("\r")
        if (last.isNotEmpty()) line(last, out)
        buf.setLength(0)
        dispatch(out)
        return out
    }

    private fun line(line: String, out: MutableList<Event>) {
        if (line.isEmpty()) return dispatch(out)
        if (line.startsWith(":")) { out += Event.Comment(line.substring(1).trim()); return }
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") data += value else if (field == "event") event = value
    }

    private fun dispatch(out: MutableList<Event>) {
        if (data.isNotEmpty()) out += Event.Data(data.joinToString("\n"), event)
        data.clear(); event = null
    }
}

/**
 * Folding OpenRouter chat-completion chunks into an [AgentReply], ported from Glimt's
 * src/lib/stream.ts: tool-call arguments split across chunks and named by index, reasoning
 * details merged by index, usage with cost, and errors mid-stream.
 */
object AgentStream {
    fun readUsage(value: Any?): AgentUsage? {
        val usage = value as? JSONObject ?: return null
        val prompt = usage.optJSONObject("prompt_tokens_details")
        val completion = usage.optJSONObject("completion_tokens_details")
        return AgentUsage(
            prompt = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0)),
            completion = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0)),
            reasoning = completion?.optInt("reasoning_tokens", 0) ?: 0,
            cached = prompt?.optInt("cached_tokens", 0) ?: 0,
            cost = usage.opt("cost").let { if (it is Number) it.toDouble() else null },
        )
    }

    /** Apply one parsed chunk (the JSON of an SSE `data:` line) to the reply. */
    fun apply(reply: AgentReply, chunk: JSONObject) {
        chunk.optString("id").takeIf { it.isNotEmpty() && reply.generationId == null }?.let { reply.generationId = it }
        chunk.optString("provider").takeIf { it.isNotEmpty() }?.let { reply.servedBy = it }
        readUsage(chunk.opt("usage"))?.let { reply.usage = it }
        val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
        // A mid-stream error comes at the top level or on the choice, and ends with finish_reason "error".
        val error = chunk.opt("error") ?: choice?.opt("error")
        if ((error != null && error != JSONObject.NULL) || choice?.optString("finish_reason") == "error") {
            reply.status = "error"
            reply.error = (error as? JSONObject)?.optString("message")?.takeIf { it.isNotEmpty() }
                ?: (error as? String) ?: "The provider returned an error."
            reply.finish = "error"
            return
        }
        if (choice == null) return
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: JSONObject()
        val before = progress(reply)
        when (val content = delta.opt("content")) {
            is String -> reply.text.append(content)
            is JSONArray -> content.objects().filter { it.optString("type") == "text" }.forEach { reply.text.append(it.optString("text")) }
        }
        val reasoning = delta.opt("reasoning")
        if (reasoning is String) reply.reasoning.append(reasoning)
        delta.optJSONArray("reasoning_details").objects().forEach { detail ->
            mergeReasoning(reply, detail)
            if (reasoning !is String) when (detail.optString("type")) {
                "reasoning.text" -> reply.reasoning.append(detail.optString("text"))
                "reasoning.summary" -> reply.reasoning.append(detail.optString("summary"))
            }
        }
        delta.optJSONArray("tool_calls").objects().forEach { mergeToolCall(reply, it) }
        if (progress(reply) > before && reply.status == "waiting") reply.status = "streaming"
        choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { reply.finish = it }
    }

    private fun progress(r: AgentReply) = r.text.length + r.reasoning.length + r.tools.size + r.tools.sumOf { it.args.length }

    private fun mergeReasoning(reply: AgentReply, detail: JSONObject) {
        val type = detail.optString("type")
        val index = if (detail.has("index")) detail.optInt("index") else null
        val previous = index?.let { i -> reply.reasoningDetails.firstOrNull { it.optInt("index", -1) == i && it.optString("type") == type } }
        if (previous == null) { reply.reasoningDetails += JSONObject(detail.toString()); return }
        for (key in listOf("text", "summary", "data")) if (detail.opt(key) is String) previous.put(key, previous.optString(key) + detail.getString(key))
        for (key in listOf("signature", "id", "format")) detail.opt(key)?.takeIf { it != JSONObject.NULL && it.toString().isNotEmpty() }?.let { previous.put(key, it) }
    }

    private fun mergeToolCall(reply: AgentReply, call: JSONObject) {
        val fn = call.optJSONObject("function") ?: JSONObject()
        val id = call.optString("id")
        val name = fn.optString("name")
        val index = if (call.has("index")) call.optInt("index") else null
        var target: AgentToolCall? = when {
            index != null -> reply.slots[index]
            // Off spec: an id names its call, a bare fragment continues the latest one.
            id.isNotEmpty() -> reply.tools.firstOrNull { it.id == id }
            name.isEmpty() -> reply.tools.lastOrNull()
            else -> null
        }
        if (target == null) {
            target = AgentToolCall(id.ifEmpty { "call_${index ?: reply.tools.size}" })
            if (id.isEmpty()) reply.madeUp += target
            reply.tools += target
            if (index != null) reply.slots[index] = target
        }
        // Some providers repeat the id and name in every fragment: set them once, never append.
        if (id.isNotEmpty() && target in reply.madeUp) { target.id = id; reply.madeUp -= target }
        if (name.isNotEmpty() && target.name.isEmpty()) target.name = name
        fn.opt("arguments").let { if (it is String) target.args += it }
    }

    /** Whether a call's arguments arrived whole: a turn cut off mid-call leaves broken JSON. */
    fun argsComplete(raw: String): Boolean = raw.isBlank() || runCatching { JSONObject(raw) }.isSuccess

    fun parseArgs(raw: String): JSONObject = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

    /** The turn as Chat Completions history, with what a tool loop needs back. */
    fun forHistory(reply: AgentReply): JSONObject {
        val calls = reply.tools.filter { it.name.isNotEmpty() }
        val out = JSONObject().put("role", "assistant")
        out.put("content", if (reply.text.isNotEmpty()) reply.text.toString() else if (calls.isNotEmpty()) "" else JSONObject.NULL)
        if (calls.isNotEmpty()) out.put("tool_calls", JSONArray(calls.map { c ->
            JSONObject().put("id", c.id).put("type", "function").put("function",
                JSONObject().put("name", c.name).put("arguments", if (argsComplete(c.args) && c.args.isNotBlank()) c.args else "{}"))
        }))
        if (reply.reasoningDetails.isNotEmpty()) out.put("reasoning_details", JSONArray(reply.reasoningDetails.map { JSONObject(it.toString()) }))
        return out
    }
}
